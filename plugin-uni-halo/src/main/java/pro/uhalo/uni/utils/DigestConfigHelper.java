package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestCategoryConfigDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 日报配置解析：设置分组 aiDigestConfig → DigestConfigDTO。
 *
 * <p>与其它 ConfigHelper 保持同样的口径：任何字段缺失/类型不符都退回默认值，
 * 不让一处配置问题把整条流水线打挂。</p>
 */
public final class DigestConfigHelper {

    private static final String GROUP_NAME = "aiDigestConfig";

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 24 * 30;
    private static final int MIN_TOP_N = 1;
    private static final int MAX_TOP_N = 50;
    private static final int MIN_WORDS = 80;
    private static final int MAX_WORDS = 2000;

    /** 单类最少篇数上限：再多就违背「精选」的意义了，宁可让站长调大精选篇数 */
    private static final int MAX_MIN_PER_CATEGORY = 10;
    /** 单类最多篇数上限（0 = 不限，不参与截断） */
    private static final int MAX_MAX_PER_CATEGORY = 50;
    /** 补抓篇数上限：一次补抓最多再评这么多篇，防止 AI 账单失控 */
    private static final int MAX_FALLBACK_SCAN_LIMIT = 100;

    private DigestConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static DigestConfigDTO defaults() {
        return DigestConfigDTO.builder().build();
    }

    public static DigestConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        return DigestConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .scheduleEnabled(node.path("scheduleEnabled").asBoolean(true))
            .scheduleTimes(text(node, "scheduleTimes", "07:00"))
            .timezone(text(node, "timezone", "Asia/Shanghai"))
            .aiApiBase(text(node, "aiApiBase", "https://api.deepseek.com/v1"))
            .aiApiKey(text(node, "aiApiKey", ""))
            .aiModel(text(node, "aiModel", "deepseek-chat"))
            .fallbackApiBase(text(node, "fallbackApiBase", ""))
            .fallbackApiKey(text(node, "fallbackApiKey", ""))
            .fallbackModel(text(node, "fallbackModel", ""))
            .aiProviders(parseProviders(node.path("aiProviders")))
            .aiPrices(parsePrices(node.path("aiPrices")))
            .priceAutoRefreshEnabled(node.path("priceAutoRefreshEnabled").asBoolean(true))
            .aiPriceFetchedAt(text(node, "aiPriceFetchedAt", ""))
            .balanceCheckEnabled(node.path("balanceCheckEnabled").asBoolean(true))
            .hours(clamp(node.path("hours").asInt(48), MIN_HOURS, MAX_HOURS))
            .topN(clamp(node.path("topN").asInt(15), MIN_TOP_N, MAX_TOP_N))
            .minPerCategory(clamp(node.path("minPerCategory").asInt(DEFAULT_MIN_PER_CATEGORY),
                0, MAX_MIN_PER_CATEGORY))
            // 0 是「不限」的合法值，不能和被 clamp 掉的负数混为一谈
            .maxPerCategory(normalizeMaxPerCategory(node.path("maxPerCategory").asInt(0)))
            .categoryFallbackEnabled(node.path("categoryFallbackEnabled").asBoolean(true))
            .categoryFallbackHours(clamp(node.path("categoryFallbackHours").asInt(168),
                MIN_HOURS, MAX_HOURS))
            .categoryFallbackScanLimit(clamp(node.path("categoryFallbackScanLimit").asInt(30),
                1, MAX_FALLBACK_SCAN_LIMIT))
            .coverFallbackMode(normalizeCoverFallbackMode(text(node, "coverFallbackMode",
                COVER_FALLBACK_PLACEHOLDER)))
            .words(clamp(node.path("words").asInt(600), MIN_WORDS, MAX_WORDS))
            .language("en".equalsIgnoreCase(text(node, "language", "zh")) ? "en" : "zh")
            .imageEnabled(node.path("imageEnabled").asBoolean(true))
            .requireImage(node.path("requireImage").asBoolean(true))
            .attachmentPolicyName(text(node, "attachmentPolicyName", ""))
            .attachmentGroupName(text(node, "attachmentGroupName", ""))
            .publishEnabled(node.path("publishEnabled").asBoolean(true))
            .publishMode(normalizePublishMode(text(node, "publishMode", "per-article")))
            .haloBaseUrl(text(node, "haloBaseUrl", ""))
            .postOwner(text(node, "postOwner", "admin"))
            .postTitleTemplate(text(node, "postTitleTemplate", "AI 日报 · {date}"))
            .postCategories(text(node, "postCategories", ""))
            // 自动打标默认开启：这是「不需要站长手工配置分类标签」的关键
            .autoCategoryEnabled(node.path("autoCategoryEnabled").asBoolean(true))
            .autoTagEnabled(node.path("autoTagEnabled").asBoolean(true))
            .autoTagMinCount(Math.max(1, node.path("autoTagMinCount").asInt(2)))
            .postTags(text(node, "postTags", ""))
            // 分类清单：repeater 提交的是对象数组；消毒在 DigestCategoryCatalog 里做，
            // 这里只负责把 JSON 读成条目（读不出来的字段留空，由目录补默认）
            .digestCategories(parseCategories(node.path("digestCategories")))
            .categoryMode(normalizeCategoryMode(text(node, "categoryMode", CATEGORY_MODE_CREATE)))
            .unknownCategoryPolicy(
                normalizeUnknownCategoryPolicy(text(node, "unknownCategoryPolicy", UNKNOWN_CATEGORY_OTHER)))
            .mermaidEnabled(node.path("mermaidEnabled").asBoolean(true))
            .footerNote(text(node, "footerNote", ""))
            .publishWaitSeconds(clamp(node.path("publishWaitSeconds").asInt(600), 60, 3600))
            .build();
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /** 分类落库方式：不存在则创建（改造前行为） */
    public static final String CATEGORY_MODE_CREATE = "create";
    /** 分类落库方式：只复用已有站点分类，匹配不到就不打分类 */
    public static final String CATEGORY_MODE_REUSE = "reuse";
    /** 分类落库方式：按清单里的「映射到已有分类名」落库（只匹配不创建） */
    public static final String CATEGORY_MODE_MAP = "map";

    /** AI 给出清单外分类时：落到清单里名为 other 的分类 */
    public static final String UNKNOWN_CATEGORY_OTHER = "other";
    /** AI 给出清单外分类时：不打分类 */
    public static final String UNKNOWN_CATEGORY_DROP = "drop";

    private static String normalizeCategoryMode(String value) {
        if (!StringUtils.hasText(value)) {
            return CATEGORY_MODE_CREATE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case CATEGORY_MODE_REUSE, "reuse-only", "existing" -> CATEGORY_MODE_REUSE;
            case CATEGORY_MODE_MAP, "mapping" -> CATEGORY_MODE_MAP;
            default -> CATEGORY_MODE_CREATE;
        };
    }

    /** 落库方式的中文名（后台面板与日志里显示用，口径与设置页的下拉项一致） */
    public static String categoryModeLabel(String mode) {
        return switch (normalizeCategoryMode(mode)) {
            case CATEGORY_MODE_REUSE -> "只复用已有分类";
            case CATEGORY_MODE_MAP -> "按清单映射";
            default -> "不存在则自动创建";
        };
    }

    /** 清单外分类处理方式的中文名 */
    public static String unknownCategoryPolicyLabel(String policy) {
        return UNKNOWN_CATEGORY_DROP.equalsIgnoreCase(policy) ? "不打分类" : "归入兜底分类";
    }

    private static String normalizeUnknownCategoryPolicy(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN_CATEGORY_OTHER;
        }
        return UNKNOWN_CATEGORY_DROP.equalsIgnoreCase(value.trim()) ? UNKNOWN_CATEGORY_DROP
            : UNKNOWN_CATEGORY_OTHER;
    }

    /**
     * 读取 repeater 提交的分类清单。
     *
     * <p>只做「读得出来就读」：字段缺失、类型不符都留空，交给
     * {@link DigestCategoryCatalog} 消毒（key 合法性、去重、补默认、截断）。</p>
     */
    static List<DigestCategoryConfigDTO> parseCategories(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var result = new ArrayList<DigestCategoryConfigDTO>();
        for (var item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            var key = item.path("key").asText("");
            var label = item.path("label").asText("");
            // 整行为空（站长点了「新增一项」但没填）直接跳过，不让空行占位
            if (!StringUtils.hasText(key) && !StringUtils.hasText(label)
                && !StringUtils.hasText(item.path("targetCategory").asText(""))) {
                continue;
            }
            result.add(new DigestCategoryConfigDTO(
                key,
                label,
                item.path("emoji").asText(""),
                item.path("desc").asText(""),
                item.path("targetCategory").asText("")));
        }
        return result;
    }

    /** 通道清单最多几条：再多也不会更快，只会让一次运行把时间花在逐条试错上 */
    public static final int MAX_AI_PROVIDERS = 10;

    /** 单价表最多几条：只该有「几个模型」，多出来的都是误操作 */
    public static final int MAX_AI_PRICES = 20;

    /**
     * 读取 repeater 提交的大模型通道清单。
     *
     * <p>消毒口径：整行空白跳过（点了「新增一项」没填）、去空格、端点去尾斜杠、
     * 通道标识补全并去重、用途按白名单过滤、开关缺省为开、超过上限的截断。</p>
     *
     * <p>重复标识不丢行而是换一个标识：站长手上有两条同标识的通道时，
     * 静默丢一条等于悄悄少了一半的故障转移能力。</p>
     */
    static List<DigestAiProviderDTO> parseProviders(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var result = new ArrayList<DigestAiProviderDTO>();
        var usedKeys = new HashSet<String>();
        for (var item : node) {
            if (item == null || !item.isObject() || result.size() >= MAX_AI_PROVIDERS) {
                continue;
            }
            var apiBase = DigestAiChannelResolver.normalizeBase(item.path("apiBase").asText(""));
            var apiKey = item.path("apiKey").asText("").trim();
            var label = item.path("label").asText("").trim();
            // 三个能区分「这一行是不是空行」的字段都空 = 空行
            // （只勾了用途没填端点与 Key 的行同样算空行：那样的通道没有任何调用价值）
            if (apiBase.isEmpty() && apiKey.isEmpty() && label.isEmpty()) {
                continue;
            }
            var key = uniqueProviderKey(item.path("key").asText(""), usedKeys, result.size() + 1);
            usedKeys.add(key);
            result.add(new DigestAiProviderDTO(
                key,
                label,
                apiBase,
                apiKey,
                // 模型名留空时先原样存着，由 DigestAiChannelResolver 按端点推断
                item.path("model").asText("").trim(),
                item.path("enabled").asBoolean(true),
                parseTasks(item.path("tasks"))));
        }
        return List.copyOf(result);
    }

    /**
     * 读取一行通道的「用途」。
     *
     * <p>刻意容忍三种形态：数组 {@code ["score","write"]}、逗号串 {@code "score,write"}、
     * 以及 {@code {"score":true}} 这种勾选表。原因很实际——设置页的表单控件给什么形态
     * 取决于控件本身，配置存储也可能被人工改过；解析层多写十行，换来的是
     * 「站长换个入口编辑一次，用途就全丢了」这类问题不会发生。</p>
     *
     * <p>未知标识直接丢弃（不猜测、不改写），去重并保持勾选顺序。</p>
     */
    static List<String> parseTasks(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        var used = new LinkedHashSet<String>();
        if (node.isArray()) {
            for (var item : node) {
                addTask(used, item.isTextual() ? item.asText("") : null, item);
            }
        } else if (node.isTextual()) {
            // 逗号串：与仓库里其它「多值文本」字段同一口味（中英文逗号、分号、空格都认）
            for (var part : node.asText("").split("[,，;；\\s]+")) {
                addTask(used, part, null);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && entry.getValue().asBoolean(false)) {
                    addTask(used, entry.getKey(), null);
                }
            });
        }
        return List.copyOf(used);
    }

    private static void addTask(Set<String> sink, String raw, JsonNode node) {
        if (raw != null) {
            DigestAiTask.fromKey(raw).ifPresent(task -> sink.add(task.getKey()));
            return;
        }
        // 数组里可能塞的是 {"key":"score"} 这种对象，能认就认
        if (node != null && node.isObject()) {
            DigestAiTask.fromKey(node.path("key").asText(""))
                .ifPresent(task -> sink.add(task.getKey()));
        }
    }

    /**
     * 读取模型单价表。
     *
     * <p>单价可以是字符串也可以是数字（表单控件给什么形状不由我们决定），
     * 非法数字在 {@link DigestAiPriceDTO} 里被当作「没填」处理——填错一位小数不该让整轮运行出问题。</p>
     */
    static List<DigestAiPriceDTO> parsePrices(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var result = new ArrayList<DigestAiPriceDTO>();
        for (var item : node) {
            if (item == null || !item.isObject() || result.size() >= MAX_AI_PRICES) {
                continue;
            }
            var model = item.path("model").asText("").trim();
            var currency = item.path("currency").asText("").trim();
            if (model.isEmpty() && currency.isEmpty()) {
                continue;
            }
            result.add(DigestAiPriceDTO.of(model, currency,
                item.path("cacheHit").asText("").trim(),
                item.path("cacheMiss").asText("").trim(),
                item.path("output").asText("").trim(),
                item.path("offPeakRatio").asText("").trim()));
        }
        return List.copyOf(result);
    }

    /**
     * 通道标识：缺失/重复都换成 {@code p<序号>}（序号从 1 开始，撞了就往后找）。
     *
     * <p>公开给「大模型通道」保存逻辑复用：标识是「测试连通性按标识回查已存 Key」的凭据，
     * 两处各生成一套规则的话，页面保存完就可能出现「测试按钮找不到这条通道」。</p>
     */
    public static String uniqueProviderKey(String raw, Set<String> used, int index) {
        var candidate = raw == null ? "" : raw.trim();
        if (!candidate.isEmpty() && !used.contains(candidate)) {
            return candidate;
        }
        var next = Math.max(1, index);
        while (used.contains("p" + next)) {
            next++;
        }
        return "p" + next;
    }

    /** 合法的发布方式，非法值一律按逐篇处理 */
    public static final String PUBLISH_MODE_PER_ARTICLE = "per-article";

    public static final String PUBLISH_MODE_DIGEST = "digest";
    public static final String PUBLISH_MODE_BOTH = "both";

    /** 每类最少篇数的默认值：1，即「每个分类每天至少一篇」 */
    public static final int DEFAULT_MIN_PER_CATEGORY = 1;

    /** 配不到图时：绘制标题卡片当封面 */
    public static final String COVER_FALLBACK_PLACEHOLDER = "placeholder";
    /** 配不到图时：允许无封面发布 */
    public static final String COVER_FALLBACK_NO_COVER = "no-cover";
    /** 配不到图时：跳过这一篇 */
    public static final String COVER_FALLBACK_SKIP = "skip";

    private static String normalizeCoverFallbackMode(String value) {
        if (!StringUtils.hasText(value)) {
            return COVER_FALLBACK_PLACEHOLDER;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case COVER_FALLBACK_NO_COVER, "none", "allow" -> COVER_FALLBACK_NO_COVER;
            case COVER_FALLBACK_SKIP, "drop" -> COVER_FALLBACK_SKIP;
            default -> COVER_FALLBACK_PLACEHOLDER;
        };
    }

    /** 单类篇数上限：0（及负数）= 不限，其余截到 50 */
    private static int normalizeMaxPerCategory(int value) {
        return value <= 0 ? 0 : Math.min(value, MAX_MAX_PER_CATEGORY);
    }

    /** 配图兜底方式的中文名（后台面板与日志里显示用） */
    public static String coverFallbackLabel(String mode) {
        return switch (normalizeCoverFallbackMode(mode)) {
            case COVER_FALLBACK_NO_COVER -> "允许无封面";
            case COVER_FALLBACK_SKIP -> "跳过该篇";
            default -> "生成标题卡片";
        };
    }

    private static String normalizePublishMode(String value) {
        if (!StringUtils.hasText(value)) {
            return PUBLISH_MODE_PER_ARTICLE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "digest", "merged", "single", "combined" -> PUBLISH_MODE_DIGEST;
            case "both", "all" -> PUBLISH_MODE_BOTH;
            default -> PUBLISH_MODE_PER_ARTICLE;
        };
    }

    /** 把「逗号分隔的名称」配置解析成列表（分类、标签共用） */
    public static List<String> splitNames(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var part : value.split("[,，;；]")) {
            if (StringUtils.hasText(part)) {
                names.add(part.trim());
            }
        }
        return names;
    }

    /** 时区解析：填错时退回 Asia/Shanghai，不让一个错别字让定时器整体失效 */
    public static ZoneId zoneOf(String timezone) {        if (StringUtils.hasText(timezone)) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (Exception ignored) {
                // 落到默认时区
            }
        }
        return ZoneId.of(DEFAULT_TIMEZONE);
    }

    /**
     * 解析执行时刻列表。
     *
     * <p>支持 {@code "07:00"}、{@code "07:00,19:00"} 以及 {@code "7:00"} 这类写法，
     * 解析不出来的项直接丢掉；全都不合法时退回默认的 07:00。</p>
     */
    public static List<LocalTime> parseScheduleTimes(String scheduleTimes) {
        var result = new ArrayList<LocalTime>();
        if (StringUtils.hasText(scheduleTimes)) {
            for (var part : scheduleTimes.split("[,，;；\\s]+")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                var normalized = part.trim();
                var matcher = TIME_PATTERN.matcher(normalized);
                if (!matcher.matches()) {
                    continue;
                }
                var hour = Integer.parseInt(matcher.group(1));
                var minute = Integer.parseInt(matcher.group(2));
                if (hour > 23 || minute > 59) {
                    continue;
                }
                var time = LocalTime.of(hour, minute);
                if (!result.contains(time)) {
                    result.add(time);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(LocalTime.of(7, 0));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }
}
