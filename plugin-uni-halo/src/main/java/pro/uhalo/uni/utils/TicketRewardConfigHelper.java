package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.TicketRewardConfigDTO;

/**
 * 阅读券免费获取规则解析：来自设置分组 {@code ticketConfig}。
 *
 * <p>容错原则与其它配置解析一致：字段缺失或非法时回落到默认值，不抛异常。
 * 本配置在签到、领取新人礼、兑换、发货等多条路径上被读取，配置写错不应该
 * 让这些链路直接失败。</p>
 */
public final class TicketRewardConfigHelper {

    public static final String GROUP_NAME = "ticketConfig";

    /** 内置券兑换项的默认定价（积分） */
    public static final int DEFAULT_EXCHANGE_POINTS = 150;

    /** 连签门槛默认值：7 天（一周一次，力度可控） */
    public static final int DEFAULT_STREAK_DAYS = 7;

    /** 连签奖励默认张数 */
    public static final int DEFAULT_STREAK_TICKETS = 1;

    private TicketRewardConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /**
     * 默认值取向：签到不送券（日活用户每天白拿券会直接吃掉付费转化），
     * 新人送 1 张（保证「免费的第一次」），首购送 1 张（降低复购门槛），
     * 连签 7 天送 2 张（养成钩子，按周结算所以力度可控）。
     */
    public static TicketRewardConfigDTO defaults() {
        return TicketRewardConfigDTO.builder()
            .signInTickets(0)
            .streakDays(DEFAULT_STREAK_DAYS)
            .streakTickets(DEFAULT_STREAK_TICKETS)
            .newUserTickets(1)
            .firstPurchaseTickets(1)
            .exchangeTickets(1)
            .exchangePoints(DEFAULT_EXCHANGE_POINTS)
            .build();
    }

    public static TicketRewardConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var defaults = defaults();
        return TicketRewardConfigDTO.builder()
            .signInTickets(nonNegative(node, "signInTickets", defaults.getSignInTickets()))
            .streakDays(nonNegative(node, "streakDays", defaults.getStreakDays()))
            .streakTickets(nonNegative(node, "streakTickets", defaults.getStreakTickets()))
            .newUserTickets(nonNegative(node, "newUserTickets", defaults.getNewUserTickets()))
            .firstPurchaseTickets(nonNegative(node, "firstPurchaseTickets",
                defaults.getFirstPurchaseTickets()))
            // 兑换张数至少 1：配成 0 会让兑换项「扣了积分不发券」，是实打实的资损
            .exchangeTickets(atLeastOne(node, "exchangeTickets", defaults.getExchangeTickets()))
            .exchangePoints(atLeastOne(node, "exchangePoints", defaults.getExchangePoints()))
            .build();
    }

    private static int nonNegative(JsonNode node, String field, int fallback) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        var parsed = value.asInt(fallback);
        return Math.max(0, parsed);
    }

    private static int atLeastOne(JsonNode node, String field, int fallback) {
        var parsed = nonNegative(node, field, fallback);
        return parsed > 0 ? parsed : fallback;
    }
}
