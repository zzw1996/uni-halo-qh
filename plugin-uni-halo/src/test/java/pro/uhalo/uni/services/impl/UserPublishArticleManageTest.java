package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.services.WxContentSecurityService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投稿文章的「编辑 / 发布 / 撤回发布」三件事的服务端判定。
 *
 * <p>重点锁定「待审投稿的修改不能直接上线」：普通用户把已发布文章改完之后，改动只落到草稿，
 * 站点上仍是旧内容、要等站长重新发布——否则「先发一篇无害的过审，再改成广告」就是绕过审核的
 * 现成通道。特权（博主/管理员）与「直发」角色才自动重新发布。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserPublishArticleManageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 待审策略：默认 draft、不查内容安全（内容安全本身有独立实现）、不限每日篇数 */
    private static final String DRAFT_CONFIG = """
        {"enabled":true,"showButton":true,"minPointsToShow":0,"articleCost":50,
         "momentCost":20,"requireHaloBind":false,"dailyArticleLimit":0,"dailyMomentLimit":0,
         "contentSecurityEnabled":false,"defaultRoleMode":"draft"}
        """;

    /** 直发策略：普通用户也能自己发布/撤回 */
    private static final String DIRECT_CONFIG = """
        {"enabled":true,"showButton":true,"minPointsToShow":0,"articleCost":50,
         "momentCost":20,"requireHaloBind":false,"dailyArticleLimit":0,"dailyMomentLimit":0,
         "contentSecurityEnabled":false,"defaultRoleMode":"direct"}
        """;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private PointsPersistence pointsPersistence;

    @Mock
    private HaloPostPublisher postPublisher;

    @Mock
    private ReactiveExtensionClient extensionClient;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private WxContentSecurityService contentSecurityService;

    @Mock
    private PrivilegeService privilegeService;

    @Mock
    private DigestHaloClient haloClient;

    @InjectMocks
    private UserPublishServiceImpl userPublishService;

    private void stubConfig(String json) throws Exception {
        lenient().when(settingFetcher.get("userPublishConfig"))
            .thenReturn(Mono.just(MAPPER.readTree(json)));
        // 无 ownerOpenid：特权判定只走「角色含 admin」这条
        lenient().when(settingFetcher.get("wxAppConfig"))
            .thenReturn(Mono.just(MAPPER.readTree("{}")));
    }

    private void stubUser(String haloUserName) {
        var user = new WxUser();
        user.setOpenid("openid-1");
        user.setHaloUserName(haloUserName);
        lenient().when(wxUserService.fetchByOpenid("openid-1")).thenReturn(Mono.just(user));
    }

    private void stubPrivileged(boolean privileged) {
        lenient().when(privilegeService.rolesOf(any()))
            .thenReturn(Mono.just(privileged ? Set.of("admin") : Set.of()));
    }

    /** 一篇文章的读结果（core 组接口）：owner=alice，published 决定发布态 */
    private void stubArticle(String name, String owner, boolean published,
                             String headSnapshot, String releaseSnapshot) throws Exception {
        when(haloClient.get(contains("/apis/content.halo.run/v1alpha1/posts/"), any()))
            .thenReturn(Mono.just(MAPPER.readTree("""
                {"metadata":{"name":"%s","creationTimestamp":"2026-10-01T02:00:00Z"},
                 "spec":{"title":"原标题","slug":"old-slug","owner":"%s","publish":%s,
                         "headSnapshot":"%s","releaseSnapshot":"%s","allowComment":true,
                         "categories":["category-jQopC"],"tags":["travel"]},
                 "status":{"permalink":"/archives/old-slug"}}
                """.formatted(name, owner, published, headSnapshot, releaseSnapshot))));
    }

    private void stubHeadContent(String raw) throws Exception {
        lenient().when(haloClient.get(contains("/head-content"), any()))
            .thenReturn(Mono.just(MAPPER.readTree("{\"raw\":\"" + raw + "\"}")));
    }

    private void stubUpdateResult(String state, String message) {
        lenient().when(postPublisher.update(anyString(), any(), anyBoolean(), anyInt()))
            .thenReturn(Mono.just(HaloPostPublishResultDTO.builder()
                .postName("post-1")
                .permalink("/archives/new-slug")
                .state(state)
                .message(message)
                .build()));
    }

    private static UserPublishArticleRequest editRequest() {
        var request = new UserPublishArticleRequest();
        request.setTitle("改过的标题");
        request.setContent("## 改过的正文");
        request.setCover("/upload/new-cover.png");
        request.setCategories(List.of("category-jQopC"));
        request.setTags(List.of("travel"));
        return request;
    }

    /** 详情：正文回填 Markdown 原文，并标出「已发布但有未生效的修改」。 */
    @Test
    void getArticleShouldReturnRawContentAndPendingFlag() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        // head 与 release 不一致：改过但没发布生效
        stubArticle("post-1", "alice", true, "snap-head", "snap-release");
        stubHeadContent("## 正文原文");

        StepVerifier.create(userPublishService.getArticle("openid-1", "post-1"))
            .assertNext(detail -> {
                assertThat(detail.getName()).isEqualTo("post-1");
                assertThat(detail.getTitle()).isEqualTo("原标题");
                assertThat(detail.getContent()).isEqualTo("## 正文原文");
                assertThat(detail.isPublished()).isTrue();
                assertThat(detail.isHasPendingChanges()).isTrue();
                assertThat(detail.getCategories()).containsExactly("category-jQopC");
                assertThat(detail.getTags()).containsExactly("travel");
            })
            .verifyComplete();
    }

    /**
     * 待审用户改已发布的文章：只保存草稿，不重新发布（站点上仍是旧内容），也不扣积分。
     */
    @Test
    void draftRoleEditOfPublishedArticleShouldNotRepublish() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", true, "snap-head", "snap-head");
        stubUpdateResult("SAVED", "修改已保存，尚未发布生效");

        StepVerifier.create(userPublishService.updateArticle("openid-1", "post-1", editRequest()))
            .assertNext(result -> {
                assertThat(result.getStatusText()).contains("需站长重新发布");
                assertThat(result.getPointsCost()).isZero();
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(Boolean.class);
        verify(postPublisher).update(org.mockito.ArgumentMatchers.eq("post-1"), any(),
            captor.capture(), anyInt());
        // 关键：false —— 改动不该越过站长直接上线
        assertThat(captor.getValue()).isFalse();
    }

    /** 特权用户改已发布的文章：保存后自动重新发布，改动立即生效。 */
    @Test
    void privilegedEditOfPublishedArticleShouldRepublish() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("admin-user");
        stubPrivileged(true);
        stubArticle("post-1", "admin-user", true, "snap-head", "snap-head");
        stubUpdateResult("PUBLISHED", "修改已保存并已发布");

        StepVerifier.create(userPublishService.updateArticle("openid-1", "post-1", editRequest()))
            .assertNext(result -> {
                assertThat(result.getStatusText()).isEqualTo("修改已保存并已生效");
                assertThat(result.getPermalink()).isEqualTo("/archives/new-slug");
            })
            .verifyComplete();

        verify(postPublisher).update(anyString(), any(), org.mockito.ArgumentMatchers.eq(true),
            anyInt());
    }

    /**
     * 保存修改要传全字段，且作者不变、slug 不变。
     *
     * <p>作者不变：站长代改别人的文章时署名仍应是原作者。slug 不变：它是永久链接的一部分，
     * 跟着标题改会让已经分享出去的链接全部 404。</p>
     */
    @Test
    void updateShouldKeepOwnerAndSlug() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("admin-user");
        stubPrivileged(true);
        stubArticle("post-1", "alice", false, "snap-head", "");
        stubUpdateResult("SAVED", "修改已保存，尚未发布生效");

        StepVerifier.create(userPublishService.updateArticle("openid-1", "post-1", editRequest()))
            .expectNextCount(1)
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(HaloPostPublishDTO.class);
        verify(postPublisher).update(anyString(), captor.capture(), anyBoolean(), anyInt());
        var dto = captor.getValue();
        assertThat(dto.getOwner()).isEqualTo("alice");
        assertThat(dto.getSlug()).isEqualTo("old-slug");
        assertThat(dto.getTitle()).isEqualTo("改过的标题");
        assertThat(dto.getCover()).isEqualTo("/upload/new-cover.png");
    }

    /** 普通用户改别人的文章：拦下，且不发出任何保存请求。 */
    @Test
    void regularUserShouldNotEditOthersArticle() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "bob", true, "snap-head", "snap-head");

        StepVerifier.create(userPublishService.updateArticle("openid-1", "post-1", editRequest()))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("只能操作自己发布的文章"))
            .verify();

        verify(postPublisher, never()).update(anyString(), any(), anyBoolean(), anyInt());
    }

    /** 待审投稿不能自己上线：这就是「审核」存在的意义。 */
    @Test
    void draftRoleShouldNotPublishOwnArticle() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", false, "snap-head", "");

        StepVerifier.create(userPublishService.publishArticleNow("openid-1", "post-1"))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("需要站长审核后发布"))
            .verify();

        verify(postPublisher, never()).republish(anyString(), anyInt());
    }

    /** 特权用户可以把草稿发上线（站长/管理员本来就能在后台做这件事）。 */
    @Test
    void privilegedUserShouldPublishOwnArticle() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("admin-user");
        stubPrivileged(true);
        stubArticle("post-1", "admin-user", false, "snap-head", "");
        lenient().when(postPublisher.republish(anyString(), anyInt()))
            .thenReturn(Mono.just(HaloPostPublishResultDTO.builder()
                .postName("post-1").permalink("/archives/old-slug").state("PUBLISHED")
                .message("已发布").build()));

        StepVerifier.create(userPublishService.publishArticleNow("openid-1", "post-1"))
            .assertNext(result -> {
                assertThat(result.getStatusText()).isEqualTo("已发布");
                assertThat(result.getMode()).isEqualTo("direct");
            })
            .verifyComplete();

        verify(postPublisher).republish("post-1", 15);
    }

    /** 已经发布且没有待生效修改：直接当成功返回，不重复触发发布。 */
    @Test
    void publishingAnAlreadyLiveArticleShouldBeNoop() throws Exception {
        stubConfig(DIRECT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", true, "snap-head", "snap-head");

        StepVerifier.create(userPublishService.publishArticleNow("openid-1", "post-1"))
            .assertNext(result -> assertThat(result.getStatusText()).contains("已是发布状态"))
            .verifyComplete();

        verify(postPublisher, never()).republish(anyString(), anyInt());
    }

    /** 待审投稿不能自行撤回：撤回后自己也就发不回来了，那是一条死路。 */
    @Test
    void draftRoleShouldNotUnpublishOwnArticle() throws Exception {
        stubConfig(DRAFT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", true, "snap-head", "snap-head");

        StepVerifier.create(userPublishService.unpublishArticle("openid-1", "post-1"))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("暂不能自行撤回"))
            .verify();

        verify(postPublisher, never()).unpublish(anyString());
    }

    /** 直发用户撤回自己的文章：走 Halo 的 unpublish，文章回到草稿态。 */
    @Test
    void directRoleShouldUnpublishOwnArticle() throws Exception {
        stubConfig(DIRECT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", true, "snap-head", "snap-head");
        lenient().when(postPublisher.unpublish("post-1")).thenReturn(Mono.empty());

        StepVerifier.create(userPublishService.unpublishArticle("openid-1", "post-1"))
            .verifyComplete();

        verify(postPublisher).unpublish("post-1");
    }

    /** 本来就没发布：不必调用撤回，直接给出原因。 */
    @Test
    void unpublishOnDraftShouldFailFast() throws Exception {
        stubConfig(DIRECT_CONFIG);
        stubUser("alice");
        stubPrivileged(false);
        stubArticle("post-1", "alice", false, "snap-head", "");

        StepVerifier.create(userPublishService.unpublishArticle("openid-1", "post-1"))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("不是发布状态"))
            .verify();

        verify(postPublisher, never()).unpublish(anyString());
    }
}
