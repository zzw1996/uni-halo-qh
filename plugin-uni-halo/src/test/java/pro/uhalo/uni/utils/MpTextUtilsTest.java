package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公众号字数治理：上限全部来自官方 draft/add 文档，这些断言就是「改坏了自己知道」的护栏。
 */
class MpTextUtilsTest {

    @Test
    void clipShouldTrimToCodePointsWithoutEllipsis() {
        var raw = "一个汉字算一个字".repeat(30);
        var clipped = MpTextUtils.clip(raw, 120);
        assertThat(MpTextUtils.count(clipped)).isEqualTo(120);
        assertThat(clipped).doesNotEndWith("…");
    }

    @Test
    void clipShouldCollapseWhitespace() {
        var clipped = MpTextUtils.clip("  第一行\n\n  第二行\t第三行  ", 120);
        assertThat(clipped).isEqualTo("第一行 第二行 第三行");
    }

    @Test
    void clipTitleShouldAddEllipsisWithinLimit() {
        var title = "这是一条特别长的公众号标题".repeat(5);
        var clipped = MpTextUtils.clipTitle(title, MpTextUtils.MAX_TITLE_CHARS);
        assertThat(MpTextUtils.count(clipped)).isEqualTo(MpTextUtils.MAX_TITLE_CHARS);
        assertThat(clipped).endsWith("…");
    }

    @Test
    void clipTitleShouldKeepShortTitleUntouched() {
        var title = "AI 发展日报 · 2026-10-09";
        assertThat(MpTextUtils.clipTitle(title, MpTextUtils.MAX_TITLE_CHARS)).isEqualTo(title);
    }

    @Test
    void clipShouldCountEmojiAsOneCharacter() {
        // 官方口径按「字」算：emoji 按 1 个字，不能用 String.length（会是 2）
        var raw = "🤖".repeat(200);
        var clipped = MpTextUtils.clip(raw, 120);
        assertThat(MpTextUtils.count(clipped)).isEqualTo(120);
        assertThat(clipped.length()).isEqualTo(240);
    }

    @Test
    void clipContentShouldAppendNoticeWhenTruncated() {
        var html = "<p>" + "长".repeat(MpTextUtils.MAX_CONTENT_CHARS + 100) + "</p>";
        var clipped = MpTextUtils.clipContent(html, MpTextUtils.MAX_CONTENT_CHARS);
        assertThat(clipped).contains("超过微信 2 万字符上限");
        assertThat(MpTextUtils.count(clipped))
            .isLessThan(MpTextUtils.MAX_CONTENT_CHARS + 120);
    }

    @Test
    void clipContentShouldCutAtBlockBoundary() {
        // 每个段落 ~200 字符：超长时必须整段保留，不能把 <p>/</p> 切一半
        var paragraph = "<p>" + "段".repeat(196) + "</p>";
        var html = paragraph.repeat(120);
        var clipped = MpTextUtils.clipContent(html, MpTextUtils.MAX_CONTENT_CHARS);

        assertThat(clipped).doesNotContain("段</p>" + "段");  // 结尾一定是完整的段落
        assertThat(clipped.endsWith("</p>" + MpTextUtils.TRUNCATED_NOTE))
            .as("截断点落在块级标签边界上")
            .isTrue();
    }

    @Test
    void clipContentShouldFallBackToHardCutForPlainText() {
        var plain = "话".repeat(MpTextUtils.MAX_CONTENT_CHARS + 50);
        var clipped = MpTextUtils.clipContent(plain, MpTextUtils.MAX_CONTENT_CHARS);
        assertThat(MpTextUtils.count(clipped))
            .isEqualTo(MpTextUtils.MAX_CONTENT_CHARS + MpTextUtils.count(MpTextUtils.TRUNCATED_NOTE));
    }

    @Test
    void exceedsContentLimitShouldFlagOverlongBodyOnly() {
        assertThat(MpTextUtils.exceedsContentLimit("<p>很短的正文</p>")).isFalse();
        assertThat(MpTextUtils.exceedsContentLimit(null)).isFalse();
        assertThat(MpTextUtils.exceedsContentLimit(
            "<p>" + "长".repeat(MpTextUtils.MAX_CONTENT_CHARS + 1) + "</p>")).isTrue();
    }

    @Test
    void clipContentShouldKeepShortContent() {
        var html = "<p>很短的正文</p>";
        assertThat(MpTextUtils.clipContent(html, MpTextUtils.MAX_CONTENT_CHARS)).isEqualTo(html);
    }

    @Test
    void tidyTailShouldDropDanglingPunctuation() {
        assertThat(MpTextUtils.tidyTail("本期最重要的进展是，")).isEqualTo("本期最重要的进展是");
        assertThat(MpTextUtils.tidyTail("正常结尾。")).isEqualTo("正常结尾。");
        assertThat(MpTextUtils.tidyTail("多标点，，；")).isEqualTo("多标点");
    }

    @Test
    void clipRawShouldNotCollapseWhitespace() {
        assertThat(MpTextUtils.clipRaw("https://a.com/x?y=1 z", 100))
            .isEqualTo("https://a.com/x?y=1 z");
    }
}
