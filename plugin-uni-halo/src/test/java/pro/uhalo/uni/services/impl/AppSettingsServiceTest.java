package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.UserAppSettings;
import pro.uhalo.uni.utils.AppSettingsCatalog;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用设置副本的读取与字段级合并。
 *
 * <p>这块逻辑写错的后果都是「用户明明改过、却看起来没生效」，而且很难归因：
 * 合并写错会互相覆盖（一端改底栏把另一端的卡片样式冲掉）、
 * 白名单写错会把非法值存进存储、revision 写错会让客户端判断错「谁更新」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppSettingsServiceTest {

    private static final String OPENID = "oTestOpenid0001";

    @Mock
    private ReactiveExtensionClient client;

    private AppSettingsService service() {
        return new AppSettingsService(client);
    }

    private static UserAppSettings record(String openid, Long revision, String payload) {
        var record = new UserAppSettings();
        record.setWxOpenid(openid);
        record.setRevision(revision);
        record.setPayload(payload);
        record.setUpdatedAt(Instant.parse("2026-09-28T02:00:00Z"));
        var metadata = new Metadata();
        metadata.setName(AppSettingsService.resourceName(openid));
        record.setMetadata(metadata);
        return record;
    }

    private void stubEmpty() {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(UserAppSettings.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(UserAppSettings.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private void stubExisting(UserAppSettings... records) {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.fromArray(records));
        when(client.update(any(UserAppSettings.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void loadWithoutRecordShouldReturnEmptyViewRatherThanThrow() {
        stubEmpty();

        StepVerifier.create(service().load(OPENID))
            .assertNext(view -> {
                assertThat(view.synced()).isFalse();
                assertThat(view.revision()).isZero();
                assertThat(view.settings()).isEmpty();
                // 空视图不能带任何「清空」含义：客户端据此保留本地设置
                assertThat(view.updatedAt()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void loadShouldReturnStoredSettingsAndRevision() {
        stubExisting(record(OPENID, 3L, "{\"tabbar.show.rank\":false,\"appearance.mode\":\"dark\"}"));

        StepVerifier.create(service().load(OPENID))
            .assertNext(view -> {
                assertThat(view.synced()).isTrue();
                assertThat(view.revision()).isEqualTo(3L);
                assertThat(view.settings())
                    .containsEntry("tabbar.show.rank", false)
                    .containsEntry("appearance.mode", "dark");
                // 时间按北京时间展示，前端直接用
                assertThat(view.updatedAt()).isEqualTo("2026-09-28 10:00");
            })
            .verifyComplete();
    }

    @Test
    void firstMergeShouldCreateRecordWithRevisionOne() {
        stubEmpty();

        StepVerifier.create(service().merge(OPENID, Map.of("tabbar.show.rank", false)))
            .assertNext(view -> {
                assertThat(view.synced()).isTrue();
                assertThat(view.revision()).isEqualTo(1L);
                assertThat(view.settings()).containsEntry("tabbar.show.rank", false);
                assertThat(view.rejected()).isEmpty();
            })
            .verifyComplete();

        verify(client).create(any(UserAppSettings.class));
    }

    @Test
    void mergeShouldKeepUntouchedFields() {
        // 两台设备各改各的：这台只提交外观，底栏的那一项必须原样保留
        stubExisting(record(OPENID, 5L, "{\"tabbar.show.rank\":false,\"layout.home\":\"h_row_col2\"}"));

        StepVerifier.create(service().merge(OPENID, Map.of("appearance.mode", "dark")))
            .assertNext(view -> {
                assertThat(view.settings())
                    .containsEntry("tabbar.show.rank", false)
                    .containsEntry("layout.home", "h_row_col2")
                    .containsEntry("appearance.mode", "dark");
                assertThat(view.revision()).isEqualTo(6L);
            })
            .verifyComplete();
    }

    @Test
    void mergeShouldDropAndReportKeysOutsideTheWhitelist() {
        stubEmpty();

        var incoming = new LinkedHashMap<String, Object>();
        incoming.put("tabbar.useCustom", true);
        incoming.put("ad.disabled", true);
        incoming.put("appearance.mode", "neon");   // 非法枚举

        StepVerifier.create(service().merge(OPENID, incoming))
            .assertNext(view -> {
                assertThat(view.settings()).containsExactly(Map.entry("tabbar.useCustom", true));
                assertThat(view.rejected()).containsExactlyInAnyOrder("ad.disabled", "appearance.mode");
            })
            .verifyComplete();
    }

    @Test
    void mergeShouldPruneKeysThatAreNoLongerWhitelisted() {
        // 存量里可能留着白名单收敛掉的旧键：顺手清掉，别让它一直被读回去
        stubExisting(record(OPENID, 2L,
            "{\"ad.disabled\":true,\"tabbar.show.rank\":false}"));

        StepVerifier.create(service().merge(OPENID, Map.of("flotbutton.show", false)))
            .assertNext(view -> assertThat(view.settings())
                .containsOnlyKeys("tabbar.show.rank", "flotbutton.show"))
            .verifyComplete();
    }

    @Test
    void mergeShouldRefuseOversizedRequests() {
        stubEmpty();
        var huge = new LinkedHashMap<String, Object>();
        for (int i = 0; i < AppSettingsCatalog.MAX_REQUEST_KEYS + 1; i++) {
            huge.put("k" + i, true);
        }

        StepVerifier.create(service().merge(OPENID, huge))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("过多"))
            .verify();
    }

    @Test
    void mergeWithoutOpenidShouldFail() {
        StepVerifier.create(service().merge("  ", Map.of("tabbar.useCustom", true)))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("Wechat-Session-Id"))
            .verify();
    }

    @Test
    void mergeShouldRetryAfterOptimisticLockConflict() {
        // 多端同时保存：第一次写入被 Halo 的乐观锁拒绝，重试时重新读一遍再合并
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        // 每次订阅都产生一条「未被本次写入改动过」的新记录。
        //
        // 必须用 Flux.defer 包一层：真实的 client.listAll 每被订阅一次就重新查一次，
        // 而 Mockito 的 thenAnswer 只在「调用 mock 方法」时执行一次、之后重试订阅到的是
        // 同一个已materialize 的 Flux——那样重试会拿到同一个被改过的对象，
        // revision 看起来被加了两次，误判成服务端重复记数。
        when(client.listAll(any(), any(), any())).thenAnswer(invocation -> Flux.defer(() -> {
            reads.incrementAndGet();
            return Flux.just(record(OPENID, 1L, "{\"tabbar.show.rank\":false}"));
        }));
        when(client.update(any(UserAppSettings.class))).thenAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                return Mono.error(new IllegalStateException(
                    "Failed to update versioned entity ... concurrently?"));
            }
            return Mono.just(invocation.getArgument(0));
        });

        StepVerifier.create(service().merge(OPENID, Map.of("appearance.mode", "dark")))
            .assertNext(view -> {
                assertThat(view.settings())
                    .containsEntry("tabbar.show.rank", false)
                    .containsEntry("appearance.mode", "dark");
                // 重试是「重新读 → 重新合并 → 再写」，不是盲目重发：
                // 因此 revision 只 +1（读到的仍是 revision=1 的那一版）
                assertThat(view.revision()).isEqualTo(2L);
            })
            .verifyComplete();

        assertThat(reads.get()).as("冲突后必须重新读一次再合并").isEqualTo(2);
        assertThat(writes.get()).as("第一次写冲突，第二次成功").isEqualTo(2);
    }

    @Test
    void brokenPayloadShouldBeTreatedAsNoSettings() {
        // 人工改坏过存储：不能把接口打挂，按「没有设置」继续
        stubExisting(record(OPENID, 1L, "{not-json"));

        StepVerifier.create(service().load(OPENID))
            .assertNext(view -> {
                assertThat(view.synced()).isTrue();
                assertThat(view.settings()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void resourceNameShouldBeStableAndNotLeakOpenid() {
        var name = AppSettingsService.resourceName(OPENID);

        assertThat(name).startsWith("uas-").hasSize(4 + 64);
        assertThat(name).doesNotContain(OPENID);
        assertThat(AppSettingsService.resourceName(OPENID)).isEqualTo(name);
        assertThat(AppSettingsService.resourceName("  " + OPENID + " ")).isEqualTo(name);
        assertThat(AppSettingsService.resourceName("oAnotherOpenid")).isNotEqualTo(name);
    }
}
