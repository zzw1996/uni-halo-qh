package pro.uhalo.uni.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.SubscriptionLimitsDTO;
import pro.uhalo.uni.services.ContentTicketService;
import pro.uhalo.uni.services.MembershipService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 订阅墙判定链测试。
 *
 * <p>关键语义：empty = 放行、DTO = 拦截。fail-open 是这堵墙的安全方向——
 * 配置缺失、文章无分类、付费文章、已订阅（含继承）、站长、会员都必须放行，
 * 只剩「未订阅 + 非豁免」这一种情况被拦。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionWallServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReactiveSettingFetcher settingFetcher;
    @Mock
    private ReactiveExtensionClient client;
    @Mock
    private DigestSubscriptionService digestSubscriptionService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private ContentTicketService contentTicketService;

    @org.mockito.Mock
    private pro.uhalo.uni.services.PrivilegeService privilegeService;

    private SubscriptionWallServiceImpl service;

    @BeforeEach
    void setUp() {
        when(privilegeService.isPrivileged(any(String.class))).thenReturn(Mono.just(false));
        service = new SubscriptionWallServiceImpl(settingFetcher, client,
            digestSubscriptionService, membershipService, contentTicketService, privilegeService);
        lenient().when(contentTicketService.isPaidPost(any(Post.class)))
            .thenReturn(Mono.just(false));
        lenient().when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.empty());
        lenient().when(membershipService.loadMemberExpireAt(any(String.class)))
            .thenReturn(Mono.empty());
    }

    // ==================== 放行场景 ====================

    @Test
    void shouldPassWhenDisabled() {
        stubWallConfig(wallConfig(false, true, true, ""));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassWhenPostHasNoCategory() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of()));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassWhenPostIsPaid() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        when(contentTicketService.isPaidPost(any(Post.class))).thenReturn(Mono.just(true));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassWhenCategoryInFreeWhitelist() {
        stubWallConfig(wallConfig(true, true, true, "公告"));
        stubPost(post("post-1", List.of("cat-notice")));
        stubCategory("cat-notice", "公告");
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassForOwnerOpenid() {
        stubWallConfig(wallConfig(true, true, true, ""));
        var wxApp = MAPPER.createObjectNode().put("ownerOpenid", "openid-1");
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wxApp));
        stubPost(post("post-1", List.of("cat-1")));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassForMemberWhenMemberFree() {
        stubWallConfig(wallConfig(true, true, true, ""));
        when(membershipService.loadMemberExpireAt("openid-1"))
            .thenReturn(Mono.just(Instant.now().plusSeconds(3600)));
        stubPost(post("post-1", List.of("cat-1")));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassWhenSubscribedCategoryHits() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        stubSubscribed(List.of("cat-1"), List.of());
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .verifyComplete();
    }

    @Test
    void shouldPassWhenGuestAllowedByConfig() {
        stubWallConfig(wallConfig(true, false, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        StepVerifier.create(service.resolveLockIfRequired(null, "post-1"))
            .verifyComplete();
    }

    // ==================== 拦截场景 ====================

    @Test
    void shouldBlockGuestWithNeedLogin() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        stubCategory("cat-1", "技术分享");
        StepVerifier.create(service.resolveLockIfRequired(null, "post-1"))
            .assertNext(locked -> {
                assertThat(locked.getLocked()).isTrue();
                assertThat(locked.getType()).isEqualTo("subscription");
                assertThat(locked.getNeedLogin()).isTrue();
                assertThat(locked.getCategories()).extracting(
                        pro.uhalo.uni.dto.SubscriptionLockedDTO.CategoryInfo::getName)
                    .containsExactly("cat-1");
            })
            .verifyComplete();
    }

    @Test
    void shouldBlockUnsubscribedUserWithCategoryName() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        stubCategory("cat-1", "技术分享");
        stubSubscribed(List.of("cat-other"), List.of());
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .assertNext(locked -> {
                assertThat(locked.getLocked()).isTrue();
                assertThat(locked.getNeedLogin()).isFalse();
                assertThat(locked.getMessage()).contains("技术分享");
                assertThat(locked.getTitle()).isEqualTo("测试文章");
                assertThat(locked.getExcerpt()).isEqualTo("摘要内容");
            })
            .verifyComplete();
    }

    @Test
    void shouldBlockWithPausedFlagWhenSubscriptionPaused() {
        stubWallConfig(wallConfig(true, true, true, ""));
        stubPost(post("post-1", List.of("cat-1")));
        stubCategory("cat-1", "技术分享");
        stubSubscribed(List.of("cat-other"), List.of("cat-1"));
        StepVerifier.create(service.resolveLockIfRequired("openid-1", "post-1"))
            .assertNext(locked -> {
                assertThat(locked.getPaused()).isTrue();
                assertThat(locked.getMessage()).contains("暂停");
            })
            .verifyComplete();
    }

    // ==================== 工具 ====================

    private void stubWallConfig(ObjectNode node) {
        when(settingFetcher.get("subscriptionWallConfig")).thenReturn(Mono.just(node));
    }

    private ObjectNode wallConfig(boolean enabled, boolean gateGuests, boolean memberFree,
        String freeCategories) {
        var node = MAPPER.createObjectNode();
        node.put("enabled", enabled);
        node.put("gateGuests", gateGuests);
        node.put("memberFree", memberFree);
        node.put("freeCategories", freeCategories);
        return node;
    }

    private void stubPost(Post post) {
        when(client.fetch(eq(Post.class), eq("post-1"))).thenReturn(Mono.just(post));
    }

    private void stubCategory(String name, String displayName) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        var spec = new Category.CategorySpec();
        spec.setDisplayName(displayName);
        category.setSpec(spec);
        when(client.fetch(eq(Category.class), eq(name))).thenReturn(Mono.just(category));
    }

    private void stubSubscribed(List<String> effective, List<String> paused) {
        var subscription = DigestSubscriptionDTO.builder()
            .effectiveCategories(effective)
            .limits(SubscriptionLimitsDTO.builder().pausedCategories(paused).build())
            .build();
        when(digestSubscriptionService.load("openid-1")).thenReturn(Mono.just(subscription));
    }

    private Post post(String name, List<String> categories) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(name);
        post.setMetadata(metadata);
        var spec = new Post.PostSpec();
        spec.setTitle("测试文章");
        spec.setCover("https://cdn.example.com/cover.png");
        spec.setCategories(categories);
        post.setSpec(spec);
        var status = new Post.PostStatus();
        status.setExcerpt("摘要内容");
        post.setStatus(status);
        return post;
    }
}
