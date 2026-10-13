package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信安全 HTML 渲染：断言的都是「微信会丢内容」的位置——
 * 不出现 div/class/style 标签、表格被展平、原始 HTML 与 Mermaid 被跳过、图片保留原地址待替换。
 */
class MpHtmlRendererTest {

    private final MpHtmlRenderer renderer = new MpHtmlRenderer();

    @Test
    void shouldRenderParagraphWithInlineStyleOnly() {
        var html = renderer.render("第一段\n\n第二段");
        assertThat(html).contains("<p style=");
        assertThat(html).doesNotContain("class=");
        assertThat(html).doesNotContain("<div");
        assertThat(html).contains("第一段").contains("第二段");
    }

    @Test
    void headingsShouldBecomeStyledParagraphs() {
        var html = renderer.render("## 一、标题\n\n正文");
        // 微信会把 h1/h2 降级处理，所以统一用段落 + 字号
        assertThat(html).doesNotContain("<h2");
        assertThat(html).contains("font-size:18px");
        assertThat(html).contains("一、标题");
    }

    @Test
    void linksShouldBeDowngradedToPlainText() {
        var html = renderer.render("[原标题](https://example.com/post) — 来源");
        assertThat(html).doesNotContain("<a ");
        assertThat(html).doesNotContain("https://example.com/post");
        assertThat(html).contains("原标题").contains("来源");
    }

    @Test
    void imagesShouldKeepSourceUrlForLaterRewrite() {
        var html = renderer.render("![](https://site.example/upload/a.jpg)");
        assertThat(html).contains("<img src=\"https://site.example/upload/a.jpg\"");
        assertThat(html).contains("max-width:100%");
    }

    @Test
    void mermaidBlocksShouldBeSkipped() {
        var html = renderer.render("""
            前文

            ```mermaid
            pie showData
                "AI" : 3
            ```

            后文
            """);
        assertThat(html).doesNotContain("mermaid");
        assertThat(html).doesNotContain("pie showData");
        assertThat(html).contains("前文").contains("后文");
    }

    @Test
    void plainCodeBlocksShouldBecomePreformattedSections() {
        var html = renderer.render("```\nAI  ████ 3\n```");
        assertThat(html).contains("white-space:pre-wrap");
        assertThat(html).contains("AI");
    }

    @Test
    void rawHtmlShouldBeDropped() {
        var html = renderer.render("""
            <details>
            <summary>纯文本图</summary>

            AI  ████ 3
            </details>
            """);
        assertThat(html).doesNotContain("details");
        assertThat(html).doesNotContain("纯文本图");
    }

    @Test
    void tablesShouldBeFlattenedIntoLabelledLines() {
        var markdown = """
            | 扫描源 | 抓取文章 | 时间范围 | 精选 |
            |:---:|:---:|:---:|:---:|
            | 88/92 | 300 篇 → 15 篇 | 48h | **15 篇** |
            """;
        var html = renderer.render(markdown);
        assertThat(html).doesNotContain("<table");
        assertThat(html).contains("扫描源");
        assertThat(html).contains("88/92");
        assertThat(html).contains("48h");
    }

    @Test
    void blockQuoteAndListsShouldUseSectionsAndLi() {
        var html = renderer.render("""
            > 导语一段

            - 要点一
            - 要点二
            """);
        assertThat(html).contains("<section style=\"border-left");
        assertThat(html).contains("<li style=");
        assertThat(html).contains("导语一段").contains("要点一");
    }

    @Test
    void plainTextShouldFlattenMarkdown() {
        var text = MpHtmlRenderer.plainText("# 标题\n\n正文 **加粗** 与 [链接](https://x.com)");
        assertThat(text).contains("标题").contains("正文").contains("加粗").contains("链接");
        assertThat(text).doesNotContain("#").doesNotContain("https://x.com");
    }

    @Test
    void htmlShouldBeEscaped() {
        var html = renderer.render("1 < 2 && 3 > 2");
        assertThat(html).contains("&lt;").contains("&amp;").contains("&gt;");
        assertThat(html).doesNotContain("1 < 2");
    }
}
