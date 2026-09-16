package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestPostPayloadDTO;
import pro.uhalo.uni.dto.DigestPostResultDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.services.DigestProgressTracker;
import pro.uhalo.uni.services.DigestRenderer;
import pro.uhalo.uni.services.HaloPostPublisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「只生成不发布」之后再点发布的链路。
 *
 * <p>这是线上踩过的坑：发布内容在生成阶段就固化在报告的 postPayloads 里，
 * 没有发布过的记录第一次点「发布」必须能按篇建稿，已经建过稿的必须只重新触发发布。</p>
 */
@ExtendWith(MockitoExtension.class)
class DigestServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPORT_NAME = "digest-2026-09-12-1205";

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestPersistence persistence;

    @Mock
    private DigestFeedService feedService;

    @Mock
    private DigestAiClient aiClient;

    @Mock
    private DigestAssetUploader assetUploader;

    @Mock
    private HaloPostPublisher publisher;

    private DigestServiceImpl service;

    private DigestProgressTracker progressTracker;

    @BeforeEach
    void setUp() throws Exception {
        progressTracker = new DigestProgressTracker();
        service = new DigestServiceImpl(settingFetcher, persistence, feedService, aiClient,
            new DigestRenderer(), assetUploader, publisher, progressTracker);
        when(settingFetcher.get("aiDigestConfig")).thenReturn(Mono.just(MAPPER.readTree("""
            {"enabled":true,"publishMode":"per-article","publishEnabled":true,
             "postOwner":"admin","postTitleTemplate":"AI 日报 · {date}","publishWaitSeconds":600}
            """)));
    }

    private static DigestReport reportWithPayloads(String payloadsJson, String postsJson) {
        var report = new DigestReport();
        report.setDate("2026-09-12");
        report.setSlot("12:05");
        report.setStatus("SUCCESS");
        report.setPublishState("SKIPPED");
        report.setMarkdown("# 日报正文");
        report.setPostPayloads(payloadsJson);
        report.setPosts(postsJson);
        var metadata = new Metadata();
        metadata.setName(REPORT_NAME);
        report.setMetadata(metadata);
        return report;
    }

    private static String payloadsJson() throws Exception {
        return MAPPER.writeValueAsString(List.of(
            DigestPostPayloadDTO.builder().title("第一篇").slug("a-1").markdown("# 第一篇").build(),
            DigestPostPayloadDTO.builder().title("第二篇").slug("b-2").markdown("# 第二篇").build()));
    }

    private static HaloPostPublishResultDTO published(String postName) {
        return HaloPostPublishResultDTO.builder()
            .postName(postName)
            .permalink("https://blog.example.com/archives/" + postName)
            .state("PUBLISHED")
            .message("已发布")
            .build();
    }

    @Test
    void publishShouldCreatePostsForPreparedPayloadsWhenNeverPublished() throws Exception {
        // 「只生成不发布」留下的记录：有备好的内容、没有任何建稿结果
        var report = reportWithPayloads(payloadsJson(), "[]");
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.just(published("post-a")), Mono.just(published("post-b")));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        // 两篇都要建稿发布，而不是合并成一篇
        verify(publisher, times(2)).publish(any(HaloPostPublishDTO.class), anyInt());
        verify(publisher, never()).republish(anyString(), anyInt());
        assertThat(report.getPublishState()).isEqualTo("PUBLISHED");
        assertThat(report.getPostCount()).isEqualTo(2);
        assertThat(report.getPostName()).isEqualTo("post-a");
        var results = MAPPER.readValue(report.getPosts(), List.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void publishShouldClearPreviousFailureReasonBeforeStarting() throws Exception {
        // 点发布之后，页面上不该还挂着上一次的失败原因
        var report = reportWithPayloads(payloadsJson(), "[]");
        report.setPublishState("FAILED");
        report.setError("上一次失败：HTTP 403 令牌无权限");
        // 每次写入都要按当时的快照记录状态（mock 返回同一个实例，事后读会拿到最终值）
        var writes = new java.util.ArrayList<String>();
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(persistence.update(any(DigestReport.class))).thenAnswer(invocation -> {
            var written = invocation.getArgument(0, DigestReport.class);
            writes.add(written.getPublishState() + "|" + written.getError());
            return Mono.just(written);
        });
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.just(published("post-a")), Mono.just(published("post-b")));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        assertThat(writes).hasSize(2);
        assertThat(writes.get(0)).isEqualTo("PUBLISHING|null");
        assertThat(writes.get(1)).isEqualTo("PUBLISHED|null");
    }

    @Test
    void publishShouldReportPerPostProgress() throws Exception {
        var report = reportWithPayloads(payloadsJson(), "[]");
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.just(published("post-a")), Mono.just(published("post-b")));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        var progress = progressTracker.snapshot();
        assertThat(progress.getStage()).isEqualTo(DigestProgressTracker.STAGE_PUBLISH);
        assertThat(progress.getTotal()).isEqualTo(2);
        assertThat(progress.getDone()).isEqualTo(2);
        assertThat(progress.getMessage()).contains("发布完成");
    }

    @Test
    void publishShouldOnlyRetriggerAlreadyCreatedPosts() throws Exception {
        // 上次发了一半：已建稿的那篇只重新触发发布，不重复建稿
        var reports = MAPPER.writeValueAsString(List.of(
            DigestPostResultDTO.builder().title("第一篇").postName("post-a").state("PENDING")
                .build(),
            DigestPostResultDTO.builder().title("第二篇").state("FAILED").message("超时").build()));
        var report = reportWithPayloads(payloadsJson(), reports);
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(publisher.republish("post-a", 600)).thenReturn(Mono.just(published("post-a")));
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.just(published("post-b")));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        verify(publisher).republish("post-a", 600);
        verify(publisher).publish(any(HaloPostPublishDTO.class), anyInt());
        assertThat(report.getPublishState()).isEqualTo("PUBLISHED");
        assertThat(report.getPostCount()).isEqualTo(2);
    }

    @Test
    void publishShouldRecordFailureSoTheAdminPageCanShowIt() throws Exception {
        var report = reportWithPayloads(payloadsJson(), "[]");
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("HTTP 403 令牌无权限")));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        // 失败也要落回记录，否则页面上看就是「点了没反应」（先写「发布中」，再写结果）
        verify(persistence, times(2)).update(any(DigestReport.class));
        assertThat(report.getPublishState()).isEqualTo("FAILED");
        assertThat(report.getError()).contains("HTTP 403");
        assertThat(report.getPosts()).contains("第一篇");
    }

    @Test
    void publishShouldFallBackToDigestBodyForLegacyRecords() throws Exception {
        // 发布内容固化之前生成的旧记录：只有正文，没有 postPayloads
        var report = reportWithPayloads("[]", "[]");
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));
        when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenReturn(Mono.just(published("post-legacy")));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.publishReport(REPORT_NAME)).expectNextCount(1).verifyComplete();

        verify(publisher).publish(any(HaloPostPublishDTO.class), anyInt());
        assertThat(report.getPublishState()).isEqualTo("PUBLISHED");
        // 补发出去的正文要写回 payloads，之后再点发布就不会再走这条兜底
        assertThat(report.getPostPayloads()).contains("AI 日报 · 2026-09-12");
    }

    @Test
    void publishShouldFailFastWhenThereIsNothingToPublish() throws Exception {
        var report = reportWithPayloads("[]", "[]");
        report.setMarkdown("");
        report.setError("上一次失败");
        when(persistence.fetch(REPORT_NAME)).thenReturn(Mono.just(report));

        StepVerifier.create(service.publishReport(REPORT_NAME))
            .expectErrorMessage("该日报既没有备好的发布内容，也没有可发布的正文")
            .verify();

        verify(publisher, never()).publish(any(HaloPostPublishDTO.class), anyInt());
        // 没有可发内容时不该改动记录（尤其不能把状态改成「发布中」把旧原因清掉）
        verify(persistence, never()).update(any(DigestReport.class));
        assertThat(report.getPublishState()).isNotEqualTo("PUBLISHING");
        assertThat(report.getError()).isEqualTo("上一次失败");
    }
    // ==================== 重试复用同一条记录 ====================
    //
    // 资源名按「日期 + 时段」固定，一个时段只有一条记录。重试若还去 create 就会撞「已存在」，
    // 于是重试从未真正执行、报告条数永远是 1，而调度器正是靠这条记录上的尝试次数
    // 来决定「还要不要再试」——三者叠在一起，就成了线上那个「一直不停开始抓取 RSS 源」。

    /** run() 内部的日期按时区算，这里必须用同一口径，否则 fetch 的桩对不上 */
    private static String todayInConfiguredZone() {
        return java.time.ZonedDateTime.now(
                pro.uhalo.uni.utils.DigestConfigHelper.zoneOf(null))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private static DigestReport existingReport(String status, int attempts) {
        var report = new DigestReport();
        report.setDate(todayInConfiguredZone());
        report.setSlot("07:00");
        report.setStatus(status);
        report.setAttempts(attempts);
        report.setStartedAt(Instant.now().minusSeconds(1800));
        var metadata = new Metadata();
        metadata.setName(DigestPersistence.resourceName(todayInConfiguredZone(), "07:00"));
        report.setMetadata(metadata);
        return report;
    }

    /** 已有同日同时段的记录时必须复用它（update），不再 create。 */
    @Test
    void runShouldReuseExistingReportForTheSameSlot() throws Exception {
        var existing = existingReport("FAILED", 1);
        when(persistence.fetch(
                DigestPersistence.resourceName(todayInConfiguredZone(), "07:00")))
            .thenReturn(Mono.just(existing));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // 让流水线在抓取阶段就失败：本用例只关心「记录是怎么来的」
        when(feedService.fetchRecent(anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("抓取失败")));

        StepVerifier.create(service.run("07:00", false))
            .assertNext(report -> {
                assertThat(report.getStatus()).as("失败要落在记录上").isEqualTo("FAILED");
                assertThat(report.getAttempts()).as("尝试次数要累加，调度器靠它判上限").isEqualTo(2);
            })
            .verifyComplete();

        // 关键：不能再新建（否则会撞「已存在」，重试永远不会真正执行）
        verify(persistence, never()).create(any(DigestReport.class));
        // update 会被调两次：一次重置为重试、一次落失败状态
        verify(persistence, org.mockito.Mockito.atLeastOnce()).update(any(DigestReport.class));
    }

    /** 没有旧记录时按第 1 次尝试新建。 */
    @Test
    void runShouldCreateReportOnFirstAttempt() throws Exception {
        when(persistence.fetch(any())).thenReturn(Mono.empty());
        when(persistence.create(any(DigestReport.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(feedService.fetchRecent(anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("抓取失败")));

        StepVerifier.create(service.run("07:00", false))
            .assertNext(report -> assertThat(report.getAttempts()).isEqualTo(1))
            .verifyComplete();

        verify(persistence).create(any(DigestReport.class));
    }

    /** 失败后进度必须收尾，不能一直停在「进行中」——页面就靠它。 */
    @Test
    void runShouldClearProgressOnFailure() throws Exception {
        when(persistence.fetch(any())).thenReturn(Mono.empty());
        when(persistence.create(any(DigestReport.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(persistence.update(any(DigestReport.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(feedService.fetchRecent(anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("抓取失败")));

        StepVerifier.create(service.run("07:00", false)).expectNextCount(1).verifyComplete();

        var snapshot = progressTracker.snapshot();
        assertThat(snapshot.isRunning()).as("失败后不能还显示「进行中」").isFalse();
        assertThat(snapshot.getStage()).isEqualTo("FAILED");
        assertThat(snapshot.getMessage()).contains("抓取失败");
    }
}
