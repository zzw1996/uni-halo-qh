package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.utils.DigestTextUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头图上传的地址处理测试。
 *
 * <p><b>这里锁住的是一个线上故障</b>：Halo 在本地存储策略下返回的 permalink 是相对路径
 * （{@code /upload/2026/09/x.jpg}），而「这篇有没有封面」的判定要求绝对地址。
 * 两端判据不一致的结果是：图片明明上传成功，却被判成「没有封面」，
 * 整轮日报因此不发布，报错还提示「检查附件存储策略」——把排查引向完全错误的方向。</p>
 *
 * <p>因此本测试最关键的断言不是「返回了什么字符串」，而是
 * <b>上传结果必须能通过封面判定</b>（{@link DigestTextUtils#isUsableImageUrl(String)}）——
 * 那才是下游真正使用的判据。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestAssetUploaderImplTest {

    private static final String SOURCE_URL = "https://example.com/cover.jpg";

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private ExternalLinkProcessor externalLinkProcessor;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @InjectMocks
    private DigestAssetUploaderImpl uploader;

    private static DigestConfigDTO config(String policyName, String groupName) {
        return DigestConfigDTO.builder()
            .attachmentPolicyName(policyName)
            .attachmentGroupName(groupName)
            .build();
    }

    private static Attachment attachment(String permalink) {
        var attachment = new Attachment();
        var status = new Attachment.AttachmentStatus();
        status.setPermalink(permalink);
        attachment.setStatus(status);
        return attachment;
    }

    private void stubUploadFromUrl(String rawPermalink, String absolutePermalink) {
        try {
            when(attachmentService.uploadFromUrl(any(URL.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(attachment(rawPermalink)));
            when(attachmentService.getPermalink(any(Attachment.class)))
                .thenReturn(Mono.just(URI.create(rawPermalink)));
            when(externalLinkProcessor.processLink(rawPermalink)).thenReturn(absolutePermalink);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ==================== 核心回归：相对 permalink 必须绝对化 ====================

    /**
     * Halo 返回相对 permalink 时，必须补成绝对地址，且补完能通过封面判定。
     *
     * <p>这正是线上失败的那条路径：不补全 → {@code isUsableImageUrl} 返回 false →
     * 全部文章被判成「没有封面」→ 整轮不发布。</p>
     */
    @Test
    void relativePermalinkShouldBeAbsolutisedAndPassCoverCheck() {
        stubUploadFromUrl("/upload/2026/09/cover.jpg",
            "https://site.example.com/upload/2026/09/cover.jpg");
        when(settingFetcher.get("appConfig")).thenReturn(Mono.empty());

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-example", config("", "")))
            .assertNext(result -> {
                assertThat(result).isEqualTo("https://site.example.com/upload/2026/09/cover.jpg");
                assertThat(DigestTextUtils.isUsableImageUrl(result))
                    .as("上传结果必须能通过「有没有封面」的判定，否则会被静默丢弃")
                    .isTrue();
            })
            .verifyComplete();
    }

    /** 已经是绝对地址时原样可用（不能因为多补一次而拼坏）。 */
    @Test
    void absolutePermalinkShouldStayUsable() {
        stubUploadFromUrl("/upload/a.jpg", "https://cdn.example.com/upload/a.jpg");
        when(settingFetcher.get("appConfig")).thenReturn(Mono.empty());

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("", "")))
            .assertNext(result -> assertThat(DigestTextUtils.isUsableImageUrl(result)).isTrue())
            .verifyComplete();
    }

    /** getPermalink 拿不到时退回 status.permalink，同样要绝对化。 */
    @Test
    void shouldFallBackToStatusPermalinkWhenServiceReturnsNothing() {
        when(attachmentService.uploadFromUrl(any(URL.class), anyString(), anyString(), anyString()))
            .thenReturn(Mono.just(attachment("/upload/fallback.jpg")));
        when(attachmentService.getPermalink(any(Attachment.class))).thenReturn(Mono.empty());
        when(externalLinkProcessor.processLink("/upload/fallback.jpg"))
            .thenReturn("https://site.example.com/upload/fallback.jpg");
        when(settingFetcher.get("appConfig")).thenReturn(Mono.empty());

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("", "")))
            .assertNext(result -> {
                assertThat(result).isEqualTo("https://site.example.com/upload/fallback.jpg");
                assertThat(DigestTextUtils.isUsableImageUrl(result)).isTrue();
            })
            .verifyComplete();
    }

    // ==================== 存储策略解析 ====================

    /** 显式配置了策略就用它，不必再读应用配置。 */
    @Test
    void explicitPolicyShouldWin() {
        stubUploadFromUrl("/upload/a.jpg", "https://site/a.jpg");

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("my-policy", "my-group")))
            .expectNextCount(1)
            .verifyComplete();

        var policy = capturePolicy();
        assertThat(policy).isEqualTo("my-policy");
        verify(settingFetcher, never()).get("appConfig");
    }

    /** 未配置策略时复用「应用配置」里的策略（与二维码/头像上传保持一致）。 */
    @Test
    void shouldReuseAppConfigPolicy() {
        stubUploadFromUrl("/upload/a.jpg", "https://site/a.jpg");
        when(settingFetcher.get("appConfig")).thenReturn(Mono.just(appConfig("app-policy", "app-group")));

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("", "")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(capturePolicy()).isEqualTo("app-policy");
    }

    /**
     * 哪里都没配策略时兜底 default-policy，而不是直接失败。
     *
     * <p>「应用配置」里的策略字段被「动态小程序码」开关包着，没开那个功能的站点
     * 根本不会存这个值。若在这里报错，日报会因为一个与它无关的开关而彻底发不出去。</p>
     */
    @Test
    void shouldFallBackToDefaultPolicyWhenNothingConfigured() {
        stubUploadFromUrl("/upload/a.jpg", "https://site/a.jpg");
        when(settingFetcher.get("appConfig")).thenReturn(Mono.empty());

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("", "")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(capturePolicy()).as("策略缺失时不得让整轮日报失败").isEqualTo("default-policy");
    }

    /** 读取应用配置失败也不能让上传整体失败。 */
    @Test
    void shouldFallBackToDefaultPolicyWhenAppConfigReadFails() {
        stubUploadFromUrl("/upload/a.jpg", "https://site/a.jpg");
        when(settingFetcher.get("appConfig"))
            .thenReturn(Mono.error(new IllegalStateException("配置读取失败")));

        StepVerifier.create(uploader.uploadImage(SOURCE_URL, "01-x", config("", "")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(capturePolicy()).isEqualTo("default-policy");
    }

    // ==================== 无效输入与失败兜底 ====================

    /** 不是可用图片地址时直接跳过，不做任何上传尝试。 */
    @Test
    void shouldSkipNonImageUrl() {
        StepVerifier.create(uploader.uploadImage("ftp://example.com/a.jpg", "01-x", config("", "")))
            .verifyComplete();

        verify(attachmentService, never())
            .uploadFromUrl(any(URL.class), anyString(), anyString(), anyString());
    }

    @Test
    void shouldSkipBlankUrl() {
        StepVerifier.create(uploader.uploadImage("  ", "01-x", config("", "")))
            .verifyComplete();

        verify(attachmentService, never())
            .uploadFromUrl(any(URL.class), anyString(), anyString(), anyString());
    }

    /**
     * 两级都失败时返回空（这张图作废），绝不向上抛异常。
     *
     * <p>单张图失败不该让整篇日报失败——由调用方按「只发布带封面图」的配置决定怎么处理。</p>
     */
    @Test
    void shouldCompleteEmptyWhenBothStagesFail() {
        when(attachmentService.uploadFromUrl(any(URL.class), anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("转存失败")));
        when(settingFetcher.get("appConfig")).thenReturn(Mono.empty());

        // 下载阶段打一个不可路由的地址，必然失败（本地回环高位端口，连接被拒即返回）
        StepVerifier.create(uploader.uploadImage("http://127.0.0.1:9/cover.jpg", "01-x",
                config("", "")))
            .verifyComplete();
    }

    /**
     * 「前缀合法但解析不出来」的地址要在这里就被拦下，不进入任何上传阶段。
     *
     * <p>{@code isUsableImageUrl} 只看前缀，所以 {@code https://} 这种值能过门；
     * 若不在入口统一解析一次，它会在下载阶段以同步异常的形式炸出来。</p>
     */
    @Test
    void shouldSkipMalformedUrlBeforeAnyStage() {
        StepVerifier.create(uploader.uploadImage("https://", "01-x", config("p", "")))
            .verifyComplete();

        verify(attachmentService, never())
            .uploadFromUrl(any(URL.class), anyString(), anyString(), anyString());
    }

    private String capturePolicy() {
        var urlCaptor = ArgumentCaptor.forClass(URL.class);
        var policyCaptor = ArgumentCaptor.forClass(String.class);
        var groupCaptor = ArgumentCaptor.forClass(String.class);
        var nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(attachmentService).uploadFromUrl(urlCaptor.capture(), policyCaptor.capture(),
            groupCaptor.capture(), nameCaptor.capture());
        return policyCaptor.getValue();
    }

    private static com.fasterxml.jackson.databind.JsonNode appConfig(String policyName,
        String groupName) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"appInfo\":{\"policyName\":\"" + policyName + "\",\"fileGroupName\":\""
                    + groupName + "\"}}");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
