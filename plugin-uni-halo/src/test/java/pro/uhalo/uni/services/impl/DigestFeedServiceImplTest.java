package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RSS / Atom 解析：命名空间兼容、链接与日期兜底、头图两级兜底。
 *
 * <p>这段逻辑静默失败的表现是「一条文章都没抓到」，线上很难定位，所以逐个分支都钉住。</p>
 */
class DigestFeedServiceImplTest {

    private final DigestFeedServiceImpl service = new DigestFeedServiceImpl();
    private final DigestFeedServiceImpl.FeedSource feed =
        new DigestFeedServiceImpl.FeedSource("example.com", "https://example.com/feed", "https://example.com");

    @Test
    void parseRssItemsWithoutNamespace() throws Exception {
        var rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Blog</title>
                <item>
                  <title>Rust &amp; performance</title>
                  <link>https://example.com/posts/rust</link>
                  <pubDate>Tue, 09 Sep 2026 10:00:00 GMT</pubDate>
                  <description><![CDATA[<p>Hello <b>world</b></p><img src="/cover.jpg">]]></description>
                </item>
              </channel>
            </rss>
            """;

        var articles = service.parseItems(rss, feed);

        assertThat(articles).hasSize(1);
        var article = articles.get(0);
        assertThat(article.getTitle()).isEqualTo("Rust & performance");
        assertThat(article.getLink()).isEqualTo("https://example.com/posts/rust");
        assertThat(article.getPubDate()).isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(article.getDescription()).isEqualTo("Hello world");
        // 正文内嵌图按相对地址补全为绝对地址
        assertThat(article.getImageUrl()).isEqualTo("https://example.com/cover.jpg");
        assertThat(article.getSourceName()).isEqualTo("example.com");
        assertThat(article.getSourceUrl()).isEqualTo("https://example.com");
    }

    @Test
    void parseAtomEntries() throws Exception {
        var atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Blog</title>
              <entry>
                <title>Formal methods</title>
                <link rel="alternate" href="https://example.com/atom-post"/>
                <link rel="self" href="https://example.com/feed"/>
                <published>2026-09-10T08:30:00Z</published>
                <summary>Summary text</summary>
              </entry>
            </feed>
            """;

        var articles = service.parseItems(atom, feed);

        assertThat(articles).hasSize(1);
        var article = articles.get(0);
        assertThat(article.getTitle()).isEqualTo("Formal methods");
        // 有 rel=alternate 时优先取它，而不是 rel=self 的 feed 地址
        assertThat(article.getLink()).isEqualTo("https://example.com/atom-post");
        assertThat(article.getPubDate()).isEqualTo(Instant.parse("2026-09-10T08:30:00Z"));
        assertThat(article.getDescription()).isEqualTo("Summary text");
    }

    @Test
    void atomLinkShouldFallBackToFirstHrefWhenRelMissing() throws Exception {
        var atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>No rel</title>
                <link href="https://example.com/plain"/>
                <updated>2026-09-10T08:30:00Z</updated>
              </entry>
            </feed>
            """;

        var articles = service.parseItems(atom, feed);

        assertThat(articles.get(0).getLink()).isEqualTo("https://example.com/plain");
    }

    @Test
    void itemImageShouldFallBackToMediaContentAndEnclosure() throws Exception {
        var withMedia = """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><item>
              <title>Media</title>
              <link>https://example.com/media</link>
              <media:thumbnail url="https://cdn.example.com/thumb.png"/>
            </item></channel></rss>
            """;
        assertThat(service.parseItems(withMedia, feed).get(0).getImageUrl())
            .isEqualTo("https://cdn.example.com/thumb.png");

        var withEnclosure = """
            <rss version="2.0"><channel><item>
              <title>Enclosure</title>
              <link>https://example.com/enc</link>
              <enclosure url="https://cdn.example.com/audio.mp3" type="audio/mpeg"/>
              <enclosure url="https://cdn.example.com/photo.jpg" type="image/jpeg"/>
            </item></channel></rss>
            """;
        // 非 image 类型的 enclosure 不能当头图（播客节目的音频链接非常常见）
        assertThat(service.parseItems(withEnclosure, feed).get(0).getImageUrl())
            .isEqualTo("https://cdn.example.com/photo.jpg");
    }

    @Test
    void itemWithoutTitleAndLinkShouldBeSkipped() throws Exception {
        var rss = """
            <rss version="2.0"><channel>
              <item><pubDate>Tue, 09 Sep 2026 10:00:00 GMT</pubDate></item>
              <item><title>Valid</title><link>https://example.com/valid</link></item>
            </channel></rss>
            """;

        var articles = service.parseItems(rss, feed);

        assertThat(articles).hasSize(1);
        assertThat(articles.get(0).getTitle()).isEqualTo("Valid");
    }

    @Test
    void descriptionShouldBeTrimmedToKeepPromptSmall() throws Exception {
        // 2100 个字符：超过 2000 的截断上限，验证不会把全文无限喂给 AI
        var longText = "y".repeat(2100);
        var rss = """
            <rss version="2.0"><channel><item>
              <title>Long</title>
              <link>https://example.com/long</link>
              <description>%s</description>
            </item></channel></rss>
            """.formatted(longText);

        assertThat(service.parseItems(rss, feed).get(0).getDescription()).hasSize(2000);
    }

    @Test
    void parseDateShouldHandleCommonFeedFormats() {
        assertThat(DigestFeedServiceImpl.parseDate("Tue, 09 Sep 2026 10:00:00 GMT"))
            .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("Tue, 09 Sep 2026 10:00:00 +0800"))
            .isEqualTo(Instant.parse("2026-09-09T02:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("2026-09-09T10:00:00+08:00"))
            .isEqualTo(Instant.parse("2026-09-09T02:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("2026-09-09T10:00:00Z"))
            .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("2026-09-09 10:00:00"))
            .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("")).isNull();
        assertThat(DigestFeedServiceImpl.parseDate("not a date")).isNull();
    }

    @Test
    void parseDateShouldIgnoreWrongWeekdayPrefix() {
        // 2026-09-09 其实是周三；源站星期几填错时不能整条丢掉
        assertThat(DigestFeedServiceImpl.parseDate("Tue, 09 Sep 2026 10:00:00 GMT"))
            .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(DigestFeedServiceImpl.parseDate("Monday, 9 Sep 2026 10:00:00 GMT"))
            .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
    }

    @Test
    void malformedXmlShouldThrowSoTheFeedIsSkipped() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.parseItems("<rss><channel><item></channel>", feed))
            .isInstanceOf(Exception.class);
    }
}
