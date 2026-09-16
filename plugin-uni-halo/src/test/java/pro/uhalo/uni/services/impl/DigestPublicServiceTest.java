package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.DigestSubscriptionRequest;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.scheme.DigestReport;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 小程序端日报列表的边界测试。
 *
 * <p>锁住三条安全/正确性边界：</p>
 * <ol>
 *   <li><b>未发布的日报绝不能被用户看到</b>——报告在发布前就先落库（先存稿再发布是刻意设计），
 *       若这里漏了判断，用户会看到半成品乃至正文缺失的空日报；</li>
 *   <li><b>文章条目要能对上发布结果</b>，逐篇发布时跳该文章、合并发布时回落到日报正文，
 *       前端不该被迫区分发布模式；</li>
 *   <li><b>分类筛选条只列实际出现过的分类</b>，否则点进去全是空分类。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DigestPublicServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DigestPersistence persistence;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestSubscriptionService subscriptionService;

    @Mock
    private WxUserService wxUserService;

    @InjectMocks
    private DigestPublicService service;

    private void stubEnabled(boolean enabled) {
        try {
            when(settingFetcher.get("aiDigestConfig"))
                .thenReturn(Mono.just(MAPPER.readTree("{\"enabled\":" + enabled + "}")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static DigestReport report(String date, String status, Integer postCount,
        String postName, String posts, String entries) {
        var report = new DigestReport();
        report.setDate(date);
        report.setSlot("auto");
        report.setStatus(status);
        report.setPostCount(postCount);
        report.setPostName(postName);
        report.setPosts(posts);
        report.setArticleEntries(entries);
        report.setArticleCount(2);
        report.setCover("/cover.png");
        report.setStats("{\"totalArticles\":120,\"successFeeds\":80}");
        return report;
    }

    private static String entries() {
        return """
            [{"title":"读懂 Transformer","cover":"/a.png","category":"ai-ml",
              "categoryLabel":"AI / ML","categoryEmoji":"🤖","keywords":["transformer","attention"],
              "sourceName":"simonwillison.net","summary":"讲清注意力机制"},
             {"title":"供应链安全","cover":"/b.png","category":"security",
              "categoryLabel":"安全","categoryEmoji":"🔒","keywords":["sbom"],
              "sourceName":"github.blog","summary":"依赖投毒的防御"}]
            """;
    }

    /** 未发布（状态不是 SUCCESS）的日报不能出现在列表里。 */
    @Test
    void shouldHideUnpublishedReports() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "RUNNING", 0, null, "[]", entries()),
            report("2026-09-14", "FAILED", 0, null, "[]", entries()))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                assertThat(response.isEnabled()).isTrue();
                assertThat(response.getItems()).as("生成中与失败的日报都不该对用户可见").isEmpty();
            })
            .verifyComplete();
    }

    /** 成功了但一篇文章都没发出来（如发布全部失败）时，也不该出现一份空日报。 */
    @Test
    void shouldHideReportsWithoutPublishedPosts() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "SUCCESS", 0, null, "[]", entries()))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> assertThat(response.getItems()).isEmpty())
            .verifyComplete();
    }

    /** 逐篇发布：每条文章跳到自己的文章名。 */
    @Test
    void shouldResolvePerArticlePostNames() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "SUCCESS", 2, "/archives/digest",
                """
                    [{"title":"读懂 Transformer","postName":"post-1","state":"SUCCESS"},
                     {"title":"供应链安全","postName":"post-2","state":"SUCCESS"}]
                    """,
                entries()))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                var articles = response.getItems().get(0).getArticles();
                assertThat(articles).hasSize(2);
                assertThat(articles.get(0).getPostName()).isEqualTo("post-1");
                assertThat(articles.get(1).getPostName()).isEqualTo("post-2");
                assertThat(articles.get(0).getCategoryLabel()).isEqualTo("AI / ML");
                assertThat(articles.get(0).getKeywords()).contains("transformer");
            })
            .verifyComplete();
    }

    /**
     * 合并发布：没有逐篇文章时回落到日报正文，前端不需要区分发布模式。
     * 否则用户点条目会打到空链接。
     */
    @Test
    void shouldFallBackToDigestPostWhenArticlesNotPublishedSeparately() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "SUCCESS", 1, "post-digest",
                "[{\"title\":\"AI 日报 · 2026-09-15\",\"postName\":\"post-digest\",\"state\":\"SUCCESS\"}]",
                entries()))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                var articles = response.getItems().get(0).getArticles();
                assertThat(articles).hasSize(2);
                assertThat(articles).allSatisfy(article ->
                    assertThat(article.getPostName()).isEqualTo("post-digest"));
                // 合并模式下这份日报自身的正文也可以作为入口
                assertThat(response.getItems().get(0).getReportPostName()).isEqualTo("post-digest");
            })
            .verifyComplete();
    }

    /** 分类筛选条只列实际出现过的分类，并按篇数倒序。 */
    @Test
    void shouldListOnlyCategoriesPresentInData() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "SUCCESS", 2, null,
                """
                    [{"title":"读懂 Transformer","postName":"post-1"},
                     {"title":"供应链安全","postName":"post-2"}]
                    """,
                entries()))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                var categories = response.getCategories();
                assertThat(categories).hasSize(2);
                assertThat(categories).extracting("key")
                    .containsExactlyInAnyOrder("ai-ml", "security");
                assertThat(categories).allSatisfy(item ->
                    assertThat(item.getCount()).isEqualTo(1));
            })
            .verifyComplete();
    }

    /** 日报功能关闭时不下发任何内容，前端据此隐藏入口。 */
    @Test
    void shouldReturnDisabledWhenFeatureOff() {
        stubEnabled(false);

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                assertThat(response.isEnabled()).isFalse();
                assertThat(response.getItems()).isEmpty();
            })
            .verifyComplete();
    }

    /** 分页与 hasMore 要按已发布的份数算，不能把被过滤掉的也算进去。 */
    @Test
    void shouldPaginateOnlyVisibleReports() {
        stubEnabled(true);
        var reports = new java.util.ArrayList<DigestReport>();
        for (var i = 0; i < 5; i++) {
            reports.add(report("2026-09-1" + i, "SUCCESS", 1, null,
                "[{\"title\":\"读懂 Transformer\",\"postName\":\"p" + i + "\"}]", entries()));
        }
        // 混入一份失败的，不应影响分页结果
        reports.add(report("2026-09-30", "FAILED", 0, null, "[]", entries()));
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(reports));

        StepVerifier.create(service.list(0, 2))
            .assertNext(response -> {
                assertThat(response.getItems()).hasSize(2);
                assertThat(response.isHasMore()).isTrue();
                // 最新的排前面（失败那份已被过滤）
                assertThat(response.getItems().get(0).getDate()).isEqualTo("2026-09-14");
            })
            .verifyComplete();
    }

    /**
     * 文章条目 JSON 损坏时，退回用发布结果兜底，而不是整份日报消失。
     *
     * <p>条目只是「分类/关键词/摘要」这些附加信息的载体；它们坏了不该连累内容本身——
     * 文章明明是发出来了、也能点开，却因为条目解析失败而整份不显示，是本末倒置。</p>
     */
    @Test
    void shouldFallBackToPostsWhenEntriesJsonIsBroken() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-15", "SUCCESS", 1, "post-digest",
                "[{\"title\":\"AI 日报\",\"postName\":\"post-digest\"}]", "{不是合法 JSON"))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                assertThat(response.getItems()).hasSize(1);
                var articles = response.getItems().get(0).getArticles();
                assertThat(articles).as("条目坏了也要能列出文章").hasSize(1);
                assertThat(articles.get(0).getPostName()).isEqualTo("post-digest");
            })
            .verifyComplete();
    }
    // ==================== 分享个人精选（裂变入口） ====================
    //
    // 三条必须钉住的性质：
    //   1. 邀请码无效时给明确提示，而不是一个空页面把裂变入口堵死；
    //   2. 分享人没配关注条件时如实说明「这是全部精选」，不假装是个性化；
    //   3. 不泄露分享人的原始偏好（关键词/来源），一键订阅也不复制来源。

    private static WxUser sharer(String nickname) {
        var user = new WxUser();
        user.setOpenid("openid-sharer");
        user.setNickname(nickname);
        user.setAvatarUrl("/avatar.png");
        user.setInviteCode("ABCD1234");
        return user;
    }

    private static DigestSubscriptionDTO prefs(List<String> categories, List<String> keywords,
        List<String> sources) {
        var dto = DigestSubscriptionDTO.builder()
            .enabled(true)
            .categories(categories)
            .keywords(keywords)
            .sources(sources)
            .build();
        dto.setFiltered(!categories.isEmpty() || !keywords.isEmpty() || !sources.isEmpty());
        return dto;
    }

    private void stubRecentPublished() {
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report(java.time.LocalDate.now().toString(), "SUCCESS", 2, null,
                "[{\"title\":\"读懂 Transformer\",\"postName\":\"post-1\"},"
                    + "{\"title\":\"供应链安全\",\"postName\":\"post-2\"}]",
                entries()))));
    }

    /** 邀请码无效：明确提示，并让用户还能看全部日报。 */
    @Test
    void sharedShouldExplainInvalidInviteCode() {
        stubEnabled(true);
        when(wxUserService.fetchByInviteCode("NOPE")).thenReturn(Mono.empty());

        StepVerifier.create(service.shared("NOPE"))
            .assertNext(dto -> {
                assertThat(dto.isValid()).isFalse();
                assertThat(dto.getMessage()).contains("失效");
                assertThat(dto.getArticles()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void sharedShouldRejectBlankInviteCode() {
        stubEnabled(true);

        StepVerifier.create(service.shared("  "))
            .assertNext(dto -> {
                assertThat(dto.isValid()).isFalse();
                assertThat(dto.getMessage()).contains("缺少邀请码");
            })
            .verifyComplete();
    }

    /** 按配好的偏好给出精选，并带上分享人昵称。 */
    @Test
    void sharedShouldReturnSharersPickedArticles() {
        stubEnabled(true);
        stubRecentPublished();
        when(wxUserService.fetchByInviteCode("ABCD1234")).thenReturn(Mono.just(sharer("小莫")));
        when(subscriptionService.load("openid-sharer"))
            .thenReturn(Mono.just(prefs(List.of("ai-ml"), List.of(), List.of())));

        StepVerifier.create(service.shared("abcd1234"))
            .assertNext(dto -> {
                assertThat(dto.isValid()).isTrue();
                assertThat(dto.getSharerName()).isEqualTo("小莫");
                assertThat(dto.getArticles()).hasSize(1);
                assertThat(dto.getArticles().get(0).getTitle()).isEqualTo("读懂 Transformer");
                // 分类摘要是内容属性，可以展示
                assertThat(dto.getCategories()).extracting("key").containsExactly("ai-ml");
                // 「从多少篇里挑」让精选有分量
                assertThat(dto.getTotalCount()).isEqualTo(2);
            })
            .verifyComplete();
    }

    /** 分享人没配关注条件时，如实说明这是全部精选，不假装个性化。 */
    @Test
    void sharedShouldBeHonestWhenSharerHasNoPreferences() {
        stubEnabled(true);
        stubRecentPublished();
        when(wxUserService.fetchByInviteCode("ABCD1234")).thenReturn(Mono.just(sharer("小莫")));
        when(subscriptionService.load("openid-sharer"))
            .thenReturn(Mono.just(prefs(List.of(), List.of(), List.of())));

        StepVerifier.create(service.shared("ABCD1234"))
            .assertNext(dto -> {
                assertThat(dto.isValid()).isTrue();
                assertThat(dto.getArticles()).hasSize(2);
                assertThat(dto.getMessage()).contains("全部精选");
            })
            .verifyComplete();
    }

    /**
     * 隐私边界：分享人的原始偏好（关键词 / 来源）绝不能出现在落地页数据里。
     *
     * <p>他主动分享的是「我挑的内容」，不是「我的兴趣标签列表」。</p>
     */
    @Test
    void sharedShouldNotLeakSharersRawPreferences() {
        stubEnabled(true);
        stubRecentPublished();
        when(wxUserService.fetchByInviteCode("ABCD1234")).thenReturn(Mono.just(sharer("小莫")));
        when(subscriptionService.load("openid-sharer")).thenReturn(Mono.just(
            prefs(List.of("ai-ml"), List.of("transformer"), List.of("simonwillison.net"))));

        StepVerifier.create(service.shared("ABCD1234"))
            .assertNext(dto -> {
                // 逐条检查命中原因：文章自身的 keywords/sourceName 是内容属性（正常列表也会展示），
                // 但 matchedReason 不能把分享人的偏好原样写出来
                for (var article : dto.getArticles()) {
                    var reason = article.getMatchedReason();
                    assertThat(reason).isNotNull();
                    assertThat(reason).as("命中原因里不能出现分享人的关键词")
                        .doesNotContain("transformer");
                    assertThat(reason).as("命中原因里不能出现分享人的来源")
                        .doesNotContain("simonwillison.net");
                    // 粗粒度的分类可以保留：它已从文章本身推导并展示在落地卡片上
                    assertThat(reason).contains("AI / ML");
                }
            })
            .verifyComplete();
    }

    /** 一键订阅：复制分类与关键词，但绝不复制来源站点。 */
    @Test
    void subscribeFromShareShouldCopyCategoriesAndKeywordsOnly() {
        when(wxUserService.fetchByInviteCode("ABCD1234")).thenReturn(Mono.just(sharer("小莫")));
        var sharerPrefs = prefs(List.of("ai-ml", "security"), List.of("llm"),
            List.of("simonwillison.net"));
        sharerPrefs.setPostCategories(List.of("tech"));
        when(subscriptionService.load("openid-sharer")).thenReturn(Mono.just(sharerPrefs));
        when(subscriptionService.save(anyString(), any(DigestSubscriptionRequest.class)))
            .thenReturn(Mono.just(prefs(List.of("ai-ml", "security"), List.of("llm"), List.of())));

        StepVerifier.create(service.subscribeFromShare("openid-viewer", "ABCD1234"))
            .assertNext(dto -> assertThat(dto.getCategories()).containsExactly("ai-ml", "security"))
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(DigestSubscriptionRequest.class);
        org.mockito.Mockito.verify(subscriptionService)
            .save(eq("openid-viewer"), captor.capture());
        var sent = captor.getValue();
        assertThat(sent.getCategories()).containsExactly("ai-ml", "security");
        assertThat(sent.getKeywords()).containsExactly("llm");
        assertThat(sent.getPostCategories()).as("站点分类要一起复制，否则被分享者的首页不会跟着变")
            .containsExactly("tech");
        assertThat(sent.getSources()).as("来源更接近「他关注哪些具体作者」，不该被复制")
            .isEmpty();
        assertThat(sent.getEnabled()).isTrue();
    }

    /** 未登录时不订阅：订阅需要身份，否则没法保存到人身上。 */
    @Test
    void subscribeFromShareShouldRequireLogin() {
        StepVerifier.create(service.subscribeFromShare(null, "ABCD1234"))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("请先登录"))
            .verify();
    }

    /** 邀请码失效时给出可读错误，而不是静默什么都不做。 */
    @Test
    void subscribeFromShareShouldFailOnInvalidCode() {
        when(wxUserService.fetchByInviteCode("NOPE")).thenReturn(Mono.empty());

        StepVerifier.create(service.subscribeFromShare("openid-viewer", "NOPE"))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("失效"))
            .verify();
    }
    // ==================== 升级前日报的兜底 ====================
    //
    // 现象（线上实际出现过）：日期行写着「5 篇」，下面一篇文章都点不开，
    // 整页看起来就是一堆日期加数字。原因是 articleEntries 是后加的字段，
    // 升级前生成的日报没有它，而 articleCount 是老字段所以还有值。

    /** 没有文章条目的旧日报：用发布结果兜底，标题与跳转目标都要有。 */
    @Test
    void legacyReportWithoutEntriesShouldStillBeReadable() {
        stubEnabled(true);
        // entries 传空数组，模拟升级前的数据
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-14", "SUCCESS", 2, null,
                """
                    [{"title":"读懂 Transformer","postName":"post-1"},
                     {"title":"供应链安全","postName":"post-2"}]
                    """,
                "[]"))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                assertThat(response.getItems()).hasSize(1);
                var articles = response.getItems().get(0).getArticles();
                assertThat(articles).as("旧日报必须仍然能列出文章").hasSize(2);
                assertThat(articles.get(0).getTitle()).isEqualTo("读懂 Transformer");
                assertThat(articles.get(0).getPostName()).as("必须能点开").isEqualTo("post-1");
            })
            .verifyComplete();
    }

    /** 兜底出来的条目没有分类（当时没落库），但同一份日报仍要能被展示。 */
    @Test
    void legacyReportEntriesShouldTolerateMissingMetadata() {
        stubEnabled(true);
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-14", "SUCCESS", 1, null,
                "[{\"title\":\"旧文章\",\"postName\":\"post-old\"}]", "[]"))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> {
                var article = response.getItems().get(0).getArticles().get(0);
                assertThat(article.getCategory()).isNull();
                assertThat(article.getKeywords()).isEmpty();
                assertThat(article.getCover()).isNull();
            })
            .verifyComplete();
    }

    /**
     * 状态正常、发布计数正常，但一篇文章都取不出来时，这份日报不该出现。
     *
     * <p>渲染出来只有「一个日期 + 一句 5 篇」，下面空无一物——比不显示更让人困惑。</p>
     */
    @Test
    void reportWithoutReadableArticlesShouldBeHidden() {
        stubEnabled(true);
        // posts 为空 → 既没有条目也反推不出任何东西
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-14", "SUCCESS", 2, null, "[]", "[]"))));

        StepVerifier.create(service.list(0, 10))
            .assertNext(response -> assertThat(response.getItems()).isEmpty())
            .verifyComplete();
    }

    /** 个性化视图同样要滤掉不可读的日报，否则「我的关注」里也会出现空壳。 */
    @Test
    void myFeedShouldAlsoHideUnreadableReports() {
        stubEnabled(true);
        when(subscriptionService.load("openid-viewer"))
            .thenReturn(Mono.just(prefs(List.of("ai-ml"), List.of(), List.of())));
        when(persistence.listRecent(anyInt())).thenReturn(Mono.just(List.of(
            report("2026-09-14", "SUCCESS", 2, null, "[]", "[]"))));

        StepVerifier.create(service.myFeed("openid-viewer", 0, 10))
            .assertNext(response -> {
                assertThat(response.getItems()).isEmpty();
                assertThat(response.getTotalCount()).isZero();
            })
            .verifyComplete();
    }
}
