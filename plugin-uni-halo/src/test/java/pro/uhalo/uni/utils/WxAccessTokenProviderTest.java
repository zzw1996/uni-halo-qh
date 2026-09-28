package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 共享 access_token provider 的缓存与并发语义。
 *
 * <p>收口的意义在于「全插件只用一份 token」：如果并发请求各换一次，后换的会把先换的挤掉，
 * 于是别的功能（小程序码、支付查单、订阅消息）会莫名其妙报 40001。这里钉住两件事——
 * 缓存命中不再换取、并发调用只换一次。</p>
 *
 * <p>测试手法：不真的发 HTTP（那需要一个 stub 服务器），而是看<b>配置读取次数</b>——
 * 每次真正换取 token 都必然会先读一次 appId/appSecret，因此读取次数就等于换取次数。
 * appId/appSecret 故意留空，让换取在发请求之前就失败（本地快速失败，无网络也没问题）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WxAccessTokenProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    private final AtomicInteger configReads = new AtomicInteger();

    private WxAccessTokenProvider provider() {
        when(settingFetcher.get(anyString())).thenAnswer(invocation -> {
            configReads.incrementAndGet();
            // 两个分组都返回空对象 = 没有配置 appId/appSecret
            return Mono.just(MAPPER.createObjectNode());
        });
        return new WxAccessTokenProvider(settingFetcher);
    }

    @Test
    void missingCredentialsShouldFailWithReadableReason() {
        var provider = provider();

        StepVerifier.create(provider.token())
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(IllegalStateException.class);
                assertThat(ex.getMessage()).contains("AppID 或 AppSecret 未配置");
            })
            .verify();
    }

    @Test
    void concurrentCallsShouldShareOneFetch() {
        var provider = provider();
        configReads.set(0);

        // 三个并发订阅者：应当只有一次换取（否则就是三次换取互相挤掉）
        var first = provider.token();
        var second = provider.token();
        var third = provider.token();

        StepVerifier.create(Mono.zip(first.onErrorResume(ex -> Mono.empty()),
                second.onErrorResume(ex -> Mono.empty()),
                third.onErrorResume(ex -> Mono.empty())))
            .verifyComplete();

        // 一次换取读两个配置分组 = 2 次；三次换取会是 6 次
        assertThat(configReads.get()).isEqualTo(2);
    }

    @Test
    void failureShouldNotBeCachedForever() {
        var provider = provider();

        StepVerifier.create(provider.token()).expectError().verify();
        var readsAfterFirst = configReads.get();

        // 失败后必须能重试：如果 in-flight 任务被永久缓存，之后所有请求都会拿到同一个错误
        StepVerifier.create(provider.token()).expectError().verify();
        assertThat(configReads.get()).isGreaterThan(readsAfterFirst);
    }

    @Test
    void invalidateShouldAllowRefetch() {
        var provider = provider();

        StepVerifier.create(provider.token()).expectError().verify();
        var readsBefore = configReads.get();

        // 40001 时调用方会 invalidate 再取一次
        provider.invalidate();
        StepVerifier.create(provider.token()).expectError().verify();

        assertThat(configReads.get()).isGreaterThan(readsBefore);
    }

    @Test
    void evictExpiredShouldBeSafeWhenNothingCached() {
        var provider = provider();

        // 空缓存时清理不能报错（每分钟都会跑一次）
        provider.evictExpired();
        provider.evictExpired();
        assertThat(configReads.get()).isLessThanOrEqualTo(2);
    }
}
