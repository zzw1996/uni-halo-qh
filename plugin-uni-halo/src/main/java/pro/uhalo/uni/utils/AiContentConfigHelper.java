package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.AiContentConfigDTO;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 定制内容配置解析（设置分组 {@code aiContentConfig}）。
 *
 * <p>容错原则与其它配置解析一致：字段缺失或非法时回落默认值，不抛异常。
 * 上限值全部收敛到安全区间——它们是<b>匿名接口</b>的入参上限，配错等于把接口敞开。</p>
 */
public final class AiContentConfigHelper {

    public static final String GROUP_NAME = "aiContentConfig";

    /**
     * 「永久买断」的表示方式：一个远期时间。
     *
     * <p>与会员卡共用 {@link MemberConfigHelper#isActive} 判定，因此不需要额外的布尔字段；
     * 展示时用 {@link #isPermanent} 判断并显示成「永久」，而不是把这个日期直接摆给用户看。</p>
     */
    public static final Instant PERMANENT_EXPIRE_AT = Instant.parse("9999-12-31T15:59:59Z");

    /** 超过这个时间就按「永久」理解（留给续费叠加天数的余量，不必卡在 9999 年） */
    private static final Instant PERMANENT_THRESHOLD = Instant.parse("9000-01-01T00:00:00Z");

    /** 上限的收敛区间：这些值同时是匿名接口的入参上限，配错等于把接口敞开 */
    private static final int MAX_DIMENSION = 30;
    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_ITEMS = 30;
    private static final int MAX_WINDOW_DAYS = 60;
    private static final int MAX_INTRO_WORDS = 800;
    private static final int MAX_TOPICS = 5;
    private static final int MAX_PRICE = 100000;
    private static final int MAX_POINTS_DAYS = 3650;
    private static final int MAX_GENERATE_WORDS = 1500;
    /** 实时素材时间窗的收敛区间：下限保证抓得到内容，上限保证还叫「最新」 */
    private static final int MIN_LIVE_HOURS = 6;
    private static final int MAX_LIVE_HOURS = 168;
    /** 生成次数相关上限：它们同样是成本阀门，配错等于把 AI 额度敞开 */
    private static final int MAX_SEARCH_HOURS = 720;
    private static final int MAX_SEARCH_ITEMS = 120;
    private static final int MAX_CREDITS_PER_DAY = 50;
    private static final int MAX_CREDITS_PER_GRANT = 1000;
    private static final int MAX_DAILY_GENERATIONS = 50;

    /** 首页运营位卡与手输主题的默认文案 */
    private static final String DEFAULT_HOME_CARD_TITLE = "AI 定制内容";
    private static final String DEFAULT_HOME_CARD_DESC = "每天一份为你定制的技术简报";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EXPIRE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AiContentConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /**
     * 默认值：功能关闭。
     *
     * <p>与会员卡同一个理由——这是付费商品，误开会让站长在没配商品的情况下露出一个买不了的入口。</p>
     */
    public static AiContentConfigDTO defaults() {
        return AiContentConfigDTO.builder()
            .enabled(false)
            .aiIntroEnabled(true)
            .maxCategories(5)
            .maxKeywords(10)
            .maxItems(10)
            .windowDays(7)
            .liveHours(48)
            .creditsPerDay(2)
            .maxCreditsPerGrant(100)
            .dailyGenerationLimit(3)
            .keywordSearchEnabled(true)
            .keywordSearchProviders(List.of("bing", "google"))
            .keywordSearchHours(72)
            .keywordSearchMaxItems(40)
            .keywordSearchCoverEnabled(true)
            .outroWords(120)
            .introWords(200)
            .maxTopics(3)
            .pointsPrice(0)
            .pointsDays(30)
            .presetTopics("")
            .homeCardEnabled(true)
            .homeCardTitle(DEFAULT_HOME_CARD_TITLE)
            .homeCardDesc(DEFAULT_HOME_CARD_DESC)
            .fallbackGenerateEnabled(true)
            .memberFree(true)
            .benefitNote("")
            .build();
    }

    public static AiContentConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var fallback = defaults();
        return AiContentConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .aiIntroEnabled(node.path("aiIntroEnabled").asBoolean(true))
            .maxCategories(clamp(node.path("maxCategories").asInt(fallback.getMaxCategories()),
                1, MAX_DIMENSION))
            // 关键词允许配 0：站长可以把这一维度整个关掉（用户就看不到关键词输入框）
            .maxKeywords(clamp(node.path("maxKeywords").asInt(fallback.getMaxKeywords()),
                0, MAX_KEYWORDS))
            .maxItems(clamp(node.path("maxItems").asInt(fallback.getMaxItems()), 1, MAX_ITEMS))
            // 兜底窗口：只在实时抓取拿不到素材时才会用到
            .windowDays(clamp(node.path("windowDays").asInt(fallback.getWindowDays()),
                1, MAX_WINDOW_DAYS))
            // 实时素材时间窗：太小会经常抓不到内容，太大就不再是「最新」
            .liveHours(clamp(node.path("liveHours").asInt(fallback.getLiveHours()),
                MIN_LIVE_HOURS, MAX_LIVE_HOURS))
            // 生成次数：每天至少 1 次（0 会让付费用户一次都用不了）
            .creditsPerDay(clamp(node.path("creditsPerDay").asInt(fallback.getCreditsPerDay()),
                1, MAX_CREDITS_PER_DAY))
            .maxCreditsPerGrant(clamp(
                node.path("maxCreditsPerGrant").asInt(fallback.getMaxCreditsPerGrant()),
                1, MAX_CREDITS_PER_GRANT))
            // 每日上限允许 0：站长可以只按总额度管，不限每天
            .dailyGenerationLimit(clamp(
                node.path("dailyGenerationLimit").asInt(fallback.getDailyGenerationLimit()),
                0, MAX_DAILY_GENERATIONS))
            // 关键词联网检索：默认开（不开的话「邓紫棋」这类词在科技源里必然命不中）
            .keywordSearchEnabled(node.path("keywordSearchEnabled").asBoolean(true))
            .keywordSearchProviders(
                searchProviders(node.path("keywordSearchProviders").asText("")))
            .keywordSearchHours(clamp(
                node.path("keywordSearchHours").asInt(fallback.getKeywordSearchHours()),
                1, MAX_SEARCH_HOURS))
            .keywordSearchMaxItems(clamp(
                node.path("keywordSearchMaxItems").asInt(fallback.getKeywordSearchMaxItems()),
                1, MAX_SEARCH_ITEMS))
            .keywordSearchCoverEnabled(node.path("keywordSearchCoverEnabled").asBoolean(true))
            // 结尾允许 0：站长可以只要导语
            .outroWords(clamp(node.path("outroWords").asInt(fallback.getOutroWords()),
                0, MAX_INTRO_WORDS))
            .introWords(clamp(node.path("introWords").asInt(fallback.getIntroWords()),
                50, MAX_INTRO_WORDS))
            // 手输主题允许配 0：关掉 = 用户只能用高级筛选，不能自由输入
            .maxTopics(clamp(node.path("maxTopics").asInt(fallback.getMaxTopics()),
                0, MAX_TOPICS))
            .pointsPrice(clamp(node.path("pointsPrice").asInt(fallback.getPointsPrice()),
                0, MAX_PRICE))
            .pointsDays(clamp(node.path("pointsDays").asInt(fallback.getPointsDays()),
                1, MAX_POINTS_DAYS))
            .presetTopics(text(node.path("presetTopics"), ""))
            .homeCardEnabled(node.path("homeCardEnabled").asBoolean(true))
            .homeCardTitle(text(node.path("homeCardTitle"), DEFAULT_HOME_CARD_TITLE))
            .homeCardDesc(text(node.path("homeCardDesc"), DEFAULT_HOME_CARD_DESC))
            .fallbackGenerateEnabled(node.path("fallbackGenerateEnabled").asBoolean(true))
            .memberFree(node.path("memberFree").asBoolean(true))
            .benefitNote(text(node.path("benefitNote"), ""))
            .build();
    }

    /**
     * 一次购买/兑换赠送的生成次数 = 天数 × 每天次数，上限 {@code maxCreditsPerGrant}。
     *
     * <p>永久买断（days ≤ 0）直接按上限给：按 36500 天算等于无限，与「成本阀门」的初衷相反。</p>
     */
    public static int creditsForDays(int days, AiContentConfigDTO config) {
        if (config == null) {
            return 0;
        }
        var perDay = config.getCreditsPerDay();
        if (days <= 0) {
            return config.getMaxCreditsPerGrant();
        }
        var wanted = (long) days * perDay;
        return (int) Math.max(1, Math.min(config.getMaxCreditsPerGrant(), wanted));
    }

    /**
     * 检索源解析：逗号/顿号/空格分隔，只认 bing / google / baidu。
     *
     * <p>不认识的名字直接丢掉（宁可少一个源，也别把配置错误变成抓取请求打到一个乱七八糟的地址上）。
     * 留空 = 用默认的两个源。</p>
     */
    public static List<String> searchProviders(String raw) {
        var defaults = List.of("bing", "google");
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        var providers = new java.util.LinkedHashSet<String>();
        for (var piece : raw.split("[,，、\s]+")) {
            var name = piece.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("bing") || name.equals("google") || name.equals("baidu")) {
                providers.add(name);
            }
        }
        return providers.isEmpty() ? defaults : List.copyOf(providers);
    }

    /** 是否「永久」（远期时间） */
    public static boolean isPermanent(Instant expireAt) {
        return expireAt != null && expireAt.isAfter(PERMANENT_THRESHOLD);
    }

    /** 权益是否仍在有效期内（含永久）；判定口径只此一处 */
    public static boolean isActive(Instant expireAt) {
        return MemberConfigHelper.isActive(expireAt);
    }

    /**
     * 叠加天数：续费在<b>当前到期时间</b>上叠加，而不是从当下重新计时。
     *
     * <p>与会员卡同一口径——否则用户在有效期内续费会白白损失剩余天数。
     * 已经是永久时不再变化（多买不会拿到比永久更多的东西，发货侧会提示）。</p>
     *
     * @param days 购买天数；0 或负数表示永久买断
     */
    public static Instant extend(Instant current, int days) {
        if (isPermanent(current)) {
            return current;
        }
        if (days <= 0) {
            return PERMANENT_EXPIRE_AT;
        }
        var base = isActive(current) ? current : Instant.now();
        return base.plusSeconds(days * 86400L);
    }

    /** 剩余天数（不足一天按一天计）；永久返回 0（前端按 permanent 展示） */
    public static int daysRemaining(Instant expireAt) {
        if (isPermanent(expireAt)) {
            return 0;
        }
        return MemberConfigHelper.daysRemaining(expireAt);
    }

    /** 到期时间展示串；未开通返回 null，永久返回「永久」 */
    public static String formatExpireAt(Instant expireAt) {
        if (expireAt == null) {
            return null;
        }
        return isPermanent(expireAt) ? "永久"
            : expireAt.atZone(ZONE).format(EXPIRE_FMT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /** 预设定制主题选项：按逗号/中文逗号/换行拆分（前端点击即加入自己的定制主题） */
    public static java.util.List<String> parseTopics(String presetTopics) {
        if (presetTopics == null || presetTopics.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(presetTopics.split("[,，;；\\n]"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .limit(30)
            .toList();
    }

    private static String text(JsonNode node, String fallback) {
        var value = node == null ? null : node.asText(null);
        return value == null ? fallback : value.trim();
    }
}
