package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.RankConfigDTO;

/**
 * 榜单配置解析：来自设置分组 rankConfig。
 *
 * <p>周榜奖励（weeklyReward*）默认关闭，站长开启后由定时任务按周结算。</p>
 */
public final class RankConfigHelper {

    public static final String GROUP_NAME = "rankConfig";

    /** 动态墙条数上限，避免站长配置过大导致一次下发过多用户内容 */
    public static final int MAX_WALL_LIMIT = 30;

    /** 周榜奖励名次上限 */
    public static final int MAX_REWARD_TOP_N = 10;

    private RankConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static RankConfigDTO defaults() {
        return RankConfigDTO.builder()
            .enabled(true)
            .showScore(true)
            .enableInviteBoard(true)
            .wallEnabled(true)
            .wallLimit(10)
            .wallIncludeUpvote(false)
            .weeklyRewardEnabled(false)
            .weeklyRewardTopN(3)
            .weeklyRewardPoints(60)
            .weeklyRewardStep(20)
            .build();
    }

    public static RankConfigDTO parse(JsonNode node) {
        var defaults = defaults();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults;
        }
        return RankConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(true))
            .showScore(node.path("showScore").asBoolean(true))
            .enableInviteBoard(node.path("enableInviteBoard").asBoolean(true))
            .wallEnabled(node.path("wallEnabled").asBoolean(true))
            .wallLimit(clamp(node.path("wallLimit").asInt(defaults.getWallLimit()), 1, MAX_WALL_LIMIT))
            .wallIncludeUpvote(node.path("wallIncludeUpvote").asBoolean(false))
            .weeklyRewardEnabled(node.path("weeklyRewardEnabled").asBoolean(false))
            .weeklyRewardTopN(clamp(node.path("weeklyRewardTopN").asInt(defaults.getWeeklyRewardTopN()), 1,
                MAX_REWARD_TOP_N))
            .weeklyRewardPoints(Math.max(0, node.path("weeklyRewardPoints").asInt(
                defaults.getWeeklyRewardPoints())))
            .weeklyRewardStep(Math.max(0, node.path("weeklyRewardStep").asInt(
                defaults.getWeeklyRewardStep())))
            .build();
    }

    /**
     * 按名次计算奖励积分：第 1 名 {@code base}，之后每名递减 {@code step}，最低为 0（不发放）。
     */
    public static int rewardPointsForRank(RankConfigDTO config, int rank) {
        if (config == null || rank < 1) {
            return 0;
        }
        var points = config.getWeeklyRewardPoints() - (rank - 1) * config.getWeeklyRewardStep();
        return Math.max(0, points);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
