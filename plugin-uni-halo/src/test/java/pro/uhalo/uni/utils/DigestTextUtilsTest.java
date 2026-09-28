package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报文本处理：HTML 清洗、URL 补全与伪头图过滤。
 */
class DigestTextUtilsTest {

    @Test
    void stripHtmlShouldRemoveTagsAndDecodeEntities() {
        var text = DigestTextUtils.stripHtml(
            "<p>Rust &amp; compiler &#39;magic&#39;</p><br/><span>中文</span>");

        assertThat(text).isEqualTo("Rust & compiler 'magic'中文");
    }

    @Test
    void stripHtmlShouldReturnEmptyForBlankInput() {
        assertThat(DigestTextUtils.stripHtml(null)).isEmpty();
        assertThat(DigestTextUtils.stripHtml("   ")).isEmpty();
    }

    @Test
    void absolutizeUrlShouldResolveRelativeAndKeepAbsolute() {
        assertThat(DigestTextUtils.absolutizeUrl("/img/a.png", "https://blog.example.com/post/1"))
            .isEqualTo("https://blog.example.com/img/a.png");
        assertThat(DigestTextUtils.absolutizeUrl("https://cdn.example.com/a.png",
            "https://blog.example.com/post/1"))
            .isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void absolutizeUrlShouldReturnEmptyForBlankInsteadOfArticleUrl() {
        // 空值如果解析成 base，会把文章页地址当成图片地址存进报告
        assertThat(DigestTextUtils.absolutizeUrl("", "https://blog.example.com/post/1")).isEmpty();
        assertThat(DigestTextUtils.absolutizeUrl("   ", "https://blog.example.com/post/1")).isEmpty();
    }

    @Test
    void isUsableImageUrlShouldSkipFakeLeadImages() {
        assertThat(DigestTextUtils.isUsableImageUrl("https://cdn.example.com/a.jpg")).isTrue();
        assertThat(DigestTextUtils.isUsableImageUrl("https://example.com/favicon.ico")).isFalse();
        assertThat(DigestTextUtils.isUsableImageUrl("https://example.com/logo.png")).isFalse();
        assertThat(DigestTextUtils.isUsableImageUrl("https://example.com/avatar/u.jpg")).isFalse();
        assertThat(DigestTextUtils.isUsableImageUrl("/relative/a.jpg")).isFalse();
        assertThat(DigestTextUtils.isUsableImageUrl("")).isFalse();
    }

    @Test
    void extractInlineImageShouldPickFirstUsableImage() {
        var html = "<img src=\"/logo.png\"><p>text</p><img src=\"photos/cover.webp\">";

        assertThat(DigestTextUtils.extractInlineImage(html, "https://blog.example.com/post/1"))
            .isEqualTo("https://blog.example.com/post/photos/cover.webp");
    }

    @Test
    void extractInlineImageShouldReturnEmptyWhenOnlyFakeImages() {
        assertThat(DigestTextUtils.extractInlineImage("<img src=\"/favicon.png\">",
            "https://blog.example.com")).isEmpty();
    }

    @Test
    void slugifyShouldNormalizeToUrlSafeToken() {
        assertThat(DigestTextUtils.slugify("Simon Willison's Weblog")).isEqualTo("simon-willison-s-weblog");
        assertThat(DigestTextUtils.slugify("  ")).isEqualTo("image");
        assertThat(DigestTextUtils.slugify("中文")).isEqualTo("image");
    }

    @Test
    void slugifyShouldTrimTrailingDashAfterTruncation() {
        // 截断到 40 字符后若正好停在分隔符上，不能留下尾部横线（Halo slug 不允许）
        var slug = DigestTextUtils.slugify("abcdefghijklmnopqrstuvwxyz0123456789abc-defgh");
        assertThat(slug).isEqualTo("abcdefghijklmnopqrstuvwxyz0123456789abc");
        assertThat(slug).doesNotEndWith("-");
    }

    @Test
    void extensionHelpersShouldOnlyAcceptImages() {
        assertThat(DigestTextUtils.extensionFromUrl("https://a.com/x.JPEG?v=2")).isEqualTo("jpg");
        assertThat(DigestTextUtils.extensionFromUrl("https://a.com/x")).isEmpty();
        assertThat(DigestTextUtils.extensionFromContentType("image/png")).isEqualTo("png");
        assertThat(DigestTextUtils.extensionFromContentType("text/html; charset=utf-8")).isEmpty();
    }

    @Test
    void isTextualContentTypeShouldCatchHtmlErrorPages() {
        // 失效图片常常返回 HTML 错误页，不能被当成图片存进附件库
        assertThat(DigestTextUtils.isTextualContentType("text/html; charset=utf-8")).isTrue();
        assertThat(DigestTextUtils.isTextualContentType("application/json")).isTrue();
        assertThat(DigestTextUtils.isTextualContentType("image/jpeg")).isFalse();
        assertThat(DigestTextUtils.isTextualContentType("")).isFalse();
    }

    @Test
    void normalizeLinkShouldIgnoreSchemeWwwSlashAnchorAndTrackingParams() {
        var expected = "example.com/post/a";

        // 同一篇被转载时，链接往往只差这些
        assertThat(DigestTextUtils.normalizeLink("https://www.example.com/post/a/")).isEqualTo(expected);
        assertThat(DigestTextUtils.normalizeLink("http://example.com/post/a")).isEqualTo(expected);
        assertThat(DigestTextUtils.normalizeLink("https://EXAMPLE.com/post/a#comments")).isEqualTo(expected);
        assertThat(DigestTextUtils.normalizeLink(
            "https://example.com/post/a?utm_source=rss&utm_medium=feed&fbclid=x")).isEqualTo(expected);
    }

    @Test
    void normalizeLinkShouldKeepMeaningfulQueryParams() {
        // ?p=123 这类参数是文章身份的一部分，丢掉它会把不同文章误判成同一篇
        // （空路径的斜杠统一去掉，所以是 example.com?p=123）
        assertThat(DigestTextUtils.normalizeLink("https://example.com/?p=123"))
            .isEqualTo("example.com?p=123");
        assertThat(DigestTextUtils.normalizeLink("https://example.com/?p=123&utm_source=x"))
            .isEqualTo("example.com?p=123");
        // 站点根路径的两种写法是同一个地址
        assertThat(DigestTextUtils.normalizeLink("https://example.com/"))
            .isEqualTo(DigestTextUtils.normalizeLink("https://example.com"));
    }

    @Test
    void dedupKeyShouldFallBackToTitleWhenLinkIsMissing() {
        assertThat(DigestTextUtils.dedupKey("https://a.com/x", "标题"))
            .isEqualTo("link:a.com/x");
        // 没有链接时按标题去重（大小写与空白归一化），标题也没有才返回空
        assertThat(DigestTextUtils.dedupKey("", "Rust  Weekly"))
            .isEqualTo("title:rust weekly");
        assertThat(DigestTextUtils.dedupKey(null, null)).isEmpty();
        assertThat(DigestTextUtils.normalizeLink("")).isEmpty();
    }
}
