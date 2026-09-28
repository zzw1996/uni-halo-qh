package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.SubscriptionConfigDTO;

/**
 * 分类订阅额度配置解析：设置分组 {@code subscriptionConfig} → {@link SubscriptionConfigDTO}。
 *
 * <p>与其它 ConfigHelper 同一口径：任何字段缺失或越界都退回默认值并收敛到安全区间，
 * 不让一处配置问题把订阅链路打挂——这条链路连着首页与消息推送。</p>
 */
public final class SubscriptionConfigHelper {

    private static final String GROUP_NAME = "subscriptionConfig";

    /** 上限收敛：额度太大既没意义（站点分类没那么多）也会让首页发太多请求 */
    private static final int MAX_QUOTA_CEILING = 200;

    private SubscriptionConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static SubscriptionConfigDTO defaults() {
        return SubscriptionConfigDTO.builder().build();
    }

    public static SubscriptionConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        return SubscriptionConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .freeQuota(clamp(node.path("freeQuota").asInt(5), 1, MAX_QUOTA_CEILING))
            .quotaPrice(clamp(node.path("quotaPrice").asInt(50), 1, 100000))
            .maxQuota(clamp(node.path("maxQuota").asInt(10), 0, MAX_QUOTA_CEILING))
            .memberMaxQuota(clamp(node.path("memberMaxQuota").asInt(30), 1, MAX_QUOTA_CEILING))
            .homeFeedPerCategory(clamp(node.path("homeFeedPerCategory").asInt(20), 5, 100))
            .hotCategoryCount(clamp(node.path("hotCategoryCount").asInt(3), 1, 10))
            .homeFeedCacheSeconds(clamp(node.path("homeFeedCacheSeconds").asInt(60), 0, 3600))
            .build();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
