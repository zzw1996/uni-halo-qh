package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown → HTML 渲染。
 *
 * <p>Halo 服务端不渲染 markdown（编辑器是浏览器端渲染后提交 HTML），
 * 所以写进 content 的必须是渲染结果，否则文章正文是空的。</p>
 */
class DigestMarkdownRendererTest {

    private final DigestMarkdownRenderer renderer = new DigestMarkdownRenderer();

    @Test
    void shouldRenderCommonDigestConstructs() {
        var markdown = """
            # 标题

            > 引用段落

            **核心论点**

            - 第一条
            - 第二条

            [原文链接](https://example.com/a) 和 ![配图](https://example.com/a.jpg)

            | 扫描源 | 精选 |
            |:---:|:---:|
            | 81/92 | 15 篇 |

            <details><summary>纯文本图</summary>

            ```
            llm │ ████
            ```

            </details>
            """;

        var html = renderer.render(markdown);

        assertThat(html).contains("<h1>标题</h1>");
        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("<strong>核心论点</strong>");
        assertThat(html).contains("<li>第一条</li>");
        assertThat(html).contains("<a href=\"https://example.com/a\">原文链接</a>");
        assertThat(html).contains("<img src=\"https://example.com/a.jpg\" alt=\"配图\"");
        // GFM 表格扩展：日报的「数据概览」就是表格
        assertThat(html).contains("<table>");
        // 原始 HTML（折叠块）原样透传，不能被转义
        assertThat(html).contains("<details>").contains("<summary>纯文本图</summary>");
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render("")).isEmpty();
        assertThat(renderer.render("   ")).isEmpty();
    }
}
