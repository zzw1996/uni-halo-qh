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
}
