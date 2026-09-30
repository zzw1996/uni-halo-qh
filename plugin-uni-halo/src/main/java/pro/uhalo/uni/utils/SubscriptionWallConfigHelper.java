package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.SubscriptionWallConfigDTO;

/**
 * 订阅墙配置解析：设置分组 {@code subscriptionWallConfig} → {@link SubscriptionWallConfigDTO}。
 *
 * <p>与其它 ConfigHelper 同一口径：字段缺失或非法退回默认值，不让一处配置问题
 * 影响文章详情链路。</p>
 */
public final class SubscriptionWallConfigHelper {

    private static final String GROUP_NAME = "subscriptionWallConfig";

    private SubscriptionWallConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /** enabled 默认 false：未配置 = 不拦，行为与「功能不存在」一致 */
    public static SubscriptionWallConfigDTO defaults() {
        return SubscriptionWallConfigDTO.builder().build();
    }

    public static SubscriptionWallConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        return SubscriptionWallConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .gateGuests(node.path("gateGuests").asBoolean(true))
            .memberFree(node.path("memberFree").asBoolean(true))
            .freeCategories(node.path("freeCategories").asText(""))
            .build();
    }
}
