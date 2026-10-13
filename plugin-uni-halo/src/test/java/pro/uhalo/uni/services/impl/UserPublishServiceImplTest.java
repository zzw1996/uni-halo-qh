package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.services.WxContentSecurityService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 投稿状态的服务端判定。
 *
 * <p>重点锁定「特权用户不受门槛限制」：博主/站点所有者不该被自己设的积分门槛挡住入口
 * ——曾经 {@code visible} 只看余额，管理员积分不足 600 时连投稿入口都看不到。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserPublishServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 门槛 600、单次 600：普通用户积分不足时入口不可见，特权用户豁免 */
    private static final String CONFIG_JSON = """
        {"enabled":true,"showButton":true,"minPointsToShow":600,
         "articleCost":600,"momentCost":600,"requireHaloBind":false,
         "dailyArticleLimit":1,"dailyMomentLimit":1}
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

    /** 特权判定已收口到 PrivilegeService（本站所有者/管理员），这里按身份返回结论 */
    @Mock
    private PrivilegeService privilegeService;

    @Mock
    private DigestHaloClient haloClient;

    @InjectMocks
    private UserPublishServiceImpl userPublishService;

    private void stubConfig() throws Exception {
        stubConfig(CONFIG_JSON);
    }

    private void stubConfig(String json) throws Exception {
        lenient().when(settingFetcher.get("userPublishConfig"))
            .thenReturn(Mono.just(MAPPER.readTree(json)));
        // 无 ownerOpenid：本组用例只走「角色含 admin」这条特权判定
        lenient().when(settingFetcher.get("wxAppConfig"))
            .thenReturn(Mono.just(MAPPER.readTree("{}")));
        lenient().when(pointsPersistence.listRecordsByOpenid(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(List.<PointsRecord>of()));
    }

    private void stubAccount(long balance) {
        var account = new UserPoints();
        account.setBalance(balance);
        when(pointsPersistence.getOrCreateAccount("openid-1")).thenReturn(Mono.just(account));
    }

    private void stubUser(String haloUserName) {
        var user = new WxUser();
        user.setOpenid("openid-1");
        user.setHaloUserName(haloUserName);
        when(wxUserService.fetchByOpenid("openid-1")).thenReturn(Mono.just(user));
    }

    /** 把该用户标记为特权（站点所有者/管理员）或普通用户 */
    private void stubPrivileged(boolean privileged) {
        lenient().when(privilegeService.rolesOf(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(privileged ? java.util.Set.of("admin") : java.util.Set.of()));
        lenient().when(privilegeService.isPrivileged(
                org.mockito.ArgumentMatchers.any(WxUser.class), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(privileged));
    }

    /** 管理员积分不足，也必须能看到入口、能发布，且不显示「还差多少积分」。 */
    @Test
    void privilegedUserShouldBypassPointsThreshold() throws Exception {
        stubConfig();
        stubAccount(0L);
        stubUser("admin-user");
        stubPrivileged(true);

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isTrue();
                // 关键：门槛对特权用户不生效
                assertThat(status.isVisible()).isTrue();
                assertThat(status.getPointsToUnlock()).isZero();
                assertThat(status.isCanPublishMoment()).isTrue();
                assertThat(status.isCanPublishArticle()).isTrue();
                assertThat(status.getMomentDisabledReason()).isEmpty();
                assertThat(status.getArticleDisabledReason()).isEmpty();
            })
            .verifyComplete();
    }

    /** 普通用户积分不足：入口不可见，且给出「还差多少」与不可发布的原因（行为不能被上面的修复带偏）。 */
    @Test
    void regularUserShouldStillBeBlockedByThreshold() throws Exception {
        stubConfig();
        stubAccount(100L);
        stubUser("normal-user");
        stubPrivileged(false);

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isFalse();
                assertThat(status.isVisible()).isFalse();
                assertThat(status.getPointsToUnlock()).isEqualTo(500);
                assertThat(status.isCanPublishMoment()).isFalse();
                assertThat(status.getMomentDisabledReason()).contains("积分不足");
            })
            .verifyComplete();
    }

    /** 普通用户积分达标：入口可见、可发布。 */
    @Test
    void regularUserWithEnoughPointsShouldSeeEntry() throws Exception {
        stubConfig();
        stubAccount(600L);
        stubUser("normal-user");
        stubPrivileged(false);

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isFalse();
                assertThat(status.isVisible()).isTrue();
                assertThat(status.getPointsToUnlock()).isZero();
                assertThat(status.isCanPublishMoment()).isTrue();
            })
            .verifyComplete();
    }

    /**
     * 投稿必须把「作者手选的分类/标签 + slug」原样交给发布器。
     *
     * <p>此前投稿只发标题/正文/封面：分类标签全丢（作者选了也不生效），slug 为空
     * （站点上其他路径都带 slug）。这里锁定这两处不再回退。</p>
     */
    @Test
    void articlePublishShouldCarrySlugCategoriesAndTags() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"direct","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("admin-user");
        stubPrivileged(true);
        when(postPublisher.publish(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Mono.just(pro.uhalo.uni.dto.HaloPostPublishResultDTO.builder()
                .postName("post-name-1")
                .permalink("/archives/hello")
                .state("PUBLISHED")
                .build()));

        var request = new UserPublishArticleRequest();
        request.setTitle("黄河岸边的盛夏");
        request.setContent("## 小标题\n\n正文");
        // 界面传的是 metadata.name；这里混入重复项、空白项与超量项，验证服务端归一化
        request.setCategories(List.of("category-jQopC", "手册", "   ", "category-jQopC",
            "ai-ml", "tools"));
        request.setTags(List.of("travel", "travel", "family", "birthday", "parenting", "ios", "duo"));

        var captor = org.mockito.ArgumentCaptor.forClass(pro.uhalo.uni.dto.HaloPostPublishDTO.class);
        StepVerifier.create(userPublishService.publishArticle("openid-1", request))
            .assertNext(result -> {
                assertThat(result.getMode()).isEqualTo("direct");
                assertThat(result.getStatusText()).isEqualTo("已发布");
            })
            .verifyComplete();

        org.mockito.Mockito.verify(postPublisher)
            .publish(captor.capture(), org.mockito.ArgumentMatchers.anyInt());
        var dto = captor.getValue();
        // 中文标题取不到 ASCII 片段，回退 post-日期-随机后缀
        assertThat(dto.getSlug()).matches("^post-\\d{8}-[0-9a-f]{5}$");
        assertThat(dto.getCategoryNames()).containsExactly("category-jQopC", "手册", "ai-ml");
        assertThat(dto.getTagNames())
            .containsExactly("travel", "family", "birthday", "parenting", "ios");
    }

    /**
     * 「我的投稿」：按 owner 过滤拉取，已发布/待审都要在，回收站里的不出现。
     *
     * <p>过滤放在 Halo 侧是关键——列表里只会出现自己的文章，客户端无须再筛。</p>
     */
    @Test
    void listMyArticlesShouldMapStatesAndHideRecycled() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"draft","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("admin-user");
        stubPrivileged(false);

        // 真实响应是 ListedPost：文章本体嵌在 post 里（这里连「别人的文章」一起塞进去，
        // 验证归属过滤确实生效——Halo 的 owner 查询参数是不存在的，传了也会被忽略）
        var body = """
            {"items":[
              {"post":{"metadata":{"name":"p1","creationTimestamp":"2026-10-08T02:00:00Z"},
                       "spec":{"title":"我的已发布","slug":"live-one","publish":true,
                               "owner":"admin-user","publishTime":"2026-10-08T02:00:00Z",
                               "deleted":false,"categories":["category-jQopC"],"tags":["travel"]},
                       "status":{"permalink":"/archives/live-one"}}},
              {"post":{"metadata":{"name":"p2","creationTimestamp":"2026-10-08T03:00:00Z"},
                       "spec":{"title":"我的待审","slug":"pending-one","publish":false,
                               "owner":"admin-user","deleted":false}}},
              {"post":{"metadata":{"name":"p3"},
                       "spec":{"title":"回收站里的","publish":true,"owner":"admin-user","deleted":true}}},
              {"post":{"metadata":{"name":"p4"},
                       "spec":{"title":"别人的文章","publish":true,"owner":"someone-else"}}}
            ]}
            """;
        when(haloClient.get(org.mockito.ArgumentMatchers.argThat(
                path -> !path.contains("owner=")), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.readTree(body)));

        StepVerifier.create(userPublishService.listMyArticles("openid-1"))
            .assertNext(items -> {
                assertThat(items).hasSize(2);
                assertThat(items.get(0).getName()).isEqualTo("p1");
                assertThat(items.get(0).getTitle()).isEqualTo("我的已发布");
                assertThat(items.get(0).getState()).isEqualTo("published");
                assertThat(items.get(0).getStateText()).isEqualTo("已发布");
                assertThat(items.get(0).getPermalink()).isEqualTo("/archives/live-one");
                assertThat(items.get(0).getCategories()).containsExactly("category-jQopC");
                assertThat(items.get(0).getTags()).containsExactly("travel");
                assertThat(items.get(1).getState()).isEqualTo("pending");
                assertThat(items.get(1).getStateText()).contains("待审");
            })
            .verifyComplete();
    }

    /** 多页：第一页 hasNext=true 必须继续翻，否则站长的文章会被截断在第一页。 */
    @Test
    void listMyArticlesShouldFollowPagination() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"draft","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("admin-user");
        stubPrivileged(false);
        when(haloClient.get(org.mockito.ArgumentMatchers.contains("page=0"),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.readTree("""
                {"hasNext":true,"items":[
                  {"post":{"metadata":{"name":"a1"},
                           "spec":{"title":"第一页的","owner":"admin-user","publish":true}}}
                ]}
                """)));
        when(haloClient.get(org.mockito.ArgumentMatchers.contains("page=1"),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.readTree("""
                {"hasNext":false,"items":[
                  {"post":{"metadata":{"name":"a2"},
                           "spec":{"title":"第二页的","owner":"admin-user","publish":false}}}
                ]}
                """)));

        StepVerifier.create(userPublishService.listMyArticles("openid-1"))
            .assertNext(items -> {
                assertThat(items).hasSize(2);
                assertThat(items).extracting(
                        pro.uhalo.uni.dto.UserPublishArticleDTO::getTitle)
                    .containsExactly("第一页的", "第二页的");
            })
            .verifyComplete();
    }

    /** 普通用户删别人的文章：自己的列表里没有这篇，必须被拦下，且不能发出回收请求。 */
    @Test
    void regularUserShouldNotDeleteOthersArticle() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"draft","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("normal-user");
        stubPrivileged(false);
        when(haloClient.get(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.readTree("{\"items\":[{\"post\":"
                + "{\"metadata\":{\"name\":\"mine\"},"
                + "\"spec\":{\"title\":\"我的\",\"owner\":\"normal-user\",\"publish\":true}}}]}")));

        StepVerifier.create(userPublishService.deleteArticle("openid-1", "others-post"))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("只能删除自己发布的文章"))
            .verify();

        org.mockito.Mockito.verify(haloClient, org.mockito.Mockito.never())
            .put(org.mockito.ArgumentMatchers.contains("/recycle"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 本人的文章：走 Halo 的 /recycle（进回收站），不是物理删除。 */
    @Test
    void ownerShouldRecycleOwnArticle() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"draft","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("normal-user");
        stubPrivileged(false);
        when(haloClient.get(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.readTree("{\"items\":[{\"post\":"
                + "{\"metadata\":{\"name\":\"mine\"},"
                + "\"spec\":{\"title\":\"我的\",\"owner\":\"normal-user\",\"publish\":true}}}]}")));
        when(haloClient.put(org.mockito.ArgumentMatchers.contains("/recycle"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.createObjectNode()));

        StepVerifier.create(userPublishService.deleteArticle("openid-1", "mine"))
            .verifyComplete();

        org.mockito.Mockito.verify(haloClient).put(
            org.mockito.ArgumentMatchers.contains("posts/mine/recycle"),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 博主/管理员代删：不校验归属，直接回收。 */
    @Test
    void privilegedShouldRecycleAnyArticle() throws Exception {
        stubConfig("""
            {"enabled":true,"defaultRoleMode":"direct","articleCost":50,"minPointsToShow":0,
             "dailyArticleLimit":0,"requireHaloBind":false}
            """);
        stubUser("admin-user");
        stubPrivileged(true);
        when(haloClient.put(org.mockito.ArgumentMatchers.contains("/recycle"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(MAPPER.createObjectNode()));

        StepVerifier.create(userPublishService.deleteArticle("openid-1", "someone-else-post"))
            .verifyComplete();

        org.mockito.Mockito.verify(haloClient).put(
            org.mockito.ArgumentMatchers.contains("posts/someone-else-post/recycle"),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(haloClient, org.mockito.Mockito.never())
            .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
