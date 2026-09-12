package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 榜单配置解析与周榜奖励口径：默认值、越界收敛、按名次递减。
 */
class RankConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsKeepRankEnabledButWeeklyRewardOff() {
        var config = RankConfigHelper.defaults();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isShowScore()).isTrue();
        assertThat(config.isEnableInviteBoard()).isTrue();
        assertThat(config.isWallEnabled()).isTrue();
        assertThat(config.isWallIncludeUpvote()).isFalse();
        // 发放积分属于权益变动，默认必须关闭
        assertThat(config.isWeeklyRewardEnabled()).isFalse();
    }

    @Test
    void parseShouldClampOutOfRangeValues() throws Exception {
        // 动态条数与奖励名次超上限/低于下限时收敛，避免一次下发过多内容或超发
        var config = RankConfigHelper.parse(MAPPER.readTree("""
            {"wallLimit":999,"weeklyRewardTopN":0,
             "weeklyRewardPoints":-5,"weeklyRewardStep":-1}
            """));

        assertThat(config.getWallLimit()).isEqualTo(RankConfigHelper.MAX_WALL_LIMIT);
        assertThat(config.getWeeklyRewardTopN()).isEqualTo(1);
        assertThat(config.getWeeklyRewardPoints()).isZero();
        assertThat(config.getWeeklyRewardStep()).isZero();
    }

    @Test
    void parseShouldFallBackToDefaultsWhenGroupMissing() {
        assertThat(RankConfigHelper.parse(null).isEnabled()).isTrue();
    }

    @Test
    void rewardPointsShouldDecreaseByRankAndStopAtZero() {
        var config = RankConfigHelper.defaults();
        config.setWeeklyRewardPoints(60);
        config.setWeeklyRewardStep(20);

        assertThat(RankConfigHelper.rewardPointsForRank(config, 1)).isEqualTo(60);
        assertThat(RankConfigHelper.rewardPointsForRank(config, 2)).isEqualTo(40);
        assertThat(RankConfigHelper.rewardPointsForRank(config, 3)).isEqualTo(20);
        // 第 4 名递减到 0，不发放
        assertThat(RankConfigHelper.rewardPointsForRank(config, 4)).isZero();
        assertThat(RankConfigHelper.rewardPointsForRank(config, 0)).isZero();
        assertThat(RankConfigHelper.rewardPointsForRank(null, 1)).isZero();
    }
}
