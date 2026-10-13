package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestCategoryConfigDTO;
import pro.uhalo.uni.dto.MpConfigDTO;

import java.util.List;
import java.util.Locale;

/**
 * 微信公众号配置解析：设置分组 {@code mpConfig} → {@link MpConfigDTO}。
 *
 * <p>与其它 ConfigHelper 同一口径：任何字段缺失/类型不符都退回默认值，
 * 不让一处配置问题把整条链路打挂。</p>
 */
public final class MpConfigHelper {

    private static final String GROUP_NAME = "mpConfig";

    /** 推送落点：只进草稿箱（默认；个人订阅号唯一可靠的程序化路径） */
    public static final String DELIVERY_DRAFT = "draft";
    /** 推送落点：草稿 + 自动发布（freepublish，不推粉丝、不占群发次数；仅企业主体已认证可用） */
    public static final String DELIVERY_PUBLISH = "publish";
    /** 推送落点：草稿 + 群发（会占用群发次数；仅企业主体已认证可用） */
    public static final String DELIVERY_MASS = "mass";

    /** 阅读原文：指向站点文章 */
    public static final String SOURCE_SITE = "site";
    /** 阅读原文：指向固定链接 */
    public static final String SOURCE_FIXED = "fixed";
    /** 阅读原文：不设置 */
    public static final String SOURCE_NONE = "none";

    /** 方向清单上限：再多会让 prompt 太长，也不利于账号定位 */
    public static final int MAX_DIRECTIONS = 6;

    /** 文章粒度：每篇精选各自一篇（默认，推送时逐篇独立成文） */
    public static final String GRANULARITY_ARTICLE = "article";
    /** 文章粒度：每个方向一篇（把该方向精选聚合成一篇） */
    public static final String GRANULARITY_DIRECTION = "direction";

    /** 每个方向每期精选篇数上限 */
    private static final int MAX_PER_DIRECTION_TOP_N = 6;
    private static final int MIN_PER_DIRECTION_TOP_N = 1;

    /** 摘要目标字数：官方上限 120，这里给最小值 40（太短没有信息量） */
    public static final int MIN_DIGEST_WORDS = 40;
    public static final int MAX_DIGEST_WORDS = 120;
    public static final int DEFAULT_DIGEST_WORDS = 60;

    /**
     * 方向清单默认为空时的内置三项：AI 发展 / AI 安全 / Vibe Coding。
     *
     * <p>用户最初的定位就是这三个方向，这里当作「清单留空 = 内置三项」，
     * 与日报分类清单留空回落内置六类同一口径。</p>
     */
    private static final List<DigestCategoryConfigDTO> BUILT_IN_DIRECTIONS = List.of(
        new DigestCategoryConfigDTO("ai-dev", "AI 发展", "🤖",
            "大模型新进展、AI 产品与行业动态、重要论文与能力突破", ""),
        new DigestCategoryConfigDTO("security", "AI 安全", "🔒",
            "AI 安全与对齐、提示词注入、模型越狱、供应链与隐私风险、安全事件", ""),
        new DigestCategoryConfigDTO("vibe-coding", "Vibe Coding", "⚡",
            "AI 辅助编程、Vibe Coding 实践、编码 Agent 与工具链、开发者工作流变化", ""));

    /** 其余内容不参与公众号成文的兜底分类（AI 只能从方向清单选，越界内容落到它、不发布） */
    public static final String FALLBACK_KEY = DigestCategoryCatalog.FALLBACK_KEY;

    public static final String FALLBACK_LABEL = "其他";

    private MpConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static MpConfigDTO defaults() {
        return MpConfigDTO.builder()
            .enabled(false)
            .appId("")
            .appSecret("")
            .author("小编")
            .needOpenComment(false)
            .onlyFansCanComment(false)
            .deliveryMode(DELIVERY_DRAFT)
            .contentSourceMode(SOURCE_SITE)
            .contentSourceUrl("")
            .uploadImages(true)
            .scheduleEnabled(false)
            .scheduleTimes("08:00")
            .timezone("Asia/Shanghai")
            .directions(List.of())
            .perDirectionTopN(3)
            .contentGranularity(GRANULARITY_ARTICLE)
            .directionFallbackEnabled(true)
            .hours(48)
            .words(500)
            .digestWords(DEFAULT_DIGEST_WORDS)
            .titleTemplate("{direction}日报 · {date}")
            .postToSite(false)
            .postOwner("admin")
            .postCategories("")
            .build();
    }

    public static MpConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        return MpConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .appId(text(node, "appId", ""))
            .appSecret(text(node, "appSecret", ""))
            .author(text(node, "author", "小编"))
            .needOpenComment(node.path("needOpenComment").asBoolean(false))
            .onlyFansCanComment(node.path("onlyFansCanComment").asBoolean(false))
            .deliveryMode(normalizeDeliveryMode(text(node, "deliveryMode", DELIVERY_DRAFT)))
            .contentSourceMode(normalizeSourceMode(text(node, "contentSourceMode", SOURCE_SITE)))
            .contentSourceUrl(text(node, "contentSourceUrl", ""))
            .uploadImages(node.path("uploadImages").asBoolean(true))
            // 定时生成默认关闭：站长明确开启后才会自动跑（且只在时刻窗口内，错过不补跑）
            .scheduleEnabled(node.path("scheduleEnabled").asBoolean(false))
            .scheduleTimes(text(node, "scheduleTimes", "08:00"))
            .timezone(text(node, "timezone", "Asia/Shanghai"))
            .directions(parseDirections(node.path("directions")))
            .perDirectionTopN(clamp(node.path("perDirectionTopN").asInt(3),
                MIN_PER_DIRECTION_TOP_N, MAX_PER_DIRECTION_TOP_N))
            .contentGranularity(normalizeGranularity(
                text(node, "contentGranularity", GRANULARITY_ARTICLE)))
            .directionFallbackEnabled(node.path("directionFallbackEnabled").asBoolean(true))
            .hours(clamp(node.path("hours").asInt(48), 1, 24 * 30))
            .words(clamp(node.path("words").asInt(500), 150, 1200))
            .digestWords(clamp(node.path("digestWords").asInt(DEFAULT_DIGEST_WORDS),
                MIN_DIGEST_WORDS, MAX_DIGEST_WORDS))
            .titleTemplate(text(node, "titleTemplate", "{direction}日报 · {date}"))
            .postToSite(node.path("postToSite").asBoolean(false))
            .postOwner(text(node, "postOwner", "admin"))
            .postCategories(text(node, "postCategories", ""))
            .build();
    }

    /**
     * 方向清单：复用日报分类清单的解析与消毒（同一份 key/label/emoji/desc 模型），
     * 上限比日报更小（{@value #MAX_DIRECTIONS}）；清单留空 = 内置三项。
     */
    static List<DigestCategoryConfigDTO> parseDirections(JsonNode node) {
        var parsed = DigestConfigHelper.parseCategories(node);
        var sanitized = DigestCategoryCatalog.sanitize(parsed);
        if (sanitized.isEmpty()) {
            return List.of();
        }
        return sanitized.size() <= MAX_DIRECTIONS
            ? sanitized : sanitized.subList(0, MAX_DIRECTIONS);
    }

    /**
     * 有效方向目录：站长配置的清单 + 自动追加的兜底分类「其他」。
     *
     * <p>兜底分类必须存在，否则 AI 遇到与三个方向都不沾边的文章时会被迫硬塞进某个方向
     * （prompt 里限定只能从清单选）；有了它，越界内容落到「其他」、不参与公众号成文。</p>
     */
    public static List<DigestCategoryConfigDTO> effectiveDirections(MpConfigDTO config) {
        var configured = config == null || config.getDirections() == null
            ? List.<DigestCategoryConfigDTO>of() : config.getDirections();
        var base = configured.isEmpty() ? BUILT_IN_DIRECTIONS : configured;
        var result = new java.util.ArrayList<DigestCategoryConfigDTO>(base);
        var hasFallback = base.stream().anyMatch(item -> FALLBACK_KEY.equals(item.key()));
        if (!hasFallback) {
            result.add(new DigestCategoryConfigDTO(FALLBACK_KEY, FALLBACK_LABEL, "📝",
                "与上面各方向都不相关的内容（不会生成公众号文章）", ""));
        }
        return List.copyOf(result);
    }

    /** 真正会成文的方向（去掉兜底分类） */
    public static List<DigestCategoryConfigDTO> publishingDirections(MpConfigDTO config) {
        return effectiveDirections(config).stream()
            .filter(item -> !FALLBACK_KEY.equals(item.key()))
            .toList();
    }

    /** 方向目录（供 AI 提示词与渲染共用） */
    public static DigestCategoryCatalog directionCatalog(MpConfigDTO config) {
        return DigestCategoryCatalog.from(effectiveDirections(config));
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String normalizeGranularity(String value) {
        if (!StringUtils.hasText(value)) {
            return GRANULARITY_ARTICLE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case GRANULARITY_DIRECTION, "per-direction", "merged" -> GRANULARITY_DIRECTION;
            default -> GRANULARITY_ARTICLE;
        };
    }

    /** 文章粒度的中文名（后台面板与日志里显示用） */
    public static String granularityLabel(String granularity) {
        return GRANULARITY_DIRECTION.equals(normalizeGranularity(granularity))
            ? "每个方向一篇（聚合）" : "每篇精选各一篇";
    }

    private static String normalizeDeliveryMode(String value) {
        if (!StringUtils.hasText(value)) {
            return DELIVERY_DRAFT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case DELIVERY_PUBLISH, "freepublish", "auto-publish" -> DELIVERY_PUBLISH;
            case DELIVERY_MASS, "sendall", "broadcast" -> DELIVERY_MASS;
            default -> DELIVERY_DRAFT;
        };
    }

    /** 推送落点的中文名（后台面板与日志里显示用） */
    public static String deliveryModeLabel(String mode) {
        return switch (normalizeDeliveryMode(mode)) {
            case DELIVERY_PUBLISH -> "建草稿后自动发布（需企业主体已认证）";
            case DELIVERY_MASS -> "建草稿后群发推送粉丝（需企业主体已认证，占群发次数）";
            default -> "只进草稿箱（推荐）";
        };
    }

    private static String normalizeSourceMode(String value) {
        if (!StringUtils.hasText(value)) {
            return SOURCE_SITE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case SOURCE_FIXED, "url" -> SOURCE_FIXED;
            case SOURCE_NONE, "off", "empty" -> SOURCE_NONE;
            default -> SOURCE_SITE;
        };
    }

    public static String sourceModeLabel(String mode) {
        return switch (normalizeSourceMode(mode)) {
            case SOURCE_FIXED -> "固定链接";
            case SOURCE_NONE -> "不设置";
            default -> "站点文章链接";
        };
    }

    /** 执行时刻解析：与日报同一套（填错退回 07:00 的行为在日报侧，这里给默认 08:00 兜底） */
    public static List<java.time.LocalTime> parseScheduleTimes(String scheduleTimes) {
        var times = DigestConfigHelper.parseScheduleTimes(
            StringUtils.hasText(scheduleTimes) ? scheduleTimes : "08:00");
        if (times.isEmpty()) {
            return List.of(java.time.LocalTime.of(8, 0));
        }
        return times;
    }

    public static java.time.ZoneId zoneOf(String timezone) {
        return DigestConfigHelper.zoneOf(timezone);
    }
}
