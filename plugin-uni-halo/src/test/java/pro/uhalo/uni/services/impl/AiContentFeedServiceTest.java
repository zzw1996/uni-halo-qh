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
import pro.uhalo.uni.dto.AiContentFeedDTO;
import pro.uhalo.uni.dto.AiContentItemDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.utils.DigestConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

    /** 实时素材抓取（与 AI 日报同一条抓取链路） */
    @Mock
    private DigestFeedService digestFeedService;

    /** 生成记录（审计 + 今日次数计数）：测试里按「空仓库」用 */
    @Mock
    private AiContentGenerationLogService logService;

    /** 关键词联网检索：默认「没开检索」（既有用例锁的是内置源那条路） */
    @Mock
    private AiContentSearchService searchService;

    @Mock
    private pro.uhalo.uni.utils.RateLimiter rateLimiter;

    @Mock
    private pro.uhalo.uni.services.PrivilegeService privilegeService;

    @Mock
    private AiContentEntitlementService entitlementService;

    @Mock
    private AiContentPreferenceService preferenceService;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    private AiContentFeedService service;

    /** 内存里的简报记录（见 {@link #stubIssueStore()}） */
    private final java.util.Map<String, AiContentIssue> issues = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new AiContentFeedService(client, digestPublicService, aiClient,
            digestFeedService, entitlementService, preferenceService, logService, searchService,
            rateLimiter, privilegeService, settingFetcher);
        stubIssueStore();
        // 默认：非特权（按次数计费）、限流放行、剩余次数充足、今日还没生成、日志写入成功
        lenient().when(privilegeService.isPrivileged(anyString())).thenReturn(Mono.just(false));
        lenient().when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(),
            any())).thenReturn(true);
        lenient().when(entitlementService.loadCredits(anyString()))
            .thenReturn(Mono.just(5));
        lenient().when(entitlementService.consumeCredit(anyString()))
            .thenReturn(Mono.just(4));
        lenient().when(logService.countToday(anyString())).thenReturn(Mono.just(0));
        // 检索默认返回空：需要它的用例自己 stub（例如「关键词检索结果作为素材」那条）
        lenient().when(searchService.search(any(), any(), any())).thenReturn(Mono.just(List.of()));
        lenient().when(logService.write(anyString(), anyString(), anyString(), anyString(),
                any(), org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any()))
            .thenReturn(Mono.empty());
        stubConfig(enabled(true));
        stubAiConfig();
        stubActive(true);
        // 默认「实时抓不到素材」：下面大多数用例锁的是兜底路径（退回站内日报内容）
        stubLiveArticles();
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
            .maxKeywords(10)
            .maxItems(3)
            .liveHours(48)
            .windowDays(7)
            .introWords(200)
            .outroWords(120)
            .keywordSearchEnabled(true)
            .keywordSearchProviders(List.of("bing"))
            .keywordSearchHours(72)
            .keywordSearchMaxItems(20)
            .keywordSearchCoverEnabled(false)
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

    /**
     * 极简内存仓库：create/update 写进来、fetch 按资源名读出去。
     *
     * <p>生成改成「先落一条 RUNNING、跑完再更新成 READY」之后，静态 stub（创建成功但读不回来）
     * 会让用例得出与现实相反的结论——所以这里按真实读写行为模拟。</p>
     */
    private void stubIssueStore() {
        lenient().when(client.create(any(AiContentIssue.class))).thenAnswer(invocation -> {
            var issue = invocation.getArgument(0, AiContentIssue.class);
            issues.put(issue.getMetadata().getName(), issue);
            return Mono.just(issue);
        });
        lenient().when(client.update(any(AiContentIssue.class))).thenAnswer(invocation -> {
            var issue = invocation.getArgument(0, AiContentIssue.class);
            issues.put(issue.getMetadata().getName(), issue);
            return Mono.just(issue);
        });
        lenient().when(client.fetch(eq(AiContentIssue.class), anyString())).thenAnswer(invocation -> {
            var name = invocation.getArgument(1, String.class);
            return issues.containsKey(name)
                ? Mono.just(issues.get(name))
                : Mono.error(new IllegalStateException("not found: " + name));
        });
        lenient().when(client.listAll(eq(AiContentIssue.class), any(), any()))
            .thenAnswer(invocation -> Flux.fromIterable(new java.util.ArrayList<>(issues.values())));
    }

    /** 预置若干记录（按 openid+日期派生资源名，与生产代码同一口径） */
    private void stubIssues(AiContentIssue... preset) {
        for (var issue : preset) {
            var name = AiContentFeedService.resourceName(
                issue.getWxOpenid() == null ? "openid-1" : issue.getWxOpenid(), issue.getDate());
            if (issue.getMetadata() == null) {
                issue.setMetadata(new run.halo.app.extension.Metadata());
            }
            issue.getMetadata().setName(name);
            issues.put(name, issue);
        }
    }

    private void stubCandidates(DigestPublicArticleDTO... articles) {
        when(digestPublicService.candidateArticles(any(Integer.class)))
            .thenReturn(Mono.just(List.of(articles)));
    }

    /** 实时素材池：抓取结果（默认空 = 抓不到，用例据此走兜底路径） */
    private void stubLiveArticles(DigestArticleDTO... articles) {
        lenient().when(digestFeedService.fetchRecent(any(Integer.class)))
            .thenReturn(Mono.just(DigestFeedResultDTO.builder()
                .articles(List.of(articles))
                .build()));
    }

    private static DigestArticleDTO liveArticle(String title, String source, int hoursAgo) {
        var article = new DigestArticleDTO();
        article.setTitle(title);
        article.setLink("https://example.com/" + title);
        article.setSourceName(source);
        article.setDescription(title + " 的摘要");
        article.setPubDate(Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
        return article;
    }

    /** 兼容旧用例名：写入由 {@link #stubIssueStore()} 负责，这里不需要额外动作 */
    private void stubCreate() {
        // no-op
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

    /**
     * 触发一次生成并等它跑完，返回最终读到的简报。
     *
     * <p>生成是「后台跑」的（页面退出也不中断），所以测试里用 {@code ensureToday} 加入同一个
     * 任务并等它结束——它与手动生成共用同一份 inFlight，不会另起一次 AI 调用。</p>
     */
    private Mono<AiContentFeedDTO> generateAndWait(String openid) {
        return service.startGeneration(openid)
            .then(service.ensureToday(openid))
            .then(service.feed(openid, null));
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

    /**
     * 用户点「生成」：一次 AI 调用完成挑选与改写 → 落库为 READY → 再读就能拿到内容。
     *
     * <p>注意入口是 {@code startGeneration}（用户显式触发），不是打开页面。</p>
     */
    @Test
    void generationShouldFilterThenCallAiOnceAndPersist() {
        stubIssues();
        stubCandidates(
            article("命中的", "engineering", "src", List.of("rust")),
            article("不命中的", "opinion", "other", List.of("life")));
        stubCreate();
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"今天有两篇值得看\",\"picks\":[{\"index\":0,\"reason\":\"先看这篇\"}]}"));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.isActive()).isTrue();
                assertThat(feed.getStatus()).isEqualTo("READY");
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

        StepVerifier.create(generateAndWait("openid-1"))
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

        StepVerifier.create(generateAndWait("openid-1"))
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

        StepVerifier.create(generateAndWait("openid-1"))
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

        StepVerifier.create(generateAndWait("openid-1"))
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

        StepVerifier.create(generateAndWait("openid-1"))
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
            var issue = invocation.getArgument(0, AiContentIssue.class);
            issues.put(issue.getMetadata().getName(), issue);
            return Mono.just(issue);
        });
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("{\"intro\":\"导语\",\"picks\":[]}")
                .delayElement(java.time.Duration.ofMillis(50)));

        var first = service.ensureToday("openid-1");
        var second = service.ensureToday("openid-1");

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

    /**
     * 结果必须写回<b>那条「生成中」的记录</b>，而不是新建一条。
     *
     * <p>这是「先落一条 RUNNING、跑完再更新」这条链路最容易写错的地方：
     * 若在这里 create，会撞名并回读到那条没有内容的 RUNNING 记录——生成结果白丢，
     * 用户还会一直看到进度条。</p>
     */
    @Test
    void resultShouldUpdateTheRunningRecordInsteadOfCreatingAnother() {
        var today = LocalDate.now(ZONE).toString();
        var running = existingIssue(today, "");
        running.setStatus("RUNNING");
        running.setStage("正在抓取最新素材…");
        running.setProgress(5);
        stubIssues(running);
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"导语\",\"picks\":[{\"index\":0,\"reason\":\"值得看\"}]}"));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("READY");
                assertThat(feed.getIntro()).isEqualTo("导语");
                assertThat(feed.getItems()).hasSize(1);
            })
            .verifyComplete();

        assertThat(issues).as("同一天只应有一条记录").hasSize(1);
        verify(client, never()).create(any(AiContentIssue.class));
    }

    // ── 实时素材（主路径） ──

    /**
     * 有实时素材时走实时路径：一次 AI 调用同时完成挑选与中文改写，
     * 条目带上链接与发布时间——用户拿到的必须是「今天最新」，而不是几天前的日报旧文。
     */
    @Test
    void liveArticlesShouldBePickedByAiWithChineseTitles() {
        stubIssues();
        stubCreate();
        stubLiveArticles(
            liveArticle("Gemini 4 ships with 19 benchmarks", "TechCrunch", 2),
            liveArticle("Rust 1.95 released", "Rust Blog", 5));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply("""
            {"intro":"今天这条围绕大模型与语言更新展开",
             "picks":[{"index":1,"title":"Rust 1.95 发布","summary":"新版本发布",
                       "reason":"与你关注的工程实践相关","category":"engineering"}]}
            """));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getLiveHours()).isEqualTo(48);
                assertThat(feed.getCandidateCount()).isEqualTo(2);
                assertThat(feed.getItems()).hasSize(1);
                var item = feed.getItems().get(0);
                // 标题用 AI 写的中文；事实（链接/时间）来自原素材
                assertThat(item.getTitle()).isEqualTo("Rust 1.95 发布");
                assertThat(item.getReason()).isEqualTo("与你关注的工程实践相关");
                assertThat(item.getLink()).isEqualTo("https://example.com/Rust 1.95 released");
                assertThat(item.getPublishedAt()).isNotBlank();
                assertThat(item.getCategory()).isEqualTo("engineering");
                // 已发布的日报内容不该再被用到
                assertThat(item.getPostName()).isNull();
            })
            .verifyComplete();

        verify(aiClient, times(1)).call(any(), anyString(), any());
        verify(digestPublicService, never()).candidateArticles(any(Integer.class));
    }

    /** 实时素材池是全站共享缓存：同一位用户之外的另一位用户不会再抓一次（成本阀门的另一半） */
    @Test
    void livePoolShouldBeSharedAcrossUsers() {
        stubIssues();
        stubCreate();
        stubLiveArticles(liveArticle("A new thing", "Src", 1));
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("{\"intro\":\"\",\"picks\":[]}"));

        StepVerifier.create(service.ensureToday("openid-1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.ensureToday("openid-2")).expectNextCount(1).verifyComplete();

        // 抓取只发生一次；AI 仍是每人各一次（这是设计里的成本口径）
        verify(digestFeedService, times(1)).fetchRecent(any(Integer.class));
        verify(aiClient, times(2)).call(any(), anyString(), any());
    }

    /** AI 挑选失败：退化为关键词命中的最新几条，仍然不是空简报 */
    @Test
    void livePickFailureShouldFallBackToKeywordMatch() {
        stubIssues();
        stubCreate();
        stubLiveArticles(
            liveArticle("Rust 1.95 released", "Rust Blog", 3),
            liveArticle("Football transfer news", "Sports", 4));
        lenient().when(preferenceService.load(anyString()))
            .thenReturn(Mono.just(AiContentPreferenceDTO.builder()
                .enabled(true)
                .categories(List.of())
                .keywords(List.of("rust"))
                .configured(true)
                .build()));
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(Mono.error(new IllegalStateException("timeout")));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getItems()).hasSize(1);
                // 兜底时标题保持原文（没有 AI 可改写），摘要取抓取到的原文摘要
                assertThat(feed.getItems().get(0).getTitle()).isEqualTo("Rust 1.95 released");
                assertThat(feed.getItems().get(0).getSummary()).contains("摘要");
            })
            .verifyComplete();
    }

    /** 站长关掉 AI：仍然从实时素材里取（最新优先），但不调用模型 */
    @Test
    void disabledIntroShouldStillUseLiveArticles() {
        stubConfig(enabled(false));
        stubIssues();
        stubCreate();
        stubLiveArticles(liveArticle("最新的那条", "Src", 1));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getLiveHours()).isEqualTo(48);
                assertThat(feed.getItems()).hasSize(1);
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    // ── 新语义：打开不生成、用户触发、进度可读 ──

    /**
     * 打开页面不生成：没有内容就如实给 NONE。
     *
     * <p>既烧不了 AI 额度，也不会凭空出现一份用户没要过的简报——那正是这轮反馈里
     * 最刺眼的一条。</p>
     */
    @Test
    void openingThePageShouldNotGenerate() {
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        stubCreate();

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("NONE");
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.getSummary()).contains("还没有生成今天的简报");
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(client, never()).create(any(AiContentIssue.class));
    }

    /** 触发生成立刻返回 RUNNING：进度与阶段写进记录，前端轮询就能显示 */
    @Test
    void startGenerationShouldReturnRunningImmediately() {
        stubIssues();
        stubCreate();
        stubCandidates();

        StepVerifier.create(service.startGeneration("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("RUNNING");
                assertThat(feed.getStage()).isNotBlank();
                assertThat(feed.getProgress()).isGreaterThan(0);
                assertThat(feed.getItems()).isEmpty();
            })
            .verifyComplete();
    }

    /**
     * 已有内容时再点生成：状态回到 RUNNING（页面显示进度），跑完后内容换成新的一版。
     *
     * <p>线上报过「改完关键词点生成只看到『今日简报已是最新』」——根因是按下「记录里已有内容」
     * 就跳过重新生成。这里把正确的行为钉住：不管当天有没有生成过，用户点了就是要重新生成。</p>
     */
    @Test
    void regeneratingShouldShowProgressThenReplaceContent() {
        var today = LocalDate.now(ZONE).toString();
        var ready = existingIssue(today, "上一版的导语");
        ready.setStatus("READY");
        stubIssues(ready);
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"重新生成的新导语\",\"picks\":[{\"index\":0,\"reason\":\"值得看\"}]}"));

        // 触发的瞬间必须是 RUNNING（前端据此显示进度条，而不是「已是最新」）
        StepVerifier.create(service.startGeneration("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("RUNNING");
                assertThat(feed.getItems()).as("生成中不显示上一版内容").isEmpty();
            })
            .verifyComplete();

        // 等这一次跑完（加入同一个在途任务，不会再扣一次）
        StepVerifier.create(service.ensureToday("openid-1", "push")
                .then(service.feed("openid-1", null)))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("READY");
                assertThat(feed.getIntro()).isEqualTo("重新生成的新导语");
                assertThat(feed.getItems()).hasSize(1);
            })
            .verifyComplete();

        verify(aiClient, times(1)).call(any(), anyString(), any());
        verify(entitlementService, times(1)).consumeCredit("openid-1");
    }

    /**
     * 卡住的 RUNNING（服务重启让在途任务消失）要按失败暴露出来。
     *
     * <p>否则用户永远盯着一动不动的进度条，连重新生成的入口都没有。</p>
     */
    /** 卡住的 RUNNING 但里面已经有内容：照常显示内容（内容是真的，不该把它藏起来） */
    @Test
    void staleRunningWithContentShouldStillShowContent() {
        var today = LocalDate.now(ZONE).toString();
        var stuck = existingIssue(today, "上一版的导语");
        stuck.setStatus("RUNNING");
        stuck.setUpdatedAt(Instant.now().minus(java.time.Duration.ofMinutes(30)));
        stubIssues(stuck);

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("READY");
                assertThat(feed.getIntro()).isEqualTo("上一版的导语");
            })
            .verifyComplete();
    }

    @Test
    void staleRunningShouldSurfaceAsFailed() {
        var today = LocalDate.now(ZONE).toString();
        var stuck = existingIssue(today, "");
        stuck.setStatus("RUNNING");
        stuck.setStage("AI 正在挑选与撰写…");
        stuck.setProgress(60);
        stuck.setUpdatedAt(Instant.now().minus(java.time.Duration.ofMinutes(30)));
        stubIssues(stuck);

        StepVerifier.create(service.feed("openid-1", null))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("FAILED");
                assertThat(feed.getError()).isNotBlank();
            })
            .verifyComplete();
    }

    // ── 生成额度：随时可生成，但按次数与每日上限计费 ──

    /**
     * 今天已经生成过，用户再点一次生成<b>仍然重新生成</b>（并再扣一次次数）。
     *
     * <p>线上反馈过「选了分类点生成却提示今日简报已是最新」——用户买了权益就期待随时能生成，
     * 那条「已生成就跳过」的规则是对「每人每天 1 次」时代的遗留。</p>
     */
    @Test
    void manualGenerationShouldAlwaysRegenerate() {
        var today = LocalDate.now(ZONE).toString();
        var ready = existingIssue(today, "上一版的导语");
        ready.setStatus("READY");
        stubIssues(ready);
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"新一版导语\",\"picks\":[{\"index\":0,\"reason\":\"值得看\"}]}"));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("READY");
                assertThat(feed.getIntro()).isEqualTo("新一版导语");
            })
            .verifyComplete();

        verify(aiClient, times(1)).call(any(), anyString(), any());
        verify(entitlementService, times(1)).consumeCredit("openid-1");
    }

    /** 次数用完：明确拒绝，并告知怎么获得更多（而不是静默失败） */
    @Test
    void outOfCreditsShouldRejectWithReason() {
        stubIssues();
        when(entitlementService.loadCredits("openid-1")).thenReturn(Mono.just(0));

        StepVerifier.create(service.startGeneration("openid-1"))
            .expectErrorSatisfies(err -> assertThat(err.getMessage()).contains("生成次数已用完"))
            .verify();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(entitlementService, never()).consumeCredit(anyString());
    }

    /** 今日已达上限：与总额度分开管，挡住「一天之内一直在输入一直在调用 AI」 */
    @Test
    void dailyLimitShouldRejectWhenReached() {
        stubIssues();
        stubConfig(AiContentConfigDTO.builder()
            .enabled(true).aiIntroEnabled(true).maxItems(3).liveHours(48).windowDays(7)
            .creditsPerDay(2).maxCreditsPerGrant(100).dailyGenerationLimit(1).build());
        when(entitlementService.loadCredits("openid-1")).thenReturn(Mono.just(9));
        when(logService.countToday("openid-1")).thenReturn(Mono.just(1));

        StepVerifier.create(service.startGeneration("openid-1"))
            .expectErrorSatisfies(err -> assertThat(err.getMessage()).contains("今天的生成次数已经用完"))
            .verify();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /** 特权用户（站长/管理员）：不扣次数、也不看每日上限 */
    @Test
    void privilegedUserShouldBypassGenerationQuota() {
        stubIssues();
        stubCandidates();
        when(privilegeService.isPrivileged("openid-1")).thenReturn(Mono.just(true));

        StepVerifier.create(service.startGeneration("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.getStatus()).isEqualTo("RUNNING");
                assertThat(feed.getRemainingGenerations()).isEqualTo(-1);
            })
            .verifyComplete();

        verify(entitlementService, never()).consumeCredit(anyString());
        verify(entitlementService, never()).loadCredits(anyString());
    }

    /** 推送线：当天已有内容就直接复用，不再烧 AI、不再扣次数 */
    @Test
    void pushShouldReuseTodaysBriefingWithoutCharging() {
        var today = LocalDate.now(ZONE).toString();
        var ready = existingIssue(today, "已经生成好的导语");
        ready.setStatus("READY");
        stubIssues(ready);

        StepVerifier.create(service.ensureToday("openid-1", "push"))
            .assertNext(issue -> assertThat(issue.getIntro()).isEqualTo("已经生成好的导语"))
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
        verify(entitlementService, never()).consumeCredit(anyString());
    }

    /** 每次生成都要留审计记录：谁触发的、生成了什么、扣了几次 */
    @Test
    void generationShouldWriteAuditLog() {
        stubIssues();
        stubCandidates(article("命中的", "engineering", "src", List.of()));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"intro\":\"导语\",\"picks\":[{\"index\":0,\"reason\":\"值得看\"}]}"));

        StepVerifier.create(generateAndWait("openid-1")).expectNextCount(1).verifyComplete();

        verify(logService).write(org.mockito.ArgumentMatchers.eq("openid-1"),
            org.mockito.ArgumentMatchers.eq(LocalDate.now(ZONE).toString()),
            org.mockito.ArgumentMatchers.eq("manual"),
            org.mockito.ArgumentMatchers.eq("READY"),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── 关键词联网检索：内容必须与用户输入相关 ──

    /**
     * 用户输入了关键词：先联网检索，检索结果作为素材第一顺位送进 AI 提示词。
     *
     * <p>线上反馈过「输入邓紫棋，生成的内容跟关键词毫无关联」——根因是素材池只有内置科技源，
     * 连一条相关报道都没有。这里钉住「检索到的报道必须出现在提示词里」。</p>
     */
    @Test
    void keywordSearchResultsShouldFeedThePrompt() {
        stubIssues();
        stubCandidates();
        stubLiveArticles(liveArticle("Tech news of the day", "TechCrunch", 1));
        lenient().when(preferenceService.load(anyString())).thenReturn(Mono.just(
            AiContentPreferenceDTO.builder()
                .enabled(true).keywords(List.of("邓紫棋")).configured(true).build()));
        when(searchService.search(any(), any(), any())).thenReturn(Mono.just(List.of(
            AiContentItemDTO.builder()
                .title("邓紫棋世界巡演官宣")
                .link("https://news.example/gem")
                .summary("巡演首站定在广州")
                .sourceName("网易娱乐")
                .publishedAt("10-08 09:00")
                .build())));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply("""
            {"intro":"围绕她的巡演","outro":"这类消息值得看的是档期与场馆",
             "picks":[{"index":0,"title":"邓紫棋巡演官宣","summary":"首站广州",
                       "reason":"与你的关键词直接相关","category":""}]}
            """));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.isSearched()).isTrue();
                assertThat(feed.getSummary()).contains("联网检索");
                assertThat(feed.getOutro()).isEqualTo("这类消息值得看的是档期与场馆");
                assertThat(feed.getItems()).hasSize(1);
                assertThat(feed.getItems().get(0).getTitle()).isEqualTo("邓紫棋巡演官宣");
            })
            .verifyComplete();

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).call(any(), promptCaptor.capture(), any());
        var prompt = promptCaptor.getValue();
        assertThat(prompt).contains("邓紫棋世界巡演官宣");
        assertThat(prompt).contains("围绕读者自己输入的关键词联网检索");
    }

    /** 检索一条都没命中：素材池回落到内置源，说明文案不能写成「联网检索到 N 条」 */
    @Test
    void emptySearchShouldFallBackToBuiltInSources() {
        stubIssues();
        stubCandidates();
        stubLiveArticles(liveArticle("Tech news", "TechCrunch", 1));
        lenient().when(preferenceService.load(anyString())).thenReturn(Mono.just(
            AiContentPreferenceDTO.builder()
                .enabled(true).keywords(List.of("邓紫棋")).configured(true).build()));
        when(searchService.search(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("{\"intro\":\"\",\"outro\":\"\",\"picks\":[]}"));

        StepVerifier.create(generateAndWait("openid-1"))
            .assertNext(feed -> {
                assertThat(feed.isSearched()).isFalse();
                assertThat(feed.getSummary()).doesNotContain("联网检索");
            })
            .verifyComplete();
    }
}