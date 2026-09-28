package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 订阅消息额度的记账。
 *
 * <p>一次性订阅的额度是「一次授权一条」，而微信不提供额度查询——这里记错一次，
 * 要么用户永远收不到（额度虚低），要么我们一直去撞 43101（额度虚高）。
 * 所以每条规则都要钉住：授权 +1、发送 -1 不为负、被拒清零、关掉开关即不可用。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushSubscriptionServiceTest {

    @Mock
    private ReactiveExtensionClient client;

    private PushSubscriptionService service() {
        return new PushSubscriptionService(client);
    }

    private void stubExisting(PushSubscription... records) {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.fromArray(records));
        when(client.create(any(PushSubscription.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(PushSubscription.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private static PushSubscription record(String openid, int newPost, int aiDigest) {
        var subscription = new PushSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(true);
        subscription.setNewPostQuota(newPost);
        subscription.setAiDigestQuota(aiDigest);
        var metadata = new Metadata();
        metadata.setName(PushSubscriptionService.resourceName(openid));
        subscription.setMetadata(metadata);
        return subscription;
    }

    @Test
    void loadWithoutRecordShouldReturnZeroQuota() {
        stubExisting();

        StepVerifier.create(service().load("openid-1"))
            .assertNext(subscription -> {
                assertThat(subscription.getNewPostQuota()).isZero();
                assertThat(PushSubscriptionService.hasQuota(subscription,
                    PushConfigHelper.KIND_NEW_POST)).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void grantShouldAddOneQuotaPerKind() {
        stubExisting(record("openid-1", 1, 0));

        StepVerifier.create(service().grant("openid-1",
                List.of(PushConfigHelper.KIND_NEW_POST, PushConfigHelper.KIND_AI_DIGEST)))
            .assertNext(subscription -> {
                assertThat(subscription.getNewPostQuota()).isEqualTo(2);
                assertThat(subscription.getAiDigestQuota()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void grantShouldCapQuotaToBoundAbuse() {
        stubExisting(record("openid-1", PushSubscriptionService.MAX_QUOTA, 0));

        StepVerifier.create(service().grant("openid-1", List.of(PushConfigHelper.KIND_NEW_POST)))
            .assertNext(subscription -> assertThat(subscription.getNewPostQuota())
                .isEqualTo(PushSubscriptionService.MAX_QUOTA))
            .verifyComplete();
    }

    @Test
    void consumeShouldDecreaseAndNeverGoNegative() {
        stubExisting(record("openid-1", 1, 0));

        StepVerifier.create(service().consume("openid-1", PushConfigHelper.KIND_NEW_POST))
            .assertNext(subscription -> assertThat(subscription.getNewPostQuota()).isZero())
            .verifyComplete();

        // 额度已经是 0：再扣也不能变成负数（否则 hasQuota 的语义就乱了）
        stubExisting(record("openid-1", 0, 0));
        StepVerifier.create(service().consume("openid-1", PushConfigHelper.KIND_NEW_POST))
            .assertNext(subscription -> assertThat(subscription.getNewPostQuota()).isZero())
            .verifyComplete();
    }

    @Test
    void clearQuotaShouldZeroOnlyThatKind() {
        stubExisting(record("openid-1", 3, 2));

        StepVerifier.create(service().clearQuota("openid-1", PushConfigHelper.KIND_NEW_POST))
            .assertNext(subscription -> {
                assertThat(subscription.getNewPostQuota()).isZero();
                // 只清被拒的那一类：另一种模板的额度不受影响
                assertThat(subscription.getAiDigestQuota()).isEqualTo(2);
            })
            .verifyComplete();
    }

    @Test
    void userSwitchShouldDisableQuotaChecks() {
        var disabled = record("openid-1", 5, 5);
        disabled.setEnabled(false);

        assertThat(PushSubscriptionService.hasQuota(disabled, PushConfigHelper.KIND_NEW_POST))
            .isFalse();
        assertThat(PushSubscriptionService.hasQuota(disabled, PushConfigHelper.KIND_AI_DIGEST))
            .isFalse();
    }

    @Test
    void setEnabledShouldPersist() {
        stubExisting(record("openid-1", 1, 1));

        StepVerifier.create(service().setEnabled("openid-1", false))
            .assertNext(subscription -> assertThat(subscription.getEnabled()).isFalse())
            .verifyComplete();
    }

    /**
     * 建索引期间写入被拒 → 自动重试，用户不该看到 500。
     *
     * <p>{@code PushSubscription} 是随升级新增的扩展类型，索引在插件启动时才建；
     * 实例较忙时建索引不止一秒，所以这里给到 5 次 × 1 秒。</p>
     */
    @Test
    void grantShouldRetryWhileIndexIsBuilding() {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(client.create(any(PushSubscription.class))).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                return Mono.error(new IllegalStateException("Index is building"));
            }
            return Mono.just(invocation.getArgument(0));
        });

        StepVerifier.create(service().grant("openid-1", List.of(PushConfigHelper.KIND_NEW_POST)))
            .assertNext(subscription -> assertThat(subscription.getNewPostQuota()).isEqualTo(1))
            .verifyComplete();

        assertThat(attempts.get()).isEqualTo(2);
    }

    /** 非「建索引」类错误不重试：重试也救不回来，立刻如实报错 */
    @Test
    void grantShouldNotRetryOtherErrors() {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(client.create(any(PushSubscription.class))).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("metadata.name 非法"));
        });

        StepVerifier.create(service().grant("openid-1", List.of(PushConfigHelper.KIND_NEW_POST)))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("metadata.name 非法"))
            .verify();

        assertThat(attempts.get()).isEqualTo(1);
    }

    /**
     * 首次授权：必须 <b>create</b>，绝不能 update。
     *
     * <p>这是踩过的坑：新建的记录同样带 {@code metadata.name}（资源名由 openid 派生），
     * 若按「有没有 name」决定 create/update，首次写入就会去 update 一条不存在的记录，
     * Halo 直接报错——用户点「同意」时就是一个 500。</p>
     */
    @Test
    void grantShouldCreateWhenNoRecordExists() {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(PushSubscription.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        // update 故意不打桩：一旦被调用就返回 null，链上立刻炸成 NPE，用例必然失败
        when(client.update(any(PushSubscription.class))).thenReturn(
            Mono.error(new IllegalStateException("不该在首次授权时走 update")));

        StepVerifier.create(service().grant("openid-1", List.of(PushConfigHelper.KIND_NEW_POST)))
            .assertNext(subscription -> assertThat(subscription.getNewPostQuota()).isEqualTo(1))
            .verifyComplete();

        verify(client, times(1)).create(any(PushSubscription.class));
        verify(client, never()).update(any(PushSubscription.class));
    }

    @Test
    void resourceNameShouldBeStableAndPerUser() {
        assertThat(PushSubscriptionService.resourceName("openid-1"))
            .isEqualTo(PushSubscriptionService.resourceName("openid-1"))
            .startsWith("ps-");
        // openid 区分大小写：归一化会让不同用户落到同一条记录上
        assertThat(PushSubscriptionService.resourceName("Openid-1"))
            .isNotEqualTo(PushSubscriptionService.resourceName("openid-1"));
    }

    @Test
    void describeQuotaShouldBeHumanReadable() {
        assertThat(PushSubscriptionService.describeQuota(record("openid-1", 2, 0)))
            .isEqualTo("新文章 2 / 简报 0");
    }
}
