package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.services.DigestTaxonomyService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类订阅热度榜的口径测试。
 *
 * <p>锁住的核心是「谁算一个订阅用户」与「算不算继承项」：热度是「发现」页的排序依据，
 * 把继承项或已退订的人算进去，会让用户看到的名次与实际兴趣分布对不上——
 * 而这种偏差没有别的办法察觉（数字看起来永远都是"合理"的）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DigestSubscriptionStatsServiceTest {

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private DigestTaxonomyService taxonomyService;

    @InjectMocks
    private DigestSubscriptionStatsServiceImpl service;

    private void stub(List<DigestSubscription> subscriptions) {
        when(client.listAll(any(), any(ListOptions.class), isNull()))
            .thenReturn(Flux.fromIterable(subscriptions));
        when(taxonomyService.categoryLabels()).thenReturn(Mono.just(labels()));
    }

    private static Map<String, String> labels() {
        return Map.of("security", "安全", "ai-ml", "AI / ML", "engineering", "工程");
    }

    private static DigestSubscription subscription(String openid, Boolean enabled,
        List<String> own, List<String> inherited) {
        var subscription = new DigestSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(enabled);
        subscription.setPostCategories(own);
        subscription.setInheritedCategories(inherited);
        return subscription;
    }

    @Test
    void rankingShouldCountActiveSubscribersPerCategory() {
        stub(List.of(
            subscription("u1", true, List.of("security", "ai-ml"), null),
            subscription("u2", true, List.of("security"), null),
            subscription("u3", true, List.of("engineering"), null)));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                assertThat(ranking.getMetric()).isEqualTo("own");
                assertThat(ranking.getTotalSubscribers()).isEqualTo(3);
                assertThat(ranking.getItems()).extracting(item -> item.getName() + "="
                    + item.getSubscribers()).containsExactly("security=2", "ai-ml=1",
                    "engineering=1");
                // 显示名从站点分类补，前端不必自己维护映射
                assertThat(ranking.getItems().get(0).getLabel()).isEqualTo("安全");
            })
            .verifyComplete();
    }

    @Test
    void inheritedCategoriesShouldNotCountAsSubscription() {
        // 继承是「谁邀请了他就用谁订的分类当起点」，不是兴趣表达：算进去热度会虚高
        stub(List.of(subscription("u1", true, List.of(), List.of("security", "ai-ml"))));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                assertThat(ranking.getItems()).isEmpty();
                assertThat(ranking.getTotalSubscribers()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void disabledAndOrphanRecordsShouldBeExcluded() {
        stub(List.of(
            subscription("u1", true, List.of("security"), null),
            subscription("u2", false, List.of("security", "ai-ml"), null),
            subscription(null, true, List.of("security"), null),
            subscription("u4", null, List.of("ai-ml"), null)));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                // u2 关掉了订阅、u3 没有 openid：都不算订阅用户
                assertThat(ranking.getTotalSubscribers()).isEqualTo(2);
                // 人数相同时按分类名排序，所以 ai-ml 在 security 前面
                assertThat(ranking.getItems()).extracting(item -> item.getName() + "="
                    + item.getSubscribers()).containsExactly("ai-ml=1", "security=1");
            })
            .verifyComplete();
    }

    @Test
    void legacyCategoriesShouldFallBackToTheSiteCategoryList() {
        // 老订阅只存了 AI 分类字段：口径函数会回落，热度榜不能因此漏掉这批人
        var legacy = subscription("u1", true, null, null);
        legacy.setCategories(List.of("ai-ml"));
        stub(List.of(legacy));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> assertThat(ranking.getItems()).extracting(item -> item.getName())
                .containsExactly("ai-ml"))
            .verifyComplete();
    }

    @Test
    void deletedCategoriesShouldFallBackToTheirName() {
        // 分类被删掉后 label 解析不到：仍然返回（label 回落 name），由前端按站点分类清单过滤
        when(client.listAll(any(), any(ListOptions.class), isNull()))
            .thenReturn(Flux.just(subscription("u1", true, List.of("gone"), null)));
        when(taxonomyService.categoryLabels()).thenReturn(Mono.just(labels()));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                assertThat(ranking.getItems()).hasSize(1);
                assertThat(ranking.getItems().get(0).getLabel()).isEqualTo("gone");
            })
            .verifyComplete();
    }

    @Test
    void rankingShouldBeCachedBetweenCalls() {
        stub(List.of(subscription("u1", true, List.of("security"), null)));

        StepVerifier.create(service.categoryRanking().then(service.categoryRanking()))
            .assertNext(ranking -> assertThat(ranking.getTotalSubscribers()).isEqualTo(1))
            .verifyComplete();

        // 5 分钟内第二次调用直接吃缓存：一次进页面不该扫两遍全表
        verify(client, times(1)).listAll(any(), any(ListOptions.class), isNull());
    }

    @Test
    void aggregationFailureShouldNotBreakThePage() {
        // 读不到订阅记录（例如索引在重建）时返回空榜，而不是把「发现」页整页打挂
        when(client.listAll(any(), any(ListOptions.class), isNull()))
            .thenReturn(Flux.error(new IllegalStateException("index building")));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                assertThat(ranking.getItems()).isEmpty();
                assertThat(ranking.getTotalSubscribers()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void labelLookupFailureShouldStillReturnTheRanking() {
        when(client.listAll(any(), any(ListOptions.class), isNull()))
            .thenReturn(Flux.just(subscription("u1", true, List.of("security"), null)));
        when(taxonomyService.categoryLabels())
            .thenReturn(Mono.error(new IllegalStateException("category list down")));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                assertThat(ranking.getItems()).hasSize(1);
                assertThat(ranking.getItems().get(0).getName()).isEqualTo("security");
                assertThat(ranking.getItems().get(0).getLabel()).isEqualTo("security");
            })
            .verifyComplete();
    }

    @Test
    void rankingShouldBeDeterministicForEqualCounts() {
        stub(List.of(
            subscription("u1", true, List.of("security", "engineering", "ai-ml"), null)));

        StepVerifier.create(service.categoryRanking())
            .assertNext(ranking -> {
                // 人数相同（都是 1）时按分类名排序，保证同一份数据每次的榜单顺序一致
                assertThat(ranking.getItems()).extracting(item -> item.getName())
                    .containsExactly("ai-ml", "engineering", "security");
            })
            .verifyComplete();
    }
}
