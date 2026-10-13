package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

/**
 * 公众号字段字数治理。
 *
 * <p>所有上限都来自官方 draft/add 文档（标题 32 字、作者 16 字、摘要 120 字、
 * 正文 2 万字符），集中在这里是为了「微信改版时只改一处」，也便于单测钉住行为。</p>
 *
 * <p>计数口径一律按 code point（一个汉字算一个字、emoji 也算一个字），
 * 与微信「字」的口径一致，避免按 {@code String.length()} 把 emoji 算成两个字而提前截断。</p>
 */
public final class MpTextUtils {

    /** 标题上限（官方：总长度不超过 32 个字） */
    public static final int MAX_TITLE_CHARS = 32;

    /** 作者上限（官方：总长度不超过 16 个字） */
    public static final int MAX_AUTHOR_CHARS = 16;

    /** 摘要（引言）上限（官方：总长度不超过 120 个字；不填则微信自动抓正文前 54 字） */
    public static final int MAX_DIGEST_CHARS = 120;

    /** 正文上限（官方：少于 2 万字符、小于 1M） */
    public static final int MAX_CONTENT_CHARS = 20000;

    /** 阅读原文链接上限（官方：不超过 1KB） */
    public static final int MAX_SOURCE_URL_CHARS = 1024;

    private MpTextUtils() {
    }

    /** 按 code point 计数的字数（空白原样计入，判断用） */
    public static int count(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * 折叠空白 + 按 code point 截断（不加省略号）。
     *
     * <p>摘要用这个：省略号本身也占一个字，不如把位置留给内容。</p>
     */
    public static String clip(String raw, int maxChars) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        var collapsed = raw.replaceAll("\\s+", " ").trim();
        return clipRaw(collapsed, maxChars);
    }

    /**
     * 折叠空白 + 截断，截断时补一个「…」（省略号计入上限）。
     *
     * <p>标题用这个：读者能看出标题被截过，但不会超出微信上限。</p>
     */
    public static String clipTitle(String raw, int maxChars) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        var collapsed = raw.replaceAll("\\s+", " ").trim();
        if (count(collapsed) <= maxChars) {
            return collapsed;
        }
        var body = clipRaw(collapsed, Math.max(1, maxChars - 1));
        return body + "…";
    }

    /**
     * 多行文本截断（正文用）：保留换行，只在超出上限时从尾部截掉。
     *
     * <p>截断点会往前找一个<b>块级标签边界</b>（{@code </p>}{@code </section>}{@code </ul>}
     * {@code </ol>}{@code </li>}）：按字符数硬切会把标签切一半，公众号编辑器遇到未闭合的
     * 标签容易被整块吃掉，表现成「文章只有一半甚至只剩标题」。</p>
     */
    public static String clipContent(String html, int maxChars) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        if (count(html) <= maxChars) {
            return html;
        }
        var limit = html.offsetByCodePoints(0, maxChars);
        var cut = lastBlockBoundary(html, limit);
        return html.substring(0, cut) + TRUNCATED_NOTE;
    }

    /** 正文是否超出微信上限（生成阶段据此提前提示站长，而不是等推送时才发现被截断） */
    public static boolean exceedsContentLimit(String html) {
        return StringUtils.hasText(html) && count(html) > MAX_CONTENT_CHARS;
    }

    /** 截断提示：正文上限 2 万字符，超了只能截，至少把「为什么短」写清楚 */
    static final String TRUNCATED_NOTE = "<p style=\"font-size:15px;color:#9ca3af;"
        + "margin:16px 0 0;\">（正文超过微信 2 万字符上限，已截断；完整内容请点击阅读原文）</p>";

    /** 往回找块级标签边界的窗口：太大等于白切一段，太小可能与「硬切」没区别 */
    private static final int BOUNDARY_SEARCH_CHARS = 1200;

    private static final String[] BLOCK_END_TAGS = {"</p>", "</section>", "</ul>", "</ol>", "</li>"};

    /**
     * 从 {@code limit} 往前找最近的一个块级标签结束位置；找不到（纯文本）时退回按字符位置
     * 硬切，保证返回的长度不会超过上限。
     */
    private static int lastBlockBoundary(String html, int limit) {
        var window = Math.max(0, limit - BOUNDARY_SEARCH_CHARS);
        var best = -1;
        for (var tag : BLOCK_END_TAGS) {
            var at = html.lastIndexOf(tag, limit - 1);
            if (at >= window && at + tag.length() > best) {
                best = at + tag.length();
            }
        }
        return best > 0 ? best : limit;
    }

    /** 不折叠空白、只按 code point 截断（链接等不宜折叠空白的文本用它） */
    public static String clipRaw(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        if (count(text) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    /** 收尾标点补齐：截断常把句子砍在半截，去掉尾部的孤立标点让摘要读起来不别扭 */
    public static String tidyTail(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var trimmed = text.trim();
        while (!trimmed.isEmpty()) {
            var last = trimmed.codePointAt(trimmed.length()
                - Character.charCount(trimmed.codePointBefore(trimmed.length())));
            if (last == '，' || last == '、' || last == '；' || last == '：' || last == ','
                || last == ';' || last == ':' || last == ' ') {
                trimmed = trimmed.substring(0, trimmed.length()
                    - Character.charCount(last)).trim();
                continue;
            }
            break;
        }
        return trimmed;
    }
}
