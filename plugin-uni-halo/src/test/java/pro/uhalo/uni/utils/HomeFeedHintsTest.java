package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首页「为什么给我看这些」的降级与提示。
 *
 * <p>这套文案是修一个真实反馈时补上的：用户取消全部订阅后首页仍在按分类筛选，
 * 因为内容其实来自**邀请人的推荐**，而当时一句话提示都没有——看起来就是「取消没生效」。
 * 四种原因必须能区分开，且「订阅没丢」这件事要说清。</p>
 */
class HomeFeedHintsTest {

    @Test
    void ownSubscriptionsShouldNotProduceAnyHint() {
        var decision = HomeFeedHints.decide("mine", 3, 1, 0, false);

        assertThat(decision.fallback()).isNull();
        assertThat(decision.hint()).isEmpty();
    }

    @Test
    void inheritedOnlyShouldExplainWhereTheContentComesFrom() {
        // 自己 0 个、继承 2 个：首页确实在按分类筛，但那是邀请人的推荐
        var decision = HomeFeedHints.decide("mine", 0, 2, 0, false);

        assertThat(decision.fallback()).isEqualTo(HomeFeedHints.FALLBACK_INHERITED_ONLY);
        assertThat(decision.hint()).contains("邀请人的推荐").contains("2 个分类");
    }

    @Test
    void pausedCategoriesShouldBeExplainedBeforeEverythingElse() {
        var decision = HomeFeedHints.decide("mine", 7, 0, 2, false);

        assertThat(decision.fallback()).isNull();
        assertThat(decision.hint()).contains("2 个分类").contains("暂停");
    }

    @Test
    void noContentShouldSayTheSubscriptionIsStillThere() {
        // 订了但没有内容：与「没有订阅」是两回事，不能让用户以为订阅丢了
        var decision = HomeFeedHints.decide("mine", 2, 0, 0, true);

        assertThat(decision.fallback()).isEqualTo(HomeFeedHints.FALLBACK_NO_CONTENT);
        assertThat(decision.hint()).contains("订阅").contains("全站最新");
    }

    @Test
    void hotScopeShouldInviteTheUserToSubscribe() {
        var decision = HomeFeedHints.decide("hot", 0, 0, 0, false);

        assertThat(decision.fallback()).isEqualTo(HomeFeedHints.FALLBACK_NO_SUBSCRIPTION);
        assertThat(decision.hint()).contains("还没订阅分类").contains("发现页");
    }

    @Test
    void emptyHotFeedShouldTellTheTruth() {
        var decision = HomeFeedHints.decide("hot", 0, 0, 0, true);

        assertThat(decision.fallback()).isEqualTo(HomeFeedHints.FALLBACK_HOT_EMPTY);
        assertThat(decision.hint()).contains("暂时没有可推荐的内容");
    }
}
