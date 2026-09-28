package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.utils.DigestConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「我的 AI 简报」的生成：筛选、AI 导语、降级与成本控制。
 *
 * <p>这里钉住的是本功能最贵的那个数字——<b>每位有效用户每天最多 1 次 AI 调用</b>：
 * 当天已有简报就绝不重复调用；没有内容或站长关掉导语时一次都不调用；
 * 并发打开也只调用一次。同时保证任何 AI 失败都不会让简报打不开。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentFeedServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private DigestPublicService digestPublicService;

    @Mock
    private DigestAiClient aiClient;

    @Mock
    private AiContentEntitlementService entitlementService;

    @Mock
    private AiContentPreferenceService preferenceService;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    private AiContentFeedService service;

    @BeforeEach
    void setUp() {
        service = new AiContentFeedService(client, digestPublicService, aiClient,
            entitlementService, preferenceService, settingFetcher);
        stubConfig(enabled(true));
        stubAiConfig();
        stubActive(true);
        lenient().when(preferenceService.touchIssueDate(anyString(), anyString()))
            .thenReturn(Mono.empty());
        lenient().when(preferenceService.load(anyString()))
            .thenReturn(Mono.just(AiContentPreferenceDTO.builder()
                .enabled(true)
                .categories(List.of("engineering"))
                .sources(List.of())
                .keywords(List.of())
                .configured(true)
                .build()));
    }

    private static AiContentConfigDTO enabled(boolean aiIntro) {
        return AiContentConfigDTO.builder()
            .enabled(true)
            .aiIntroEnabled(aiIntro)
            .maxCategories(5)
            .maxSources(5)
            .maxKeywords(10)
            .maxItems(3)
            .windowDays(7)
            .introWords(200)
            .build();
    }

    private void stubConfig(AiContentConfigDTO config) {
        when(entitlementService.loadConfig()).thenReturn(Mono.just(config));
    }

    private void stubActive(boolean active) {
        when(entitlementService.isActive(anyString())).thenReturn(Mono.just(active));
    }

    private void stubAiConfig() {
        try {
            when(settingFetcher.get(DigestConfigHelper.groupName()))
                .thenReturn(Mono.just(MAPPER.readTree("{\"aiApiKey\":\"sk-test\"}")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void stubIssues(AiContentIssue... issues) {
        when(client.listAll(any(), any(), any())).thenReturn(Flux.fromArray(issues));
    }

    private void stubCandidates(DigestPublicArticleDTO... articles) {
        when(digestPublicService.candidateArticles(any(Integer.class)))
            .thenReturn(Mono.just(List.of(articles)));
    }

    private void stubCreate() {
        when(client.create(any(AiContentIssue.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private static DigestPublicArticleDTO article(String title, String category, String source,
        List<String> keywords) {
        return DigestPublicArticleDTO.builder()
            .title(title)
            .category(category)
            .categoryLabel(category)
            .keywords(keywords)
            .sourceName(source)
            .postName("post-" + title)
            .summary("摘要")
            .build();
    }

    private static AiContentIssue existingIssue(String date, String intro) {
        var issue = new AiContentIssue();
        issue.setWxOpenid("openid-1");
        issue.setDate(date);
        issue.setIntro(intro);
        issue.setItems("[]");
        issue.setCandidateCount(0);
        issue.setMatchedCount(0);
        issue.setAiGenerated(true);
        return issue;
    }

    /** 未开通：不给内容，也绝不调用 AI */
    /** 这些链路只关心正文，用量给它一个「对方未上报」的壳即可 */
    private static Mono<DigestAiReplyDTO> aiReply(String text) {
        return Mono.just(DigestAiReplyDTO.of(text,
            new DigestAiProviderDTO("test", "测试通道", "https://test.example/v1", "sk-test",
                "test-model"), "test-model", DigestAiUsageDTO.unreported()));
    }

    @Test
    void inactiveEntitlementShouldReturnEmptyFeedWithoutAiCall() {
        stubActive(false);
        stubCandidates(article("A", "engineering", "src", List.of()));
        stubIssues();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.isActive()).isFalse();
                assertThat(feed.getItems()).isEmpty();
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(client, never()).create(any(AiContentIssue.class));
    }

    /** 功能关闭：即便账上有权益也不生成 */
    @Test
    void disabledFeatureShouldReturnDisabledFeed() {
        stubConfig(AiContentConfigDTO.builder().enabled(false).build());
        stubIssues();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> assertThat(feed.isEnabled()).isFalse())
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /** 当天已有简报：直接返回，不再调用 AI（成本阀门） */
    @Test
    void existingIssueShouldBeReturnedWithoutAiCall() {
        var today = LocalDate.now(ZONE).toString();
        stubIssues(existingIssue(today, "今天的导语"));

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getIntro()).isEqualTo("今天的导语");
                assertThat(feed.getDate()).isEqualTo(today);
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(client, never()).create(any(AiContentIssue.class));
    }

    /** 首次打开：筛选 + 一次 AI 调用 → 落库 → 带导语与推荐理由返回 */
    @Test
    void firstOpenShouldFilterThenCallAiOnceAndPersist() {
        stubIssues();
        stubCandidates(
            article("命中的", "engineering", "src", List.of("rust")),
            article("不命中的", "opinion", "other", List.of("life")));
        stubCreate();
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"今天有两篇值得看\",\"picks\":[{\"index\":0,\"reason\":\"先看这篇\"}]}"));

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.isActive()).isTrue();
                assertThat(feed.getIntro()).isEqualTo("今天有两篇值得看");
                assertThat(feed.isAiGenerated()).isTrue();
                assertThat(feed.getItems()).hasSize(1);
                assertThat(feed.getItems().get(0).getTitle()).isEqualTo("命中的");
                assertThat(feed.getItems().get(0).getReason()).isEqualTo("先看这篇");
                assertThat(feed.getItems().get(0).getMatchedReason()).contains("分类匹配");
                assertThat(feed.getCandidateCount()).isEqualTo(2);
            })
            .verifyComplete();

        verify(aiClient, times(1)).call(any(), anyString(), any());
        verify(client, times(1)).create(any(AiContentIssue.class));
    }

    /** AI 挂掉：降级为纯筛选结果，简报照常落库 */
    @Test
    void aiFailureShouldDegradeToPlainFilteredList() {
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        stubCreate();
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(Mono.error(new IllegalStateException("timeout")));

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getIntro()).isEmpty();
                assertThat(feed.isAiGenerated()).isFalse();
                assertThat(feed.getItems()).hasSize(1);
                assertThat(feed.getSummary()).contains("未生成导语");
            })
            .verifyComplete();

        verify(client, times(1)).create(any(AiContentIssue.class));
    }

    /** 模型返回非 JSON：同样是降级，不能让简报打不开 */
    @Test
    void nonJsonResponseShouldDegradeWithoutFailing() {
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        stubCreate();
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("抱歉，我无法完成这个请求"));

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getIntro()).isEmpty();
                assertThat(feed.getItems()).hasSize(1);
            })
            .verifyComplete();
    }

    /** 候选为空：一次 AI 都不调用，并给出可读的空态说明 */
    @Test
    void emptyCandidatesShouldSkipAiAndExplain() {
        stubIssues();
        stubCandidates();
        stubCreate();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.getSummary()).contains("还没有可筛的精选内容");
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(client, times(1)).create(any(AiContentIssue.class));
    }

    /** 站长关掉导语：纯筛选，零 AI 成本 */
    @Test
    void disabledIntroShouldNeverCallAi() {
        stubConfig(enabled(false));
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        stubCreate();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> assertThat(feed.getItems()).hasSize(1))
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /** 偏好太窄导致一条都没命中：说清原因，而不是留一片空白 */
    @Test
    void noMatchShouldExplainAndStillPersist() {
        stubConfig(enabled(false));
        stubIssues();
        stubCandidates(article("不命中的", "opinion", "other", List.of()));
        stubCreate();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.getSummary()).contains("没有命中你关注的方向");
            })
            .verifyComplete();
    }

    /** 历史日期只读：翻往期不该触发 AI 调用 */
    @Test
    void historicalDateShouldNotGenerate() {
        stubIssues();

        StepVerifier.create(service.feed("openid-1", "2020-01-01"))
            .assertNext(feed -> {
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.getDate()).isEqualTo("2020-01-01");
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(client, never()).create(any(AiContentIssue.class));
    }

    /** 并发打开两次：只生成一份、只调用一次 AI */
    @Test
    void concurrentOpensShouldGenerateOnlyOnce() {
        var calls = new AtomicInteger();
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        when(client.create(any(AiContentIssue.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Mono.just(invocation.getArgument(0));
        });
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("{\"intro\":\"导语\",\"picks\":[]}")
                .delayElement(java.time.Duration.ofMillis(50)));

        var first = service.feed("openid-1", null);
        var second = service.feed("openid-1", null);

        StepVerifier.create(Mono.zip(first, second))
            .assertNext(tuple -> {
                assertThat(tuple.getT1().getIntro()).isEqualTo("导语");
                assertThat(tuple.getT2().getIntro()).isEqualTo("导语");
            })
            .verifyComplete();

        assertThat(calls.get()).as("在途请求必须合并，否则就是双倍 AI 成本").isEqualTo(1);
        verify(aiClient, times(1)).call(any(), anyString(), any());
    }

    /** 关注方向改了：作废当天那份，让下次打开按新方向重算 */
    @Test
    void invalidateTodayShouldDeleteTheIssueOfToday() {
        var today = LocalDate.now(ZONE).toString();
        var existing = existingIssue(today, "旧方向的导语");
        when(client.fetch(eq(AiContentIssue.class), anyString())).thenReturn(Mono.just(existing));
        when(client.delete(any(AiContentIssue.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.invalidateToday("openid-1"))
            .expectNext(true)
            .verifyComplete();

        verify(client).delete(existing);
    }

    /** 当天还没生成过：不是错误，也不需要删除动作 */
    @Test
    void invalidateTodayShouldBeQuietWhenNothingToDelete() {
        when(client.fetch(eq(AiContentIssue.class), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("not found")));

        StepVerifier.create(service.invalidateToday("openid-1"))
            .expectNext(false)
            .verifyComplete();

        verify(client, never()).delete(any(AiContentIssue.class));
    }

    /** 落库失败（并发下已被写入）：回读复用，不报错 */
    @Test
    void createConflictShouldFallBackToReadBack() {
        var today = LocalDate.now(ZONE).toString();
        when(client.create(any(AiContentIssue.class)))
            .thenReturn(Mono.error(new IllegalStateException("AiContentIssue aci-xxx already exists")));
        when(client.listAll(any(), any(), any()))
            .thenReturn(Flux.empty())
            .thenReturn(Flux.just(existingIssue(today, "别人已经生成的那份")));
        stubCandidates();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> assertThat(feed.getIntro()).isEqualTo("别人已经生成的那份"))
            .verifyComplete();
    }
}
