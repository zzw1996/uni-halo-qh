package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「今日头条号」配图规划：正文配图位解析、按位生图提示词、文案卡片文案、图片字节类型识别。
 *
 * <p>每篇文章<b>至少 3 张图</b>，这是硬口径（头条多图文章的推荐表现更好）。
 * 图片的<b>位置</b>由写稿模型在正文里标注：正文中单独一行的 {@code 【配图1：画面描述】}
 * 就是一个图位（{@link #bodySlots}）——模型最清楚哪句话讲完该配一张什么图，
 * 比事后均匀插图中肯得多。图位的画面描述直接进生图提示词（{@link #buildSlotPrompt}），
 * 图片与上下文因此是对得上的。</p>
 *
 * <p>图片来源分两级：</p>
 * <ul>
 *   <li>配了「图片模型」→ 按图位逐张 AI 生图；</li>
 *   <li>没配、或某张生成/上传失败 → 用文案卡片补齐（{@link #cardPlan} +
 *       {@code DigestCoverGenerator}），保证张数不会少。</li>
 * </ul>
 *
 * <p>模型忘了标（或标少了）也不影响硬口径：{@link #padSlots} 会按正文均匀补出图位，
 * 描述从小标题/首句/标签里取。AI 生图的提示词刻意写成「不含文字、不含真人、不含品牌」：
 * 模型画不好中文，画错的字直接毁图；真人/商标则可能踩到肖像与商标风险。</p>
 */
public final class ToutiaoImageHelper {

    /** 配图张数区间：下限就是「每篇至少 3 张」 */
    public static final int MIN_IMAGES = 3;
    public static final int MAX_IMAGES = 6;

    /** 默认画面风格（可在设置里改） */
    public static final String DEFAULT_STYLE_PROMPT = "写实摄影风格，光线自然，色彩干净，主体清晰";

    /** 默认出图尺寸（各家中转/官方都认 1024×1024 这个档位） */
    public static final String DEFAULT_SIZE = "1024x1024";

    /**
     * 正文里的配图标记行：{@code 【配图1：一句话画面描述】}（编号可以不连续，解析按出现顺序重排）。
     *
     * <p>写在提示词里的就是这格式；解析刻意放宽（冒号可缺、编号可缺），模型的格式纪律不可信。</p>
     */
    private static final Pattern SLOT_PATTERN =
        Pattern.compile("【配图\\s*(\\d*)\\s*[:：]?\\s*([^】]*)】");

    /** 上下文摘要的长度：生图提示词里带一小段「这张图前面在讲什么」，够定位即可 */
    private static final int CONTEXT_CHARS = 60;

    private static final int MAX_CARD_TITLE_CHARS = 36;

    private ToutiaoImageHelper() {
    }

    /** 正文里的一个图位：画面描述 + 它前面那段文字（生图时当上下文用） */
    public record BodySlot(int order, String description, String contextBefore) {
    }

    /**
     * 从正文里解析配图标记，按出现顺序返回图位（编号重排为 1..N）。
     *
     * <p>标记行前后允许有空行/空格；描述为空的图位也保留（生图提示词里还有主题与上下文兜底）。</p>
     */
    public static List<BodySlot> bodySlots(String body) {
        var slots = new ArrayList<BodySlot>();
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        var matcher = SLOT_PATTERN.matcher(body);
        var lastEnd = 0;
        var order = 0;
        while (matcher.find()) {
            var between = body.substring(lastEnd, matcher.start()).trim();
            order++;
            slots.add(new BodySlot(order, cleanDescription(matcher.group(2)),
                tailOf(between, CONTEXT_CHARS)));
            lastEnd = matcher.end();
        }
        return List.copyOf(slots);
    }

    /** 正文里的标记行（页面展示与纯文本复制时用得到） */
    public static String markerRegexSource() {
        return SLOT_PATTERN.pattern();
    }

    /**
     * 图位不足时按正文均匀补齐到目标张数（模型没标或标少了的兜底）。
     *
     * <p>已有图位原样保留；补出来的图位均匀落在正文文本块之间，
     * 描述依次取自小标题 → 正文首句 → 标签 → 关键词——都是文章里真实存在的内容，
     * 不是凭空的「意境图」。</p>
     */
    public static List<BodySlot> padSlots(List<BodySlot> slots, String body, int needed,
        List<String> titles, List<String> tags, String keyword) {
        var target = Math.max(Math.max(MIN_IMAGES, slots.size()),
            Math.min(MAX_IMAGES, Math.max(needed, MIN_IMAGES)));
        if (target > MAX_IMAGES) {
            target = MAX_IMAGES;
        }
        var result = new ArrayList<BodySlot>(slots);
        if (result.size() >= target) {
            return List.copyOf(result.subList(0, target));
        }
        // 描述候选池
        var pool = new ArrayList<String>();
        for (var heading : extractHeadings(body)) {
            if (!pool.contains(heading)) {
                pool.add(heading);
            }
        }
        var firstSentence = firstSentence(body);
        if (StringUtils.hasText(firstSentence) && !pool.contains(firstSentence)) {
            pool.add(firstSentence);
        }
        var tagsText = String.join("、", tags);
        if (StringUtils.hasText(tagsText) && !pool.contains(tagsText)) {
            pool.add(tagsText);
        }
        var theme = StringUtils.hasText(keyword) ? keyword.trim() : "主题";
        if (!pool.contains(theme)) {
            pool.add(theme);
        }
        // 均匀落点：把正文按标记切开，剩下的文本块均分给要补的图位
        var textBlocks = textBlocks(body);
        var usableBlocks = Math.max(1, textBlocks.size());
        var cursor = 0;
        while (result.size() < target) {
            var index = result.size() - slots.size();
            var at = Math.min(usableBlocks - 1,
                (index * usableBlocks) / Math.max(1, target - slots.size()));
            var context = tailOf(blockAt(textBlocks, at), CONTEXT_CHARS);
            var description = cursor < pool.size() ? pool.get(cursor++) : theme;
            result.add(new BodySlot(result.size() + 1, cleanDescription(description), context));
        }
        return List.copyOf(result);
    }

    /**
     * 按图位生成图片提示词：画面内容来自模型写在标记里的描述，
     * 再带上主题与「这张图前面在讲什么」，图片和上下文才对得上。
     */
    public static String buildSlotPrompt(String keyword, String style, BodySlot slot,
        String stylePrompt) {
        var theme = StringUtils.hasText(keyword) ? keyword.trim() : "主题";
        var styleText = StringUtils.hasText(style) ? style.trim() : "资讯综述";
        var look = StringUtils.hasText(stylePrompt) ? stylePrompt.trim() : DEFAULT_STYLE_PROMPT;
        var what = StringUtils.hasText(slot.description()) ? slot.description().trim()
            : "与上下文内容相符的实拍感画面";
        var context = StringUtils.hasText(slot.contextBefore())
            ? "这张图前面一段在讲：" + slot.contextBefore().trim() + "。" : "";
        return """
            为今日头条的一篇图文配图。文章主题：「%s」，风格：%s。
            %s
            画面内容：%s。
            要求：%s；横构图或方构图；画面中不要出现任何文字、字母、数字、水印与 logo；
            不要出现可识别的真实人物、品牌与商标；不要拼贴、不要分格、不要边框。"""
            .formatted(theme, styleText, context, what, look);
    }

    /** 正文按标记切开后的文本块（展示与补位都要用） */
    public static List<String> textBlocks(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        var blocks = new ArrayList<String>();
        var matcher = SLOT_PATTERN.matcher(body);
        var lastEnd = 0;
        while (matcher.find()) {
            addBlock(blocks, body.substring(lastEnd, matcher.start()));
            lastEnd = matcher.end();
        }
        addBlock(blocks, body.substring(lastEnd));
        return List.copyOf(blocks);
    }

    private static void addBlock(List<String> blocks, String raw) {
        var text = raw.strip();
        if (!text.isEmpty()) {
            blocks.add(text);
        }
    }

    private static String blockAt(List<String> blocks, int index) {
        return blocks.isEmpty() ? "" : blocks.get(Math.max(0, Math.min(index, blocks.size() - 1)));
    }

    /** 把标记从正文里去掉（纯文本展示等场景） */
    public static String stripMarkers(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        return SLOT_PATTERN.matcher(body).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String cleanDescription(String raw) {
        var text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (text.length() > 60) {
            text = text.substring(0, 60);
        }
        return text;
    }

    /** 取结尾一段做上下文摘要（取结尾是因为图位紧跟在这段后面） */
    private static String tailOf(String text, int maxChars) {
        var flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= maxChars) {
            return flat;
        }
        return flat.substring(flat.length() - maxChars);
    }

    /**
     * 文案卡片规划（AI 生图不可用时的兜底，也是「张数不够」时的补齐来源）。
     *
     * <p>第 1 张是封面卡（标题）；后面的优先用小标题当「要点」卡，
     * 小标题不够时依次用正文首句、标签、关键词补位——宁可重复主题词，
     * 也不让某张卡片空着（张数是硬要求）。</p>
     *
     * @param titles  标题候选（按顺序；可为空）
     * @param body    正文（可为空）
     * @param tags    标签（可为空）
     * @param style   写作风格（决定配色与卡片上的小字）
     * @param keyword 主题关键词
     * @param count   需要几张（未达下限时按下限补）
     */
    public static List<CardSpec> cardPlan(List<String> titles, String body, List<String> tags,
        String style, String keyword, int count) {
        var total = Math.max(MIN_IMAGES, Math.min(MAX_IMAGES, count));
        var styleKey = StringUtils.hasText(style) ? style.trim() : "主题";
        var theme = StringUtils.hasText(keyword) ? keyword.trim() : "主题";
        var firstTitle = firstNonEmpty(titles, theme);

        var result = new ArrayList<CardSpec>(total);
        result.add(new CardSpec(styleKey, styleKey, firstTitle));

        // 卡片 2..n 的内容来源：小标题 → 正文首句 → 标签 → 关键词
        var pool = new ArrayList<String>();
        for (var heading : extractHeadings(body)) {
            if (!heading.equals(firstTitle) && !pool.contains(heading)) {
                pool.add(heading);
            }
        }
        for (var fallback : List.of(firstSentence(body), String.join(" · ", tags), theme)) {
            if (StringUtils.hasText(fallback) && !fallback.equals(firstTitle)
                && !pool.contains(fallback)) {
                pool.add(fallback);
            }
        }

        var cursor = 0;
        for (var i = 1; i < total; i++) {
            String title;
            String label;
            String key;
            if (cursor < pool.size()) {
                title = pool.get(cursor++);
                label = "要点";
                key = styleKey + "-p" + i;
            } else {
                // 素材用尽：同文案换配色，保证张数与画面不重复
                title = theme;
                label = styleKey;
                key = styleKey + "-r" + i;
            }
            result.add(new CardSpec(key, label, clipCardTitle(title)));
        }
        return List.copyOf(result);
    }

    /** 卡片规格：categoryKey 只决定配色（同一 key 颜色稳定），label 是卡片左上角小字 */
    public record CardSpec(String categoryKey, String categoryLabel, String title) {
    }

    /**
     * 从正文里挑出「小标题」行。
     *
     * <p>提示词要求每层小标题单独一行、不带 Markdown 标记；这里按「短、无句尾标点、
     * 句中也没有句号」三条兜住模型的不听话——多挑几个进池子没有代价（用不完就丢）。</p>
     */
    public static List<String> extractHeadings(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        var result = new LinkedHashSet<String>();
        for (var rawLine : body.split("\\R")) {
            var line = rawLine.trim()
                .replaceFirst("^#{1,6}\\s*", "")
                .replaceFirst("^[0-9一二三四五六七八九十]+\\s*[、.．)）]\\s*", "")
                .replace("**", "")
                .trim();
            if (line.length() < 4 || line.length() > 30) {
                continue;
            }
            // 句尾标点或句中出现句号 = 正文句子，不是小标题
            if (line.matches(".*[。！？；，、：,.!?;:]$") || line.matches(".*[。！？].*")) {
                continue;
            }
            result.add(line);
        }
        return List.copyOf(result);
    }

    /** 正文首句（截到第一个句号类标点或 30 字），作为卡片素材的兜底 */
    public static String firstSentence(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        var flat = body.replaceAll("\\s+", " ").trim();
        var end = flat.length();
        for (var i = 0; i < flat.length() && i < 40; i++) {
            var ch = flat.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?') {
                end = i + 1;
                break;
            }
        }
        return clipCardTitle(flat.substring(0, Math.min(end, flat.length())));
    }

    /**
     * 按魔数识别图片类型。
     *
     * <p>图片接口有时返回 {@code b64_json}（字节在手上、没有 Content-Type），
     * 而附件上传要靠类型决定扩展名与 MIME；识别不出时按 JPEG 处理——
     * 与 {@code DigestAssetUploaderImpl} 自己的兜底口径一致。</p>
     */
    public static String mediaTypeOf(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return "image/jpeg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /** 卡片标题收敛：卡片上放不下长句，超长截断加省略号 */
    private static String clipCardTitle(String value) {
        var text = value == null ? "" : value.trim();
        if (text.length() <= MAX_CARD_TITLE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CARD_TITLE_CHARS - 1) + "…";
    }

    private static String firstNonEmpty(List<String> titles, String fallback) {
        if (titles != null) {
            for (var title : titles) {
                if (StringUtils.hasText(title)) {
                    return clipCardTitle(title.trim());
                }
            }
        }
        return clipCardTitle(fallback);
    }
}
