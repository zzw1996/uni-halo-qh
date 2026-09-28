package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.PostClassifyApplyRequest;
import pro.uhalo.uni.dto.PostClassifyCandidateDTO;
import pro.uhalo.uni.dto.PostClassifyPreviewResponse;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧文章归类：把存量文章补进日报那套主题体系。
 *
 * <p>这里守四条性质，都是「改站点数据」这件事的底线：</p>
 * <ol>
 *   <li><b>只追加、不覆盖</b>：站长原有的分类标签一个不能动，标题正文完全不碰；</li>
 *   <li><b>幂等</b>：已经归过类的文章不再出现在待归类里，重复应用也只回报「跳过」，
 *       不能把同一个分类写进列表两遍；</li>
 *   <li><b>预览不写库</b>：预览阶段一次 {@code update} 都不许发生——
 *       分类花的是 AI 额度，写库改的是站点数据，两者都必须等站长点头；</li>
 *   <li><b>逐条独立</b>：一篇失败不影响其它篇，且失败原因要如实回报。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostTaxonomyServiceTest {

    private static final String GROUP = "aiDigestConfig";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestAiClient aiClient;

    @Mock
    private DigestTaxonomyService taxonomy;

    private PostTaxonomyService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PostTaxonomyService(client, settingFetcher, aiClient, taxonomy);
        stubConfig("""
            {"enabled":true,"aiApiKey":"sk-test","aiModel":"deepseek-chat"}""");
        lenient().when(taxonomy.categoryLabels()).thenReturn(Mono.just(Map.of()));
        lenient().when(taxonomy.tagLabels()).thenReturn(Mono.just(Map.of()));
        lenient().when(taxonomy.resolveOrCreateCategories(any()))
            .thenReturn(Mono.just(List.of("ai-ml")));
        // 分类清单随配置一起传下去，所以归类链路调用的是带目录的重载
        lenient().when(taxonomy.resolveOrCreateCategories(any(), any()))
            .thenReturn(Mono.just(List.of("ai-ml")));
        lenient().when(taxonomy.resolveOrCreateTags(any()))
            .thenReturn(Mono.just(List.of("rust")));
        lenient().when(taxonomy.resolveExistingCategories(any())).thenReturn(Mono.just(List.of()));
        lenient().when(aiClient.call(any(), anyString(), any()))
            .thenReturn(aiReply("{\"results\":[]}"));
    }


    /** 这些链路只关心正文，用量给它一个「对方未上报」的壳即可 */
    private static Mono<DigestAiReplyDTO> aiReply(String text) {
        return Mono.just(DigestAiReplyDTO.of(text,
            new DigestAiProviderDTO("test", "测试通道", "https://test.example/v1", "sk-test",
                "test-model"), "test-model", DigestAiUsageDTO.unreported()));
    }

    private void stubConfig(String json) throws Exception {
        lenient().when(settingFetcher.get(GROUP)).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    // ── 统计 ──

    /**
     * 统计口径：只数 AI 日报生成的文章。
     *
     * <p>站长自己写的文章（哪怕是没分类的）不在这份统计里——他归好类的文章
     * 不该被机器重判，而他自己写的没分类的文章轮不到插件替他决定。</p>
     */
    @Test
    void statusShouldCountOnlyDigestGeneratedPosts() {
        stubPosts(
            // AI 文章：已有主题分类
            post("p1", "AI 文章已归类", true, List.of("ai-ml"), List.of(), 3),
            // AI 文章：归的是站长自己的分类 → 同样算「已归好类」
            post("p2", "AI 文章归了站长分类", true, List.of("记录"), List.of(), 2),
            // AI 文章：一篇分类都没有 → 待归类
            post("p3", "AI 文章没分类", true, null, List.of(), 1),
            // 站长自己写的文章：不入统计
            ownPost("o1", "我自己的文章，还没分类"),
            // 未发布/已删除/仅内部可见的 AI 文章：都不算
            draft("d1", "草稿"),
            deleted("x1", "已删除"),
            internal("i1", "仅内部可见"));

        StepVerifier.create(service.status())
            .assertNext(status -> {
                assertThat(status.getDigestPosts()).isEqualTo(3);
                assertThat(status.getClassifiedPosts()).isEqualTo(2);
                assertThat(status.getPendingPosts()).isEqualTo(1);
                assertThat(status.getBatchSize()).isEqualTo(PostTaxonomyService.BATCH_SIZE);
                assertThat(status.isAiReady()).isTrue();
                assertThat(status.isAutoCategoryEnabled()).isTrue();
                assertThat(status.isAutoTagEnabled()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void statusShouldReportAiNotReadyWithoutApiKey() throws Exception {
        stubConfig("{\"enabled\":true,\"aiApiKey\":\"\"}");
        stubPosts(post("p1", "文章", true, null, List.of(), 1));

        StepVerifier.create(service.status())
            .assertNext(status -> assertThat(status.isAiReady()).isFalse())
            .verifyComplete();
    }

    // ── 预览 ──

    @Test
    void previewShouldOnlyTakeUnclassifiedPostsNewestFirst() {
        stubPosts(
            post("newest", "最新未归类", true, null, List.of(), 30),
            post("middle", "中间已归类", true, List.of("security"), List.of(), 20),
            post("oldest", "最旧未归类", true, null, List.of(), 10));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply("""
            {"results":[
              {"index":0,"category":"engineering","keywords":["Rust"]},
              {"index":1,"category":"tools","keywords":["CLI"]}
            ]}"""));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                assertThat(response.getAnalyzed()).isEqualTo(2);
                assertThat(response.getItems()).extracting(PostClassifyCandidateDTO::getName)
                    .containsExactly("newest", "oldest");
                assertThat(response.getPendingPosts()).isEqualTo(2);
                assertThat(response.isHasMore()).isFalse();
            })
            .verifyComplete();

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(aiClient).call(any(), prompt.capture(), any());
        assertThat(prompt.getValue()).contains("最新未归类").contains("最旧未归类");
        // 已归类的文章不该出现在提示词里：既浪费额度，也让它有可能被改判
        assertThat(prompt.getValue()).doesNotContain("中间已归类");
    }

    @Test
    void previewShouldNotWriteAnything() {
        stubPosts(post("p1", "文章", true, null, List.of(), 1));

        StepVerifier.create(service.preview(15)).expectNextCount(1).verifyComplete();

        verify(client, never()).update(any());
        verify(client, never()).create(any());
    }

    @Test
    void previewShouldLimitBatchAndKeepRemainingCount() {
        stubPosts(
            post("p1", "一", true, null, List.of(), 3),
            post("p2", "二", true, null, List.of(), 2),
            post("p3", "三", true, null, List.of(), 1));

        StepVerifier.create(service.preview(2))
            .assertNext(response -> {
                assertThat(response.getItems()).hasSize(2);
                assertThat(response.getPendingPosts()).isEqualTo(3);
                assertThat(response.isHasMore()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void previewShouldMarkUnjudgedItemsForManualHandling() {
        stubPosts(post("p1", "文章", true, null, List.of(), 1));
        // 模型说了个不存在的类别：不能悄悄归到 other，要让站长看见
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"results\":[{\"index\":0,\"category\":\"前端\",\"keywords\":[]}]}"));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                var item = response.getItems().get(0);
                assertThat(item.getSuggestedCategory()).isEmpty();
                assertThat(item.getSuggestedCategoryLabel()).isEmpty();
                assertThat(item.getNote()).contains("AI 没给出有效主题");
            })
            .verifyComplete();
    }

    @Test
    void previewShouldTranslateCategoryKeyToChineseLabel() {
        stubPosts(post("p1", "文章", true, null, List.of(), 1));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"results\":[{\"index\":0,\"category\":\"ai-ml\",\"keywords\":[\"LLM\"]}]}"));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                var item = response.getItems().get(0);
                assertThat(item.getSuggestedCategory()).isEqualTo("ai-ml");
                assertThat(item.getSuggestedCategoryLabel())
                    .isEqualTo(DigestRenderer.categoryLabel("ai-ml"));
                assertThat(item.getSuggestedKeywords()).containsExactly("LLM");
            })
            .verifyComplete();
    }

    @Test
    void previewShouldExplainItselfWhenAutoCategoryDisabled() throws Exception {
        stubConfig("""
            {"enabled":true,"aiApiKey":"sk-test","autoCategoryEnabled":false,"autoTagEnabled":false}""");
        stubPosts(post("p1", "文章", true, null, List.of(), 1));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply(
            "{\"results\":[{\"index\":0,\"category\":\"ai-ml\",\"keywords\":[\"LLM\"]}]}"));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                var item = response.getItems().get(0);
                assertThat(item.getSuggestedCategory()).isEmpty();
                assertThat(item.getSuggestedKeywords()).isEmpty();
                assertThat(item.getNote()).contains("关闭了「自动分类」").contains("关闭了「自动打标」");
            })
            .verifyComplete();
    }

    @Test
    void previewShouldSaySoWhenNothingIsPending() {
        stubPosts(post("p1", "已归类", true, List.of("tools"), List.of(), 1));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                assertThat(response.getItems()).isEmpty();
                assertThat(response.isHasMore()).isFalse();
                assertThat(response.getMessage()).contains("AI 日报生成的文章都已有分类");
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /**
     * 站长已经归好类的文章不再分析。
     *
     * <p>关键点：判断依据是「有没有分类」，不是「有没有那六个主题分类」。
     * 站长把文章归进他自己的分类（记录 / 手册 / 开箱……）同样是分好类了，
     * 机器再判一遍既浪费 AI 调用，也可能把他整理好的结果覆盖成别的主题。</p>
     */
    @Test
    void previewShouldNotReanalyzePostsWithOwnCategories() {
        stubPosts(
            post("p1", "归进站长自己的分类", true, List.of("记录"), List.of(), 2),
            post("p2", "归进主题分类", true, List.of("security"), List.of(), 1));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                assertThat(response.getItems()).isEmpty();
                assertThat(response.getPendingPosts()).isZero();
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /** 只分析插件自己发布的文章：站长自己写的（哪怕没分类）不替他做决定 */
    @Test
    void previewShouldIgnorePostsNotWrittenByDigest() {
        stubPosts(ownPost("o1", "我自己写的，还没分类"));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> {
                assertThat(response.getItems()).isEmpty();
                assertThat(response.getPendingPosts()).isZero();
            })
            .verifyComplete();

        verify(aiClient, never()).call(any(), anyString(), any());
    }

    /** 老版本只打了 annotation 没打 label，同样要认出来是 AI 文章 */
    @Test
    void previewShouldRecognizeLegacyDigestMarker() {
        stubPosts(legacyDigestPost("p1", "老版本发布的 AI 文章"));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> assertThat(response.getAnalyzed()).isEqualTo(1))
            .verifyComplete();
    }

    @Test
    void previewShouldFailFastWithoutApiKey() throws Exception {
        stubConfig("{\"enabled\":true,\"aiApiKey\":\"\"}");
        stubPosts(post("p1", "文章", true, null, List.of(), 1));

        StepVerifier.create(service.preview(15))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("大模型通道"))
            .verify();
    }

    /**
     * 清单里配好了通道、旧字段全空时，归类不能再报「还没配置」。
     *
     * <p>这三个消费方（日报 / 归类 / 付费简报）共用一份通道配置，归类页的可用性判断
     * 也必须按清单算，否则站长会看着一个配好的页面被拦住。</p>
     */
    @Test
    void previewShouldAcceptChannelListWithoutLegacyKey() throws Exception {
        stubConfig("""
            {"enabled":true,"aiApiKey":"","aiApiBase":"",
             "aiProviders":[{"key":"ds","label":"DeepSeek","apiBase":"https://api.deepseek.com/v1",
                             "apiKey":"sk-list","model":"deepseek-chat","enabled":true}]}
            """);
        stubPosts(post("p1", "文章", true, null, List.of(), 1));
        when(aiClient.call(any(), anyString(), any())).thenReturn(aiReply("""
            {"results":[{"name":"p1","category":"ai-ml","tags":[],"reason":"讲模型"}]}
            """));

        StepVerifier.create(service.preview(15))
            .assertNext(response -> assertThat(response.getAiModel()).isEqualTo("deepseek-chat"))
            .verifyComplete();
    }

    // ── 应用 ──

    @Test
    void applyShouldAppendWithoutTouchingExisting() {
        var original = post("p1", "原标题", true, List.of("站长分类"), List.of("站长标签"), 1);
        stubPosts(original);
        var updated = captureUpdate();

        StepVerifier.create(service.apply(request(item("p1", "ai-ml", List.of("Rust")))))
            .assertNext(response -> {
                assertThat(response.getApplied()).isEqualTo(1);
                assertThat(response.getItems().get(0).getState()).isEqualTo("APPLIED");
                assertThat(response.getItems().get(0).getCategory()).isEqualTo("ai-ml");
                assertThat(response.getItems().get(0).getTags()).containsExactly("rust");
            })
            .verifyComplete();

        var saved = updated.get();
        // 站长原有的排在前，新分类追加在后；标签同理
        assertThat(saved.getSpec().getCategories()).containsExactly("站长分类", "ai-ml");
        assertThat(saved.getSpec().getTags()).containsExactly("站长标签", "rust");
        // 标题等其它字段完全不碰
        assertThat(saved.getSpec().getTitle()).isEqualTo("原标题");
    }

    @Test
    void applyShouldCreateCategoryListWhenPostHadNone() {
        var original = post("p1", "标题", true, null, null, 1);
        stubPosts(original);
        var updated = captureUpdate();

        StepVerifier.create(service.apply(request(item("p1", "ai-ml", null))))
            .assertNext(response -> assertThat(response.getApplied()).isEqualTo(1))
            .verifyComplete();

        assertThat(updated.get().getSpec().getCategories()).containsExactly("ai-ml");
    }

    @Test
    void applyShouldSkipWhenNothingNew() {
        // 已经归过类的文章：重复应用只回报跳过，不能再写一遍（也不能重复插入同一个分类）
        stubPosts(post("p1", "标题", true, List.of("ai-ml"), List.of("rust"), 1));

        StepVerifier.create(service.apply(request(item("p1", "ai-ml", List.of("Rust")))))
            .assertNext(response -> {
                assertThat(response.getApplied()).isZero();
                assertThat(response.getSkipped()).isEqualTo(1);
                assertThat(response.getItems().get(0).getMessage()).contains("无需重复写入");
            })
            .verifyComplete();

        verify(client, never()).update(any());
    }

    @Test
    void applyShouldSkipMissingPost() {
        stubPosts();

        StepVerifier.create(service.apply(request(item("gone", "ai-ml", List.of("Rust")))))
            .assertNext(response -> {
                assertThat(response.getSkipped()).isEqualTo(1);
                assertThat(response.getItems().get(0).getMessage()).contains("文章不存在");
            })
            .verifyComplete();
    }

    @Test
    void applyShouldIgnoreCategoryThatIsNeitherThemeKeyNorExisting() {
        // 请求体里写一个既不是主题 key、站点上也不存在的分类：忽略，不给站点造分类
        stubPosts(post("p1", "标题", true, null, List.of(), 1));
        var updated = captureUpdate();

        StepVerifier.create(service.apply(request(item("p1", "我瞎写的分类", List.of("Rust")))))
            .assertNext(response -> {
                assertThat(response.getApplied()).isEqualTo(1);
                assertThat(response.getItems().get(0).getCategory()).isEmpty();
            })
            .verifyComplete();

        assertThat(updated.get().getSpec().getCategories()).isEmpty();
        assertThat(updated.get().getSpec().getTags()).containsExactly("rust");
    }

    @Test
    void applyShouldRetryOnConcurrentModification() {
        var original = post("p1", "标题", true, null, List.of(), 1);
        var attempts = new AtomicInteger();
        // 每次 fetch 都返回一份新的副本，模拟真实的「重新读取最新版本」
        when(client.fetch(eq(Post.class), anyString()))
            .thenAnswer(inv -> Mono.fromSupplier(() -> copyOf(original)));
        when(client.update(any())).thenAnswer(inv -> {
            if (attempts.incrementAndGet() < 3) {
                return Mono.error(new IllegalStateException(
                    "Failed to update versioned entity, Was the entity updated or deleted concurrently?"));
            }
            return Mono.just(inv.getArgument(0));
        });

        StepVerifier.create(service.apply(request(item("p1", "ai-ml", null))))
            .assertNext(response -> {
                assertThat(response.getApplied()).isEqualTo(1);
                assertThat(attempts.get()).isEqualTo(3);
            })
            .verifyComplete();
    }

    @Test
    void applyShouldReportFailureWithReason() {
        stubPosts(post("p1", "标题", true, null, List.of(), 1));
        when(client.update(any())).thenReturn(Mono.error(new IllegalStateException("磁盘满了")));

        StepVerifier.create(service.apply(request(item("p1", "ai-ml", null))))
            .assertNext(response -> {
                assertThat(response.getFailed()).isEqualTo(1);
                assertThat(response.getApplied()).isZero();
                assertThat(response.getItems().get(0).getMessage()).contains("磁盘满了");
                assertThat(response.getMessage()).contains("失败 1 篇");
            })
            .verifyComplete();
    }

    @Test
    void applyShouldKeepGoingAfterOneItemFails() {
        var ok = post("ok", "好文章", true, null, List.of(), 2);
        var bad = post("bad", "坏文章", true, null, List.of(), 1);
        when(client.fetch(eq(Post.class), anyString())).thenAnswer(inv -> {
            var name = inv.getArgument(1, String.class);
            if ("ok".equals(name)) {
                return Mono.fromSupplier(() -> copyOf(ok));
            }
            if ("bad".equals(name)) {
                return Mono.fromSupplier(() -> copyOf(bad));
            }
            return Mono.empty();
        });
        when(client.update(any())).thenAnswer(inv -> {
            Post post = inv.getArgument(0);
            if ("坏文章".equals(post.getSpec().getTitle())) {
                return Mono.error(new IllegalStateException("写入失败"));
            }
            return Mono.just(post);
        });

        var request = new PostClassifyApplyRequest(new ArrayList<>(List.of(
            item("ok", "ai-ml", null), item("bad", "ai-ml", null), item("gone", "ai-ml", null))));

        StepVerifier.create(service.apply(request))
            .assertNext(response -> {
                assertThat(response.getApplied()).isEqualTo(1);
                assertThat(response.getFailed()).isEqualTo(1);
                assertThat(response.getSkipped()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void applyShouldRejectEmptyRequest() {
        StepVerifier.create(service.apply(new PostClassifyApplyRequest(List.of())))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有要应用的条目"))
            .verify();

        StepVerifier.create(service.apply(null))
            .expectError(IllegalArgumentException.class)
            .verify();
    }

    @Test
    void applyShouldCapTagsPerPost() {
        stubPosts(post("p1", "标题", true, null, List.of(), 1));
        captureUpdate();

        var keywords = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        StepVerifier.create(service.apply(request(item("p1", "ai-ml", keywords))))
            .expectNextCount(1)
            .verifyComplete();

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(taxonomy).resolveOrCreateTags(captor.capture());
        assertThat(captor.getValue()).hasSize(6);
    }

    @Test
    void applyShouldPassCategoryKeyToTaxonomyForCreation() {
        stubPosts(post("p1", "标题", true, null, List.of(), 1));
        captureUpdate();

        StepVerifier.create(service.apply(request(item("p1", "AI-ML", null))))
            .expectNextCount(1)
            .verifyComplete();

        // 大小写归一化后才交给创建逻辑，否则会建出 AI-ML 这种不合法的扩展名
        verify(taxonomy).resolveOrCreateCategories(eq(List.of("ai-ml")), any());
    }

    // ── 工具 ──

    @Test
    void sanitizeShouldStripHtmlAndCollapseWhitespace() {
        assertThat(PostTaxonomyService.sanitize("<p>你好<br/>世界</p>\n\n  第二段"))
            .isEqualTo("你好 世界 第二段");
        assertThat(PostTaxonomyService.sanitize(null)).isEmpty();
    }

    private static PostClassifyApplyRequest request(PostClassifyApplyRequest.Item item) {
        return new PostClassifyApplyRequest(List.of(item));
    }

    private static PostClassifyApplyRequest.Item item(String name, String category,
        List<String> keywords) {
        return PostClassifyApplyRequest.Item.builder()
            .name(name)
            .category(category)
            .keywords(keywords)
            .build();
    }

    /** 捕获写入的文章（每次 update 都返回传入的那个实例）。 */
    private java.util.concurrent.atomic.AtomicReference<Post> captureUpdate() {
        var captured = new java.util.concurrent.atomic.AtomicReference<Post>();
        when(client.update(any(Post.class))).thenAnswer(inv -> {
            Post post = inv.getArgument(0);
            captured.set(post);
            return Mono.just(post);
        });
        return captured;
    }

    /**
     * 文章查询按名字作答。
     *
     * <p>{@code fetch} 每次返回一份副本：真实的扩展存储每次读都是新对象，
     * 而重试逻辑依赖这一点（拿到已经改过的同一个实例会误判成「无需重复写入」）。</p>
     */
    private void stubPosts(Post... posts) {
        when(client.fetch(eq(Post.class), anyString())).thenAnswer(inv -> {
            var name = inv.getArgument(1, String.class);
            return Flux.fromArray(posts)
                .filter(post -> name.equals(post.getMetadata().getName()))
                .map(PostTaxonomyServiceTest::copyOf)
                .next();
        });
        when(client.listAll(eq(Post.class), any(), any()))
            .thenReturn(Flux.fromArray(posts).map(PostTaxonomyServiceTest::copyOf));
    }

    private static Post copyOf(Post source) {
        var copy = new Post();
        var metadata = new Metadata();
        metadata.setName(source.getMetadata().getName());
        // labels/annotations 必须一起复制：日报来源标记就打在它们上面，
        // 漏掉的话模拟出来的文章全都不是「AI 生成的文章」，候选集直接空了
        metadata.setLabels(source.getMetadata().getLabels() == null
            ? new java.util.LinkedHashMap<>()
            : new java.util.LinkedHashMap<>(source.getMetadata().getLabels()));
        metadata.setAnnotations(source.getMetadata().getAnnotations() == null
            ? new java.util.LinkedHashMap<>()
            : new java.util.LinkedHashMap<>(source.getMetadata().getAnnotations()));
        copy.setMetadata(metadata);

        var spec = new Post.PostSpec();
        spec.setTitle(source.getSpec().getTitle());
        spec.setPublish(source.getSpec().getPublish());
        spec.setDeleted(source.getSpec().getDeleted());
        spec.setVisible(source.getSpec().getVisible());
        spec.setPublishTime(source.getSpec().getPublishTime());
        spec.setCategories(source.getSpec().getCategories() == null ? null
            : new ArrayList<>(source.getSpec().getCategories()));
        spec.setTags(source.getSpec().getTags() == null ? null
            : new ArrayList<>(source.getSpec().getTags()));
        copy.setSpec(spec);
        return copy;
    }

    /**
     * 一篇 AI 日报生成的文章（默认形态）。
     *
     * <p>带上 {@code uni.uhalo.pro/digest} 标签：归类的范围就是这些文章，
     * 没有标记的文章压根不该进候选。</p>
     */
    private static Post post(String name, String title, boolean publish, List<String> categories,
        List<String> tags, int hoursAgo) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(new java.util.LinkedHashMap<>(Map.of("uni.uhalo.pro/digest", "true")));
        post.setMetadata(metadata);

        var spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setPublish(publish);
        spec.setDeleted(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPublishTime(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(hoursAgo * 3600L));
        spec.setCategories(categories);
        spec.setTags(tags);
        post.setSpec(spec);
        return post;
    }

    /** 站长自己写的文章：没有日报标记 */
    private static Post ownPost(String name, String title) {
        var post = post(name, title, true, null, List.of(), 0);
        post.getMetadata().setLabels(new java.util.LinkedHashMap<>());
        return post;
    }

    /** 老版本插件写的文章：只打了 annotation，没有 label */
    private static Post legacyDigestPost(String name, String title) {
        var post = post(name, title, true, null, List.of(), 0);
        post.getMetadata().setLabels(new java.util.LinkedHashMap<>());
        post.getMetadata().setAnnotations(new java.util.LinkedHashMap<>(
            Map.of("uni.uhalo.pro/digest-generated", "true")));
        return post;
    }

    private static Post draft(String name, String title) {
        var post = post(name, title, false, null, List.of(), 0);
        post.getSpec().setPublishTime(null);
        return post;
    }

    private static Post deleted(String name, String title) {
        var post = post(name, title, true, null, List.of(), 0);
        post.getSpec().setDeleted(true);
        return post;
    }

    private static Post internal(String name, String title) {
        var post = post(name, title, true, null, List.of(), 0);
        post.getSpec().setVisible(Post.VisibleEnum.INTERNAL);
        return post;
    }
}
