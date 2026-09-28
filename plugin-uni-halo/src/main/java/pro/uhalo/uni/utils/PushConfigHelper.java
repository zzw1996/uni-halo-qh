package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.PushConfigDTO;

/**
 * 推送配置解析（设置分组 {@code pushConfig}）。
 *
 * <p>容错原则与其它配置解析一致：字段缺失或非法时回落默认值，不抛异常。
 * 间隔与上限必须收敛到安全区间——它们直接决定「一天最多点几次微信接口」，
 * 配错（例如 1 分钟一次）会变成对微信侧的持续调用。</p>
 */
public final class PushConfigHelper {

    public static final String GROUP_NAME = "pushConfig";

    /**
     * 默认模板 ID：站长已经在 MP 后台选好的「内容更新通知」模板。
     *
     * <p>它属于该站点的小程序，作为默认值只是为了省掉一次粘贴；换成别的模板直接覆盖即可。</p>
     */
    public static final String DEFAULT_TEMPLATE_ID = "wTqRnFopbwyBQU_8gJvPdTpOLzS2h9xgikQxcSuUf4k";

    /** 消息类型（与模板对应）：新文章提醒 / AI 简报就绪提醒 */
    public static final String KIND_NEW_POST = "newPost";
    public static final String KIND_AI_DIGEST = "aiDigest";
    public static final java.util.List<String> KINDS = java.util.List.of(KIND_NEW_POST, KIND_AI_DIGEST);

    private static final int MIN_INTERVAL_MINUTES = 1;
    private static final int MAX_INTERVAL_MINUTES = 120;
    private static final int MAX_ITEMS = 5;
    private static final int MIN_DAILY_LIMIT = 1;
    private static final int MAX_DAILY_LIMIT = 100000;
    private static final int MAX_LOOKBACK_HOURS = 168;

    private PushConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /**
     * 默认值：<b>总开关关闭</b>。
     *
     * <p>与其它付费/外部能力一致——推送会真的给用户发微信消息，功能没配好之前
     * 绝不能因为「升级完就自动开始推」而打扰用户。</p>
     */
    public static PushConfigDTO defaults() {
        return PushConfigDTO.builder()
            .enabled(false)
            .templateId(DEFAULT_TEMPLATE_ID)
            .aiDigestTemplateId("")
            .newPostEnabled(true)
            .aiDigestEnabled(true)
            .checkIntervalMinutes(5)
            .maxItemsPerMessage(3)
            .dailySendLimit(500)
            .lookbackHours(24)
            .miniprogramState("formal")
            .build();
    }

    public static PushConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var fallback = defaults();
        return PushConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .templateId(text(node.path("templateId"), fallback.getTemplateId()))
            .aiDigestTemplateId(text(node.path("aiDigestTemplateId"), ""))
            .newPostEnabled(node.path("newPostEnabled").asBoolean(true))
            .aiDigestEnabled(node.path("aiDigestEnabled").asBoolean(true))
            .checkIntervalMinutes(clamp(
                node.path("checkIntervalMinutes").asInt(fallback.getCheckIntervalMinutes()),
                MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
            .maxItemsPerMessage(clamp(
                node.path("maxItemsPerMessage").asInt(fallback.getMaxItemsPerMessage()),
                1, MAX_ITEMS))
            .dailySendLimit(clamp(node.path("dailySendLimit").asInt(fallback.getDailySendLimit()),
                MIN_DAILY_LIMIT, MAX_DAILY_LIMIT))
            .lookbackHours(clamp(node.path("lookbackHours").asInt(fallback.getLookbackHours()),
                1, MAX_LOOKBACK_HOURS))
            .miniprogramState(normalizeState(text(node.path("miniprogramState"),
                fallback.getMiniprogramState())))
            .build();
    }

    /** 该类型的模板 ID（未配置返回空串，调用方据此跳过而不是去撞 40037） */
    public static String templateOf(PushConfigDTO config, String kind) {
        if (config == null) {
            return "";
        }
        return KIND_AI_DIGEST.equals(kind)
            ? config.resolvedAiDigestTemplateId() : config.getTemplateId().trim();
    }

    /** 该类型是否开启（总开关 + 分开关 + 模板已配置，三者缺一不可） */
    public static boolean kindEnabled(PushConfigDTO config, String kind) {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        var enabled = KIND_AI_DIGEST.equals(kind) ? config.isAiDigestEnabled()
            : config.isNewPostEnabled();
        return enabled && !templateOf(config, kind).isEmpty();
    }

    /** 归一化消息类型：认不出来返回空串（不猜） */
    public static String normalizeKind(String kind) {
        if (kind == null) {
            return "";
        }
        var value = kind.trim();
        return KINDS.contains(value) ? value : "";
    }

    /** miniprogram_state 只认这三个官方值，其余一律回落 formal */
    private static String normalizeState(String value) {
        if (value == null) {
            return "formal";
        }
        var trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (trimmed) {
            case "trial", "developer" -> trimmed;
            default -> "formal";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String text(JsonNode node, String fallback) {
        var value = node == null ? null : node.asText(null);
        if (value == null) {
            return fallback;
        }
        var trimmed = value.trim();
        // 留空 = 清除模板（而不是回落默认）：站长清空字段的意图是「先不推这个」
        return trimmed;
    }
}
