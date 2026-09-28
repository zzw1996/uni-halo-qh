package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.services.DigestProgressTracker;
import pro.uhalo.uni.services.DigestRenderer;
import pro.uhalo.uni.services.HaloPostPublisher;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「每个分类至少几篇 + 配图兜底」的整条流水线行为。
 *
 * <p>用假的 RSS 与假的 AI 输出跑完整流程，验证的是站长真正能观察到的结果：
 * 发了几篇、分别是哪些分类、封面是哪来的、统计里怎么记录。</p>
 */
@ExtendWith(MockitoExtension.class)
class DigestPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 提示词里的文章条目：{@code Index 3: [来源] 标题} */
    private static final Pattern INDEX_PATTERN =
        Pattern.compile("Index (\\d+): \\[([^\\]]*)\\] ([^\\n]*)");

    private static final String IMAGE = "https://cdn.example.com/pic.jpg";

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestPersistence persistence;

    @Mock
    private DigestFeedService feedService;

    @Mock
    private DigestAiClient aiClient;

    /**
     * 日报只会在开头「顺手」触发一次官网单价读取：桩成空 Mono 即可，
     * 流水线本身不依赖它的结果（本次运行用内置价目表）。
     */
    @Mock
    private DigestAiModelService aiModelService;

    @Mock
    private DigestAssetUploader assetUploader;

    @Mock
    private HaloPostPublisher publisher;

    private DigestServiceImpl service;

    private static final DigestAiProviderDTO TEST_CHANNEL = new DigestAiProviderDTO("test",
        "测试通道", "https://test.example/v1", "sk-test", "deepseek-flash", true);

    private static final String TEST_MODEL = "deepseek-flash";

    private final List<String> prompts = new ArrayList<>();

    /** 每次调用声明的任务：用于钉住「哪一步算哪个任务」 */
    private final List<DigestAiTask> tasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new DigestServiceImpl(settingFetcher, persistence, feedService, aiClient,
            aiModelService, new DigestRenderer(), assetUploader, publisher,
            new DigestProgressTracker());
        lenient().when(settingFetcher.get("basic"))
            .thenReturn(Mono.just(MAPPER.createObjectNode().put("title", "测试站")));
        // 没有历史记录：首次 fetch 空，之后的保存都原样回写同一个实例
        lenient().when(persistence.fetch(anyString())).thenReturn(Mono.empty());
        lenient().when(persistence.create(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(persistence.update(any(DigestReport.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        // 头图解析在测试里视为「原样返回」（真实实现会去抓 og:image）
        lenient().when(feedService.resolveLeadImages(anyList()))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(aiClient.call(any(), anyString(), any())).thenAnswer(invocation -> {
            var prompt = invocation.getArgument(1, String.class);
            var task = invocation.getArgument(2, DigestAiTask.class);
            prompts.add(prompt);
            tasks.add(task);
            // 带上用量与通道信息：花费统计就是从这里累加的，桩里不给我就断不了言
            return Mono.just(DigestAiReplyDTO.of(aiResponse(prompt), TEST_CHANNEL, TEST_MODEL,
                new DigestAiUsageDTO(1000, 500, 1500, 0, 0, 0, true)));
        });
        lenient().when(assetUploader.uploadImage(anyString(), anyString(), any()))
            .thenAnswer(invocation -> Mono.just("https://blog.example.com/upload/"
                + invocation.getArgument(1, String.class) + ".jpg"));
        lenient().when(assetUploader.uploadBytes(any(), anyString(), anyString(), any()))
            .thenReturn(Mono.just("https://blog.example.com/upload/card.png"));
        lenient().when(publisher.publish(any(HaloPostPublishDTO.class), anyInt()))
            .thenAnswer(invocation -> {
                var dto = invocation.getArgument(0, HaloPostPublishDTO.class);
                return Mono.just(HaloPostPublishResultDTO.builder()
                    .postName("post-" + dto.getSlug())
                    .permalink("https://blog.example.com/archives/" + dto.getSlug())
                    .state("PUBLISHED")
                    .build());
            });
    }

    // ── 用例 ──

    @Test
    void quotaShouldPublishAtLeastOneArticlePerCategory() throws Exception {
        // 三个高分 ai-ml + 一个低分 security，精选只给 2 篇：改造前 security 会被挤掉
        configure(defaultConfig().put("topN", 2).put("minPerCategory", 1).toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("ai-ml", "bbbb", "https://a.example.com/2", IMAGE),
            article("ai-ml", "cccc", "https://a.example.com/3", IMAGE),
            article("security", "zzzz", "https://b.example.com/1", IMAGE)))));

        var report = run();

        assertThat(publishedTitles()).containsExactlyInAnyOrder("ai-ml:aaaa", "security:zzzz");
        var stats = statsOf(report);
        assertThat(stats.path("categoryCounts").path("ai-ml").asInt()).isEqualTo(1);
        assertThat(stats.path("categoryCounts").path("security").asInt()).isEqualTo(1);
        // 本期有素材的两个分类都必须覆盖到（其余三个分类时间窗内本来就没有文章）
        assertThat(missingKeys(stats)).doesNotContain("ai-ml", "security");
    }

    @Test
    void duplicateLinksShouldBeDroppedBeforeScoring() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 0).toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://www.example.com/post/a/?utm_source=rss", IMAGE),
            article("ai-ml", "bbbb", "http://example.com/post/a#comments", IMAGE),
            article("security", "zzzz", "https://b.example.com/1", IMAGE)))));

        var report = run();

        assertThat(publishedTitles()).containsExactlyInAnyOrder("ai-ml:aaaa", "security:zzzz");
        assertThat(statsOf(report).path("dedupedCount").asInt()).isEqualTo(1);
        // 去重发生在评分之前：只给两篇打分，不花第三次调用
        var scoringPrompt = prompts.stream().filter(prompt -> prompt.contains("relevance"))
            .findFirst().orElseThrow();
        assertThat(scoringPrompt).contains("Index 1:").doesNotContain("Index 2:");
    }

    @Test
    void missingCategoriesShouldTriggerWiderWindowFetch() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 1)
            .put("categoryFallbackEnabled", true).toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("ai-ml", "bbbb", "https://a.example.com/2", IMAGE)))));
        var older = article("security", "old", "https://b.example.com/old", IMAGE);
        older.setPubDate(Instant.now().minus(5, ChronoUnit.DAYS));
        when(feedService.fetchRecent(168))
            .thenReturn(Mono.just(feeds(List.of(older))));

        var report = run();

        verify(feedService).fetchRecent(168);
        assertThat(publishedTitles()).contains("security:old");
        var stats = statsOf(report);
        assertThat(stats.path("supplementCount").asInt()).isEqualTo(1);
        assertThat(stats.path("fallbackFetched").asInt()).isEqualTo(1);
        // 缺口里的 security 已被补上；另外三个分类补抓窗口里也没有文章，仍算缺口
        assertThat(missingKeys(stats)).doesNotContain("security", "ai-ml");
    }

    @Test
    void fallbackFetchFailureShouldNotBreakTheRun() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 1)
            .put("categoryFallbackEnabled", true).toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("ai-ml", "bbbb", "https://a.example.com/2", IMAGE)))));
        when(feedService.fetchRecent(168))
            .thenReturn(Mono.error(new IllegalStateException("补抓超时")));

        var report = run();

        assertThat(report.getStatus()).isEqualTo("SUCCESS");
        assertThat(publishedTitles()).containsExactlyInAnyOrder("ai-ml:aaaa", "ai-ml:bbbb");
    }

    @Test
    void articlesWithoutCoverShouldGetAGeneratedCard() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 1)
            .put("coverFallbackMode", "placeholder").toString());
        // 站点名读不到（设置组缺失）时也要照常出图，不能把整轮变成「没有结果」
        when(settingFetcher.get("basic")).thenReturn(Mono.empty());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("security", "zzzz", "https://b.example.com/1", null)))));

        var report = run();

        var posts = publishedPosts();
        var withImage = post(posts, "ai-ml:aaaa");
        var withCard = post(posts, "security:zzzz");
        assertThat(withImage.getCover()).isEqualTo("https://blog.example.com/upload/01-hn.jpg");
        assertThat(withCard.getCover()).isEqualTo("https://blog.example.com/upload/card.png");
        // 卡片只作封面：正文里不再重复插这张图
        assertThat(withCard.getMarkdown()).doesNotContain("card.png");
        verify(assetUploader).uploadBytes(any(), eq("image/png"), anyString(), any());
        assertThat(statsOf(report).path("placeholderCoverCount").asInt()).isEqualTo(1);
    }

    @Test
    void skipModeShouldDropArticlesWithoutCover() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 1)
            .put("coverFallbackMode", "skip").toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("security", "zzzz", "https://b.example.com/1", null)))));

        var report = run();

        assertThat(publishedTitles()).containsExactly("ai-ml:aaaa");
        verify(assetUploader, never()).uploadBytes(any(), anyString(), anyString(), any());
        // 被跳过的那篇所在的分类要如实记为缺口
        assertThat(missingKeys(statsOf(report))).contains("security");
    }

    @Test
    void noCoverModeShouldPublishWithoutCover() throws Exception {
        configure(defaultConfig().put("topN", 5).put("minPerCategory", 1)
            .put("coverFallbackMode", "no-cover").toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE),
            article("security", "zzzz", "https://b.example.com/1", null)))));

        run();

        assertThat(post(publishedPosts(), "security:zzzz").getCover()).isEmpty();
        verify(assetUploader, never()).uploadBytes(any(), anyString(), anyString(), any());
    }

    // ── 脚手架 ──

    private static com.fasterxml.jackson.databind.node.ObjectNode defaultConfig() {
        var node = MAPPER.createObjectNode();
        node.put("enabled", true);
        node.put("hours", 48);
        node.put("topN", 15);
        node.put("minPerCategory", 1);
        node.put("categoryFallbackEnabled", false);
        node.put("imageEnabled", true);
        node.put("requireImage", true);
        node.put("publishEnabled", true);
        node.put("publishMode", "per-article");
        node.put("postOwner", "admin");
        node.put("publishWaitSeconds", 600);
        return node;
    }

    /**
     * 每一步都要声明自己的任务。
     *
     * <p>任务决定「这次调用该走哪条通道」，声明错了不会报错，只会悄悄用错模型
     * （例如拿便宜模型去写成稿），所以这里把每个步骤的声明钉死。</p>
     */
    @Test
    void eachStepShouldDeclareItsOwnTask() throws Exception {
        configure(defaultConfig().put("topN", 1).put("minPerCategory", 0).toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE)))));

        run();

        // 逐条断言任务序列：评分在前，成稿随后，最后是看点
        assertThat(tasks).startsWith(DigestAiTask.SCORE);
        assertThat(tasks).containsSubsequence(DigestAiTask.SCORE, DigestAiTask.WRITE,
            DigestAiTask.OVERVIEW);
        assertThat(tasks).doesNotContain(DigestAiTask.CLASSIFY, DigestAiTask.CONTENT);
    }

    /**
     * 花费要落进 stats，并且按任务拆开。
     *
     * <p>估算的口径（单价 × token）在 DigestAiPricingTest 里钉；这里钉的是「流水线把用量记下来了」——
     * 漏记的话报告里会显示「本轮 0 次调用」，比不显示还糟。</p>
     */
    @Test
    void statsShouldCarryAiCostByTask() throws Exception {
        configure(defaultConfig().put("topN", 1).put("minPerCategory", 0).put("balanceCheckEnabled", false)
            .toString());
        when(feedService.fetchRecent(48)).thenReturn(Mono.just(feeds(List.of(
            article("ai-ml", "aaaa", "https://a.example.com/1", IMAGE)))));

        var report = run();

        var cost = statsOf(report).path("aiCost");
        assertThat(cost.path("calls").asInt()).isPositive();
        assertThat(cost.path("tasks")).isNotEmpty();
        var score = cost.path("tasks").get(0);
        assertThat(score.path("task").asText()).isEqualTo("score");
        assertThat(score.path("calls").asInt()).isPositive();
        assertThat(score.path("inputTokens").asInt()).isPositive();
        // 单价用的内置默认（deepseek-flash），所以金额必须算得出来
        assertThat(score.path("priced").asBoolean()).isTrue();
        assertThat(cost.path("totals")).isNotEmpty();
    }

    private void configure(String json) throws Exception {
        prompts.clear();
        when(settingFetcher.get("aiDigestConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private DigestReport run() {
        var report = service.run("07:00", true).block();
        assertThat(report).isNotNull();
        return report;
    }

    private static DigestArticleDTO article(String category, String label, String link,
        String imageUrl) {
        var dto = new DigestArticleDTO();
        // 标题写成「分类:标签」：假的 AI 输出按这个前缀回填分类，断言也按它认文章
        dto.setTitle(category + ":" + label);
        dto.setCategory(category);
        dto.setLink(link);
        dto.setSourceName("HN");
        dto.setPubDate(Instant.now().minus(1, ChronoUnit.HOURS));
        dto.setDescription("一篇测试文章的描述");
        dto.setImageUrl(imageUrl);
        return dto;
    }

    private static DigestFeedResultDTO feeds(List<DigestArticleDTO> articles) {
        return DigestFeedResultDTO.builder()
            .totalFeeds(92)
            .successFeeds(10)
            .articles(articles)
            .build();
    }

    /**
     * 假的模型输出：按提示词类型回答。
     *
     * <p>评分与摘要都按提示词里的 Index 逐条回填，正文里因此不会出现「模型漏项」的干扰；
     * 分类取标题里 {@code 分类:} 的前缀，这样测试能精确控制每篇落在哪个分类。</p>
     */
    private static String aiResponse(String prompt) {
        if (prompt.contains("relevance")) {
            var results = new ArrayList<String>();
            var matcher = INDEX_PATTERN.matcher(prompt);
            while (matcher.find()) {
                var title = matcher.group(3).trim();
                results.add("{\"index\":" + matcher.group(1)
                    + ",\"relevance\":9,\"quality\":9,\"timeliness\":9,\"category\":\""
                    + title.substring(0, title.indexOf(':')) + "\",\"keywords\":[\"k\"]}");
            }
            return "{\"results\":[" + String.join(",", results) + "]}";
        }
        if (prompt.contains("titleZh")) {
            var results = new ArrayList<String>();
            var matcher = INDEX_PATTERN.matcher(prompt);
            while (matcher.find()) {
                results.add("{\"index\":" + matcher.group(1)
                    + ",\"titleZh\":\"\",\"summary\":\"这段概述说明文章讲了什么。\","
                    + "\"points\":[\"核心论点一\"],\"reason\":\"值得一读\"}");
            }
            return "{\"results\":[" + String.join(",", results) + "]}";
        }
        return "【看点】今日看点\n【背景】背景铺垫";
    }

    private List<HaloPostPublishDTO> publishedPosts() {
        var captor = ArgumentCaptor.forClass(HaloPostPublishDTO.class);
        verify(publisher, atLeastOnce()).publish(captor.capture(), anyInt());
        return captor.getAllValues();
    }

    private List<String> publishedTitles() {
        return publishedPosts().stream().map(HaloPostPublishDTO::getTitle).toList();
    }

    private static HaloPostPublishDTO post(List<HaloPostPublishDTO> posts, String title) {
        return posts.stream().filter(post -> title.equals(post.getTitle())).findFirst()
            .orElseThrow(() -> new AssertionError("没有发出去的文章：" + title));
    }

    private static JsonNode statsOf(DigestReport report) throws Exception {
        return MAPPER.readTree(report.getStats());
    }

    /** 统计里的缺口分类 key 列表 */
    private static List<String> missingKeys(JsonNode stats) {
        var keys = new ArrayList<String>();
        stats.path("missingCategories").forEach(node -> keys.add(node.asText()));
        return keys;
    }
}
