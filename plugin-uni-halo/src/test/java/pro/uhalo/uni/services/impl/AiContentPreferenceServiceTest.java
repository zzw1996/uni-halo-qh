package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.AiContentPreferenceRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
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
import static org.mockito.Mockito.when;

/**
 * 付费「AI 定制内容」的偏好读写。
 *
 * <p>这是<b>匿名可写</b>接口的后半段（前半段是权益判定），因此这里守两条底线：
 * 超限必须报错而不是静默截断（否则用户以为全生效了），
 * 分类 key 必须来自站长的清单（否则用户配出一个日报里根本不存在的分类）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentPreferenceServiceTest {

    @Mock
    private ReactiveExtensionClient client;

    private AiContentPreferenceService service() {
        return new AiContentPreferenceService(client);
    }

    /** 来源维度已取消，第二个参数保留为「未使用的占位」以少改调用处 */
    private static AiContentConfigDTO config(int maxCategories, int unusedSources,
        int maxKeywords) {
        return AiContentConfigDTO.builder()
            .enabled(true)
            .maxCategories(maxCategories)
            .maxKeywords(maxKeywords)
            .build();
    }

    private static DigestCategoryCatalog catalog(String... keys) {
        var rows = new java.util.ArrayList<pro.uhalo.uni.dto.DigestCategoryConfigDTO>();
        for (var key : keys) {
            rows.add(new pro.uhalo.uni.dto.DigestCategoryConfigDTO(key, key.toUpperCase(), "🏷",
                key + " 主题", ""));
        }
        return DigestCategoryCatalog.from(rows);
    }

    private void stubExisting(AiContentPreference... preferences) {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.fromArray(preferences));
    }

    @Test
    void loadWithoutRecordShouldReturnEmptyPreference() {
        stubExisting();

        StepVerifier.create(service().load("openid-1"))
            .assertNext(dto -> {
                assertThat(dto.isConfigured()).isFalse();
                assertThat(dto.getCategories()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void loadWithoutOpenidShouldNotTouchTheStore() {
        StepVerifier.create(service().load("  "))
            .assertNext(dto -> assertThat(dto.isEnabled()).isFalse())
            .verifyComplete();

        verify(client, never()).listAll(any(), any(), any());
    }

    @Test
    void saveShouldCreateForFirstTime() {
        stubExisting();
        when(client.create(any(AiContentPreference.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var request = new AiContentPreferenceRequest();
        request.setCategories(List.of("ai-ml"));
        request.setSources(List.of("blog.rust-lang.org"));
        request.setKeywords(List.of("rust"));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml", "security"),
                config(5, 5, 10), java.util.List.of()))
            .assertNext(dto -> {
                assertThat(dto.getCategories()).containsExactly("ai-ml");
                assertThat(dto.getKeywords()).containsExactly("rust");
                // 来源维度已取消：客户端即便还带着 sources，也不再落库（否则会继续参与筛选）
                assertThat(dto.getSources()).isEmpty();
                assertThat(dto.isConfigured()).isTrue();
            })
            .verifyComplete();

        verify(client).create(any(AiContentPreference.class));
    }

    @Test
    void saveShouldUpdateExistingRecordWithOpenidDerivedName() {
        var existing = new AiContentPreference();
        existing.setWxOpenid("openid-1");
        existing.setMetadata(new Metadata());
        existing.getMetadata().setName(AiContentPreferenceService.resourceName("openid-1"));
        stubExisting(existing);
        when(client.update(any(AiContentPreference.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var request = new AiContentPreferenceRequest();
        request.setCategories(List.of("security"));

        StepVerifier.create(service().save("openid-1", request, catalog("security"),
                config(5, 5, 10), java.util.List.of()))
            .assertNext(dto -> assertThat(dto.getCategories()).containsExactly("security"))
            .verifyComplete();

        // 一个用户永远只有一条记录：再次保存是 update，不是再 create 一条
        verify(client, never()).create(any());
        verify(client).update(any(AiContentPreference.class));
    }

    /** 超上限时报错而不是截断：静默丢掉用户勾的东西比报错更让人困惑 */
    @Test
    void tooManyItemsShouldFailInsteadOfTruncating() {
        stubExisting();

        var request = new AiContentPreferenceRequest();
        request.setKeywords(List.of("a", "b", "c"));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml"),
                config(5, 5, 2), java.util.List.of()))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(((WxAuthException) ex).getReason()).contains("最多选 2 项", "当前 3 项");
            })
            .verify();
    }

    @Test
    void unknownCategoryKeyShouldBeRejected() {
        stubExisting();

        var request = new AiContentPreferenceRequest();
        request.setCategories(List.of("ai-ml", "not-in-list"));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml"),
                config(5, 5, 10), java.util.List.of()))
            .expectErrorSatisfies(ex -> assertThat(((WxAuthException) ex).getReason())
                .contains("不在站长配置的分类清单里", "not-in-list"))
            .verify();
    }

    @Test
    void keywordDimensionCanBeDisabledByZeroLimit() {
        stubExisting();

        var request = new AiContentPreferenceRequest();
        request.setKeywords(List.of("rust"));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml"),
                config(5, 5, 0), java.util.List.of()))
            .expectErrorSatisfies(ex -> assertThat(((WxAuthException) ex).getReason())
                .contains("不可配置"))
            .verify();
    }

    @Test
    void tooLongItemShouldBeRejected() {
        stubExisting();

        var request = new AiContentPreferenceRequest();
        request.setKeywords(List.of("x".repeat(41)));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml"),
                config(5, 5, 10), java.util.List.of()))
            .expectErrorSatisfies(ex -> assertThat(((WxAuthException) ex).getReason())
                .contains("过长"))
            .verify();
    }

    @Test
    void blankItemsShouldBeDroppedAndDuplicatesCollapsed() {
        stubExisting();
        when(client.create(any(AiContentPreference.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var request = new AiContentPreferenceRequest();
        request.setKeywords(java.util.Arrays.asList("rust", " rust ", "", null, "Rust"));

        StepVerifier.create(service().save("openid-1", request, catalog("ai-ml"),
                config(5, 5, 10), java.util.List.of()))
            .assertNext(dto -> assertThat(dto.getKeywords()).containsExactly("rust", "Rust"))
            .verifyComplete();
    }

    @Test
    void missingOpenidShouldFail() {
        var request = new AiContentPreferenceRequest();

        StepVerifier.create(service().save("  ", request, catalog("ai-ml"), config(5, 5, 10), java.util.List.of()))
            .expectErrorSatisfies(ex -> assertThat(((WxAuthException) ex).getReason())
                .contains("Wechat-Session-Id"))
            .verify();
    }

    @Test
    void loadShouldAddConfiguredFlagOnlyWhenSomethingIsSet() {
        var record = new AiContentPreference();
        record.setWxOpenid("openid-1");
        record.setCategories(List.of("ai-ml"));
        record.setMetadata(new Metadata());
        record.getMetadata().setName(AiContentPreferenceService.resourceName("openid-1"));
        stubExisting(record);

        StepVerifier.create(service().load("openid-1"))
            .assertNext((AiContentPreferenceDTO dto) -> assertThat(dto.isConfigured()).isTrue())
            .verifyComplete();
    }
}
