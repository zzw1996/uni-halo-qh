package pro.uhalo.uni.services;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Markdown → HTML 渲染。
 *
 * <p>Halo 服务端**不做 markdown 渲染**：后台编辑器是在浏览器里渲染好 HTML 后，
 * 把 HTML 放进 {@code content} 字段提交的（源码与依赖里都没有任何 markdown 库）。
 * 因此插件必须自己渲染，否则写进去的文章正文会是空的——`raw` 仍保存 markdown 原文，
 * 便于站长在 Halo 编辑器里继续以 markdown 方式修改。</p>
 *
 * <p>启用 GFM 表格扩展（日报里的数据概览是表格）；原始 HTML（日报里的
 * {@code <details>} 折叠块）默认原样透传，不做转义。</p>
 */
@Component
public class DigestMarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public DigestMarkdownRenderer() {
        var extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    /** 渲染为 HTML；空输入返回空串（Halo 接受空 content） */
    public String render(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }
}
