package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.ContentLockedDTO;
import pro.uhalo.uni.infra.ContentUnlockPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.ContentUnlock;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.ContentTicketConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 付费内容判定（{@code resolveLockIfRequired}）的放行语义测试。
 *
 * <p><b>这组测试锁住的是一个资损级缺陷的修复</b>：拦截器的契约是「服务层返回非空 DTO 即写 403，
 * 返回 empty 即放行」。若博主本人或<b>已解锁</b>用户也拿到一个 {@code unlocked=true} 的 DTO，
 * 他们会被拦截器当成待拦截对象而收到 403——博主打不开自己的文章，
 * 已解锁用户<b>花了券依然看不到正文</b>。因此这两类必须返回 empty。</p>
 *
 * <p>反向同样关键：真正未解锁的用户必须拿到 {@code locked=true} 的 DTO，
 * 否则付费墙形同虚设、正文直接泄露。所以本测试对「放行」与「拦截」两侧都做了断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentTicketServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ARTICLE_NAME = "post-paid-1";
    private static final String PAID_TAG_NAME = "tag-paid-name";
    private static final String PAID_TAG_SLUG = "paid";
    private static final String OPENID = "openid-abc";
    private static final String OWNER_HALO_NAME = "admin";

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private ContentUnlockPersistence unlockPersistence;

    @Mock
    private PointsPersistence pointsPersistence;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private ContentTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        // 每个用例都会走判定链路：读配置 → 查文章 → 解析付费标签
        lenient().when(settingFetcher.get(eq(ContentTicketConfigHelper.GROUP_NAME)))
            .thenReturn(Mono.just(enabledConfigNode()));
        lenient().when(client.fetch(eq(Post.class), eq(ARTICLE_NAME)))
            .thenReturn(Mono.just(paidPost()));
        lenient().when(client.listAll(eq(Tag.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(paidTag()));
        lenient().when(pointsPersistence.getOrCreateAccount(any()))
            .thenReturn(Mono.just(accountWithTickets(3)));
    }

    // ==================== 放行场景：必须返回 empty ====================

    /**
     * 博主本人访问自己的付费文章：放行。
     *
     * <p>返回 DTO 会让拦截器写 403，博主被自己的付费墙挡住。</p>
     */
    @Test
    void ownerShouldPassThroughOwnPaidPost() {
        when(wxUserService.fetchByOpenid(eq(OPENID)))
            .thenReturn(Mono.just(wxUser(OWNER_HALO_NAME)));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    /**
     * 已解锁用户再次访问：放行，且不重复扣券。
     *
     * <p>这是本组测试的核心——返回 DTO 会导致「花了券还看不到正文」。</p>
     */
    @Test
    void unlockedUserShouldPassThroughPaidPost() {
        when(wxUserService.fetchByOpenid(eq(OPENID))).thenReturn(Mono.empty());
        when(unlockPersistence.findByUnlockKey(any())).thenReturn(Mono.just(new ContentUnlock()));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    /** 未打付费标签的文章：放行，避免误拦截正常阅读。 */
    @Test
    void nonPaidPostShouldPassThrough() {
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME)))
            .thenReturn(Mono.just(postWithoutPaidTag()));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    /** 功能未开启：一律放行，绝不拦截。 */
    @Test
    void disabledFeatureShouldPassThroughEverything() {
        when(settingFetcher.get(eq(ContentTicketConfigHelper.GROUP_NAME)))
            .thenReturn(Mono.just(disabledConfigNode()));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    /** 文章查不到时放行，把 404 语义留给 Halo 原生接口，不替换成我们的 403。 */
    @Test
    void missingPostShouldPassThrough() {
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME))).thenReturn(Mono.empty());

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    /**
     * 判定链路抛异常时放行（fail-open）。
     *
     * <p>误拦截会让付费文章乃至全站打不开，影响面远大于「少收一次费」。</p>
     */
    @Test
    void failureInResolutionShouldPassThrough() {
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME)))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .verifyComplete();
    }

    // ==================== 拦截场景：必须返回 locked DTO ====================

    /** 已登录但未解锁：拦截，且 unlocked 必须为 false，券余额为真实值。 */
    @Test
    void lockedOutForUnlockedLoggedInUser() {
        when(wxUserService.fetchByOpenid(eq(OPENID))).thenReturn(Mono.empty());
        when(unlockPersistence.findByUnlockKey(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isTrue();
                org.assertj.core.api.Assertions.assertThat(dto.getUnlocked()).isFalse();
                org.assertj.core.api.Assertions.assertThat(dto.getArticleName()).isEqualTo(ARTICLE_NAME);
                org.assertj.core.api.Assertions.assertThat(dto.getTickets()).isEqualTo(3);
                org.assertj.core.api.Assertions.assertThat(dto.getCostTickets()).isEqualTo(2);
            })
            .verifyComplete();
    }

    /** 未登录访问付费文章：拦截，提示登录，券数按 0 返回（不查库）。 */
    @Test
    void lockedOutForAnonymousUser() {
        StepVerifier.create(service.resolveLockIfRequired(null, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isTrue();
                org.assertj.core.api.Assertions.assertThat(dto.getUnlocked()).isFalse();
                org.assertj.core.api.Assertions.assertThat(dto.getTickets()).isZero();
            })
            .verifyComplete();
    }

    /**
     * 锁定响应绝不能携带正文。
     *
     * <p>摘要只取自 {@code post.status.excerpt}（列表页本就公开），
     * 正文来自 Snapshot，服务端全程不读取——这里断言 DTO 只有安全的展示字段。</p>
     */
    @Test
    void lockedResponseMustNotCarryBodyContent() {
        when(wxUserService.fetchByOpenid(eq(OPENID))).thenReturn(Mono.empty());
        when(unlockPersistence.findByUnlockKey(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getTitle()).isEqualTo("付费文章标题");
                org.assertj.core.api.Assertions.assertThat(dto.getExcerpt()).isEqualTo("公开摘要");
                org.assertj.core.api.Assertions.assertThat(dto.getCover()).isEqualTo("/cover.png");
                // DTO 没有任何正文字段：结构上就不可能带出付费内容
                org.assertj.core.api.Assertions
                    .assertThat(ContentLockedDTO.class.getDeclaredFields())
                    .allSatisfy(field -> org.assertj.core.api.Assertions
                        .assertThat(field.getName())
                        .doesNotContainIgnoringCase("content")
                        .doesNotContainIgnoringCase("body")
                        .doesNotContainIgnoringCase("snapshot"));
            })
            .verifyComplete();
    }

    /** 超长摘要需截断，避免锁定响应过大。 */
    @Test
    void shouldTruncateLongExcerpt() {
        when(wxUserService.fetchByOpenid(eq(OPENID))).thenReturn(Mono.empty());
        when(unlockPersistence.findByUnlockKey(any())).thenReturn(Mono.empty());
        var longExcerpt = "摘".repeat(400);
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME)))
            .thenReturn(Mono.just(paidPostWithExcerpt(longExcerpt)));

        StepVerifier.create(service.resolveLockIfRequired(OPENID, ARTICLE_NAME))
            .assertNext(dto -> org.assertj.core.api.Assertions
                .assertThat(dto.getExcerpt())
                .hasSizeLessThanOrEqualTo(203))
            .verifyComplete();
    }

    // ==================== getStatus：放行场景也要有明确响应 ====================

    /**
     * {@code getStatus} 与拦截链路语义不同：放行场景（非付费/已解锁/博主）
     * 也要返回 DTO 而不是 empty，因为前端调它是为了拿到统一的状态结构。
     */
    @Test
    void statusShouldReturnDtoEvenWhenNotLocked() {
        StepVerifier.create(service.getStatus(OPENID, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isFalse();
                org.assertj.core.api.Assertions.assertThat(dto.getUnlocked()).isTrue();
            })
            .verifyComplete();
    }

    /** 博主本人查状态：locked=false，不要求付费。 */
    @Test
    void statusShouldBeUnlockedForOwner() {
        when(wxUserService.fetchByOpenid(eq(OPENID)))
            .thenReturn(Mono.just(wxUser(OWNER_HALO_NAME)));

        StepVerifier.create(service.getStatus(OPENID, ARTICLE_NAME))
            .assertNext(dto -> org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isFalse())
            .verifyComplete();
    }

    /** 状态查询失败时按「可阅读」返回（fail-open），宁可少收费也不阻断阅读。 */
    @Test
    void statusShouldFailOpenOnError() {
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME)))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.getStatus(OPENID, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isFalse();
                org.assertj.core.api.Assertions.assertThat(dto.getUnlocked()).isTrue();
            })
            .verifyComplete();
    }

    /**
     * 文章不存在时，状态查询仍必须返回结构化 DTO，不能是空响应体。
     *
     * <p>内部 {@code fetchPost} 会把查询失败吞成 empty，若不再兜一层，
     * 整条链就是 empty，接口返回空响应体，前端拿不到统一的状态结构。</p>
     */
    @Test
    void statusShouldReturnDtoWhenPostMissing() {
        when(client.fetch(eq(Post.class), eq(ARTICLE_NAME))).thenReturn(Mono.empty());

        StepVerifier.create(service.getStatus(OPENID, ARTICLE_NAME))
            .assertNext(dto -> {
                org.assertj.core.api.Assertions.assertThat(dto.getArticleName()).isEqualTo(ARTICLE_NAME);
                org.assertj.core.api.Assertions.assertThat(dto.getLocked()).isFalse();
                org.assertj.core.api.Assertions.assertThat(dto.getUnlocked()).isTrue();
                org.assertj.core.api.Assertions.assertThat(dto.getMessage()).isNotBlank();
            })
            .verifyComplete();
    }

    // ==================== 构造辅助 ====================

    private static com.fasterxml.jackson.databind.JsonNode enabledConfigNode() {
        try {
            return MAPPER.readTree("""
                {
                  "enabled": true,
                  "paidTagSlug": "%s",
                  "costTickets": 2,
                  "freeForOwner": true,
                  "permanent": true
                }
                """.formatted(PAID_TAG_SLUG));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode disabledConfigNode() {
        try {
            return MAPPER.readTree("""
                { "enabled": false, "paidTagSlug": "paid", "costTickets": 2 }
                """);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 带付费标签的文章。 */
    private static Post paidPost() {
        return buildPost(List.of(PAID_TAG_NAME), "公开摘要");
    }

    private static Post paidPostWithExcerpt(String excerpt) {
        return buildPost(List.of(PAID_TAG_NAME), excerpt);
    }

    /** 不带付费标签的文章。 */
    private static Post postWithoutPaidTag() {
        return buildPost(List.of("tag-normal"), "公开摘要");
    }

    private static Post buildPost(List<String> tags, String excerpt) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(ARTICLE_NAME);
        post.setMetadata(metadata);

        var spec = new Post.PostSpec();
        spec.setTitle("付费文章标题");
        spec.setSlug("paid-post-slug");
        spec.setCover("/cover.png");
        spec.setTags(tags);
        spec.setOwner(OWNER_HALO_NAME);
        post.setSpec(spec);

        var status = new Post.PostStatus();
        status.setExcerpt(excerpt);
        post.setStatus(status);
        return post;
    }

    private static Tag paidTag() {
        var tag = new Tag();
        var metadata = new Metadata();
        metadata.setName(PAID_TAG_NAME);
        tag.setMetadata(metadata);

        var spec = new Tag.TagSpec();
        spec.setDisplayName("付费");
        spec.setSlug(PAID_TAG_SLUG);
        tag.setSpec(spec);
        return tag;
    }

    private static UserPoints accountWithTickets(int contentTickets) {
        var account = new UserPoints();
        account.setWxUserId(OPENID);
        // UserPoints 的积分字段是 balance（Long），没有 points 字段
        account.setBalance(100L);
        account.setContentTickets(contentTickets);
        return account;
    }

    private static WxUser wxUser(String haloUserName) {
        var wxUser = new WxUser();
        wxUser.setHaloUserName(haloUserName);
        return wxUser;
    }
}
