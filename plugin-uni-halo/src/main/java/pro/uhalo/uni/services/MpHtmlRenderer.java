package pro.uhalo.uni.services;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Markdown → <b>微信公众号可用的 HTML</b>。
 *
 * <p>为什么不用现成的 HTML 渲染器再「清洗」：微信的正文白名单不公开，已知它会丢掉
 * {@code class}/{@code id}、{@code <style>}、JS，并且对 {@code <div>}、flex/grid、
 * CSS 变量、渐变这些都不友好（社区实测会被剥离或渲染错乱）。
 * 与其渲染出通用 HTML 再猜哪些会被删，不如直接按微信的脾气出标签：
 * <b>只用 section / p / span / strong / em / ul / ol / li / img / br</b>，
 * 样式全部内联在 {@code style} 属性里，图片先写原始地址、由
 * {@code MpImageService} 在推送前换成微信图床地址。</p>
 *
 * <p>已知会丢内容的节点直接跳过：原始 HTML 块（日报合并版里的 {@code <details>}）、
 * Mermaid 代码块（微信不渲染图表，留着只是一段乱码）。</p>
 */
@Component
public class MpHtmlRenderer {

    // ── 内联样式（微信正文里唯一可靠的样式通道） ──

    private static final String S_BODY = "font-size:16px;line-height:1.75;color:#333;margin:0 0 16px;";
    private static final String S_H1 = "font-size:20px;font-weight:bold;line-height:1.5;color:#222;"
        + "margin:26px 0 14px;";
    private static final String S_H2 = "font-size:18px;font-weight:bold;line-height:1.5;color:#222;"
        + "margin:24px 0 12px;";
    private static final String S_H3 = "font-size:17px;font-weight:bold;line-height:1.5;color:#222;"
        + "margin:20px 0 10px;";
    private static final String S_QUOTE = "border-left:3px solid #d0d7de;padding:2px 0 2px 12px;"
        + "color:#57606a;font-size:15px;margin:0 0 16px;";
    private static final String S_LIST = "padding-left:22px;margin:0 0 16px;";
    private static final String S_LIST_ITEM = "font-size:15px;line-height:1.75;color:#333;margin:0 0 6px;";
    private static final String S_CODE_BLOCK = "background-color:#f6f8fa;border-radius:6px;"
        + "padding:12px;font-family:Menlo,Consolas,monospace;font-size:13px;line-height:1.6;"
        + "white-space:pre-wrap;word-break:break-all;color:#24292f;margin:0 0 16px;";
    private static final String S_CODE_INLINE = "background-color:#f6f8fa;border-radius:3px;"
        + "padding:1px 5px;font-family:Menlo,Consolas,monospace;font-size:14px;color:#24292f;";
    private static final String S_IMAGE = "max-width:100%;height:auto;display:block;margin:0 auto 14px;";
    private static final String S_DIVIDER = "height:1px;background-color:#e8e8e8;margin:22px 0;";
    private static final String S_TABLE_ROW = "font-size:14px;line-height:1.7;color:#57606a;"
        + "margin:0 0 8px;";
    private static final String S_EMPHASIS = "color:#57606a;";

    private final Parser parser;

    public MpHtmlRenderer() {
        var extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    /** 渲染为微信可用的 HTML；空输入返回空串 */
    public String render(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        var output = new StringBuilder(markdown.length() * 2);
        var visitor = new MpVisitor(output);
        parser.parse(markdown).accept(visitor);
        return output.toString();
    }

    /** 解析出的纯文本（表格展平、摘要兜底都用它） */
    public static String plainText(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        var extensions = List.of(TablesExtension.create());
        var parser = Parser.builder().extensions(extensions).build();
        var text = new StringBuilder();
        parser.parse(markdown).accept(new PlainTextVisitor(text));
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    // ── HTML 输出 ──

    private static final class MpVisitor extends AbstractVisitor {

        private final StringBuilder out;

        private MpVisitor(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void visit(Document document) {
            visitChildren(document);
        }

        @Override
        public void visit(Paragraph paragraph) {
            out.append("<p style=\"").append(S_BODY).append("\">");
            visitChildren(paragraph);
            out.append("</p>");
        }

        @Override
        public void visit(Heading heading) {
            var style = switch (heading.getLevel()) {
                case 1 -> S_H1;
                case 2 -> S_H2;
                default -> S_H3;
            };
            // 微信会把 h1/h2 降级处理，统一用段落 + 字号表达层级
            out.append("<p style=\"").append(style).append("\">");
            visitChildren(heading);
            out.append("</p>");
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            out.append("<section style=\"").append(S_QUOTE).append("\">");
            visitChildren(blockQuote);
            out.append("</section>");
        }

        @Override
        public void visit(BulletList bulletList) {
            out.append("<ul style=\"").append(S_LIST).append("\">");
            visitChildren(bulletList);
            out.append("</ul>");
        }

        @Override
        public void visit(OrderedList orderedList) {
            out.append("<ol style=\"").append(S_LIST).append("\">");
            visitChildren(orderedList);
            out.append("</ol>");
        }

        @Override
        public void visit(ListItem listItem) {
            out.append("<li style=\"").append(S_LIST_ITEM).append("\">");
            visitChildren(listItem);
            out.append("</li>");
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {
            out.append("<section style=\"").append(S_DIVIDER).append("\"></section>");
        }

        @Override
        public void visit(Text text) {
            out.append(escape(text.getLiteral()));
        }

        @Override
        public void visit(StrongEmphasis strongEmphasis) {
            out.append("<strong style=\"font-weight:bold;\">");
            visitChildren(strongEmphasis);
            out.append("</strong>");
        }

        @Override
        public void visit(Emphasis emphasis) {
            out.append("<em style=\"").append(S_EMPHASIS).append("font-style:italic;\">");
            visitChildren(emphasis);
            out.append("</em>");
        }

        /**
         * 链接：只保留可见文字，不输出 {@code <a>}。
         *
         * <p>公众号正文里可点击的超链接只能指向公众号文章，其它域名会被降级成纯文本；
         * 与其让微信替我们降级，不如直接给文本——跳转统一走「阅读原文」。</p>
         */
        @Override
        public void visit(Link link) {
            visitChildren(link);
        }

        @Override
        public void visit(Image image) {
            if (!StringUtils.hasText(image.getDestination())) {
                return;
            }
            out.append("<img src=\"").append(escapeAttribute(image.getDestination()))
                .append("\" style=\"").append(S_IMAGE).append("\" />");
        }

        @Override
        public void visit(Code code) {
            out.append("<span style=\"").append(S_CODE_INLINE).append("\">")
                .append(escape(code.getLiteral())).append("</span>");
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            var info = fencedCodeBlock.getInfo() == null ? "" : fencedCodeBlock.getInfo().trim();
            if (info.toLowerCase(java.util.Locale.ROOT).startsWith("mermaid")) {
                // 微信不渲染图表：留在正文里只会是一段乱码
                return;
            }
            appendCodeBlock(fencedCodeBlock.getLiteral());
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
            appendCodeBlock(indentedCodeBlock.getLiteral());
        }

        private void appendCodeBlock(String literal) {
            if (!StringUtils.hasText(literal)) {
                return;
            }
            out.append("<section style=\"").append(S_CODE_BLOCK).append("\">")
                .append(escape(literal.stripTrailing())).append("</section>");
        }

        /** 原始 HTML 一律丢弃：{@code <details>} 等标签微信不认识，内容留着也只是纯文本噪音 */
        @Override
        public void visit(HtmlBlock htmlBlock) {
        }

        @Override
        public void visit(HtmlInline htmlInline) {
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            out.append(' ');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            out.append("<br />");
        }

        /**
         * 表格：展平成带列名的文字行。
         *
         * <p>公众号正文对 {@code <table>} 的支持是「能用但很难看」，而日报里的表只有
         * 「扫描源 / 抓取文章 / 时间范围 / 精选」一行数据——展平成
         * 「<b>扫描源</b> 88/92 ｜ <b>抓取文章</b> 300 篇 → 15 篇」反而更适合手机阅读。</p>
         *
         * <p>表格节点来自 GFM 扩展（{@code TableBlock} 是 {@code CustomBlock} 的子类），
         * 访问者接口里没有它自己的重载，所以在这里按类型分流。</p>
         */
        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tableBlock) {
                renderTable(tableBlock);
                return;
            }
            visitChildren(customBlock);
        }

        private void renderTable(Node tableBlock) {
            var header = new java.util.ArrayList<String>();
            var rows = new java.util.ArrayList<List<String>>();
            collectTable(tableBlock, header, rows);
            if (rows.isEmpty()) {
                return;
            }
            for (var row : rows) {
                var parts = new java.util.ArrayList<String>();
                for (var i = 0; i < row.size(); i++) {
                    var label = i < header.size() ? header.get(i) : "";
                    var value = row.get(i);
                    if (!StringUtils.hasText(label)) {
                        parts.add(escape(value));
                        continue;
                    }
                    parts.add("<strong style=\"color:#333;\">" + escape(label) + "</strong> "
                        + escape(value));
                }
                out.append("<p style=\"").append(S_TABLE_ROW).append("\">")
                    .append(String.join(" ｜ ", parts)).append("</p>");
            }
        }

        /** 收集表头与数据行（单元格取纯文本；按文档顺序遍历，首行全是表头单元格时作为表头） */
        private void collectTable(Node table, List<String> header, List<List<String>> rows) {
            var queue = new java.util.ArrayDeque<Node>();
            queue.add(table);
            while (!queue.isEmpty()) {
                var node = queue.poll();
                if (node instanceof TableRow row) {
                    var cells = new java.util.ArrayList<String>();
                    var allHeader = true;
                    for (var child = row.getFirstChild(); child != null; child = child.getNext()) {
                        if (child instanceof TableCell cell) {
                            cells.add(plainTextOf(cell));
                            allHeader = allHeader && cell.isHeader();
                        }
                    }
                    if (header.isEmpty() && rows.isEmpty() && allHeader) {
                        header.addAll(cells);
                    } else {
                        rows.add(cells);
                    }
                    continue;
                }
                for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
                    queue.add(child);
                }
            }
        }

        /** 单元格 / 任意节点的纯文本 */
        private static String plainTextOf(Node node) {
            var text = new StringBuilder();
            node.accept(new PlainTextVisitor(text));
            return text.toString().replaceAll("\\s+", " ").trim();
        }
    }

    /** 只收集可读文本的访问器（跳过图片、代码块以外的结构） */
    private static final class PlainTextVisitor extends AbstractVisitor {

        private final StringBuilder text;

        private PlainTextVisitor(StringBuilder text) {
            this.text = text;
        }

        @Override
        public void visit(Text node) {
            text.append(node.getLiteral());
        }

        @Override
        public void visit(Code node) {
            text.append(node.getLiteral());
        }

        @Override
        public void visit(Image image) {
            // 图片没有可读文本
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
        }

        @Override
        public void visit(HtmlInline htmlInline) {
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
        }
    }

    // ── 转义 ──

    static String escape(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeAttribute(String text) {
        return escape(text).replace("\"", "&quot;");
    }
}
