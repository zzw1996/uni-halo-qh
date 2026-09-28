package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 文章上下篇（详情页底部按钮）测试。
 *
 * <p>产品口径是「和首页主列表一致」，所以这里锁定四件事：</p>
 * <ol>
 *   <li>方向按**列表位置**：上一篇 = 列表里上面那篇（更新），下一篇 = 下面那篇（更早）；</li>
 *   <li>排序与首页一致：置顶优先，其次发布时间倒序；</li>
 *   <li>候选集按查看者的订阅分类过滤，用不了订阅口径时退回全站；</li>
 *   <li>草稿 / 回收站 / 定时发布 / 仅登录可见的文章都不能当邻居——它们在列表里也不出现。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PostRelationServiceImplTest {

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private DigestSubscriptionService digestSubscriptionService;

    @InjectMocks
    private PostRelationServiceImpl postRelationService;

    private static Post post(String name, String title, Instant publishTime) {
        return post(name, title, publishTime, true, false, Post.VisibleEnum.PUBLIC, false, List.of());
    }

    private static Post post(String name, String title, Instant publishTime, boolean publish, boolean deleted,
                             Post.VisibleEnum visible, boolean pinned, List<String> categories) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setPublishTime(publishTime);
        spec.setPublish(publish);
        spec.setDeleted(deleted);
        spec.setVisible(visible);
        spec.setPinned(pinned);
        spec.setCategories(categories);
        var post = new Post();
        post.setMetadata(metadata);
        post.setSpec(spec);
        return post;
    }

    private void stubPosts(Post... posts) {
        when(client.listAll(eq(Post.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(posts));
    }

    /** 未登录（没有订阅）：走全站口径 */
    private void stubAnonymous() {
        lenient().when(digestSubscriptionService.load(any())).thenReturn(Mono.empty());
    }

    /** 已登录且订阅了给定分类 */
    private void stubSubscription(String openid, String... categories) {
        lenient().when(digestSubscriptionService.load(openid)).thenReturn(Mono.just(
            DigestSubscriptionDTO.builder().effectiveCategories(List.of(categories)).build()));
    }

    /** 列表 [C(最新) → B(当前) → A(最早)]：上一篇 = C（上面/更新），下一篇 = A（下面/更早）。 */
    @Test
    void shouldTakePrevFromAboveAndNextFromBelow() {
        var now = Instant.now();
        var newest = post("post-c", "最新一篇", now.minus(1, ChronoUnit.HOURS));
        var middle = post("post-b", "中间一篇", now.minus(1, ChronoUnit.DAYS));
        var oldest = post("post-a", "最早一篇", now.minus(3, ChronoUnit.DAYS));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(middle));
        stubPosts(newest, middle, oldest);
        stubAnonymous();

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNotNull();
                assertThat(neighbors.prev().name()).isEqualTo("post-c");
                assertThat(neighbors.prev().title()).isEqualTo("最新一篇");
                assertThat(neighbors.next()).isNotNull();
                assertThat(neighbors.next().name()).isEqualTo("post-a");
                assertThat(neighbors.next().title()).isEqualTo("最早一篇");
            })
            .verifyComplete();
    }

    /** 列表最上面那篇没有「上一篇」，最下面那篇没有「下一篇」——对应侧返回 null。 */
    @Test
    void shouldReturnNullOnMissingSide() {
        var now = Instant.now();
        var top = post("post-c", "列表第一篇", now.minus(1, ChronoUnit.HOURS));
        var second = post("post-b", "第二篇", now.minus(1, ChronoUnit.DAYS));
        when(client.fetch(Post.class, "post-c")).thenReturn(Mono.just(top));
        stubPosts(top, second);
        stubAnonymous();

        StepVerifier.create(postRelationService.fetchNeighbors("post-c", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNull();
                assertThat(neighbors.next()).isNotNull();
                assertThat(neighbors.next().name()).isEqualTo("post-b");
            })
            .verifyComplete();
    }

    /** 置顶的老文章排在列表最上面，因此成为「上一篇」。 */
    @Test
    void shouldRespectPinnedFirstOrdering() {
        var now = Instant.now();
        var pinnedOld = post("post-pinned", "置顶的老文章", now.minus(10, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, true, List.of());
        var current = post("post-b", "当前文章", now.minus(1, ChronoUnit.DAYS));
        var older = post("post-a", "更早的一篇", now.minus(3, ChronoUnit.DAYS));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(current, older, pinnedOld);
        stubAnonymous();

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNotNull();
                assertThat(neighbors.prev().name()).isEqualTo("post-pinned");
                assertThat(neighbors.next()).isNotNull();
                assertThat(neighbors.next().name()).isEqualTo("post-a");
            })
            .verifyComplete();
    }

    /** 登录且有订阅：只在订阅分类内的文章里取邻居。 */
    @Test
    void shouldNarrowCandidatesBySubscription() {
        var now = Instant.now();
        var catTech = post("post-c", "科技一篇", now.minus(1, ChronoUnit.HOURS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        var catLife = post("post-x", "生活一篇", now.minus(2, ChronoUnit.HOURS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-life"));
        var current = post("post-b", "当前文章", now.minus(1, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        var older = post("post-a", "更早的科技一篇", now.minus(3, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(catTech, catLife, current, older);
        stubSubscription("openid-1", "cat-tech");

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", "openid-1"))
            .assertNext(neighbors -> {
                // 列表里的「生活一篇」被订阅口径过滤掉
                assertThat(neighbors.prev().name()).isEqualTo("post-c");
                assertThat(neighbors.next().name()).isEqualTo("post-a");
            })
            .verifyComplete();
    }

    /** 当前文章本身不在订阅范围内（例如点开别处分享的老文章）：退回全站，按钮不凭空消失。 */
    @Test
    void shouldFallBackToSiteWideWhenCurrentPostOutsideSubscription() {
        var now = Instant.now();
        var other = post("post-c", "订阅内的文章", now.minus(1, ChronoUnit.HOURS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        var current = post("post-b", "订阅外的文章", now.minus(1, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-life"));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(other, current);
        stubSubscription("openid-1", "cat-tech");

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", "openid-1"))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNotNull();
                assertThat(neighbors.prev().name()).isEqualTo("post-c");
            })
            .verifyComplete();
    }

    /** 订阅分类里一篇文章都没有：同样退回全站，而不是返回空。 */
    @Test
    void shouldFallBackWhenSubscriptionHasNoPosts() {
        var now = Instant.now();
        var current = post("post-b", "当前文章", now.minus(1, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        var older = post("post-a", "更早的一篇", now.minus(3, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of("cat-tech"));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(current, older);
        stubSubscription("openid-1", "cat-empty");

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", "openid-1"))
            .assertNext(neighbors -> assertThat(neighbors.next().name()).isEqualTo("post-a"))
            .verifyComplete();
    }

    /** 草稿 / 回收站 / 定时发布（发布时间在未来）都不算邻居。 */
    @Test
    void shouldExcludeUnpublishedFromNeighbors() {
        var now = Instant.now();
        var current = post("post-b", "当前文章", now.minus(1, ChronoUnit.DAYS));
        var draft = post("post-draft", "草稿", now.minus(2, ChronoUnit.HOURS), false, false,
            Post.VisibleEnum.PUBLIC, false, List.of());
        var trashed = post("post-trashed", "回收站里的", now.minus(3, ChronoUnit.HOURS), true, true,
            Post.VisibleEnum.PUBLIC, false, List.of());
        var scheduled = post("post-scheduled", "定时发布", now.plus(1, ChronoUnit.DAYS), true, false,
            Post.VisibleEnum.PUBLIC, false, List.of());
        var older = post("post-a", "更早的一篇", now.minus(5, ChronoUnit.DAYS));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(draft, trashed, scheduled, current, older);
        stubAnonymous();

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNull();
                assertThat(neighbors.next()).isNotNull();
                assertThat(neighbors.next().name()).isEqualTo("post-a");
            })
            .verifyComplete();
    }

    /** 仅登录可见（visible=INTERNAL）的文章不算邻居：它在站点列表里根本不出现。 */
    @Test
    void shouldExcludeInternalVisiblePostsFromNeighbors() {
        var now = Instant.now();
        var current = post("post-b", "当前文章", now.minus(1, ChronoUnit.DAYS));
        var internal = post("post-internal", "仅登录可见", now.minus(2, ChronoUnit.HOURS), true, false,
            Post.VisibleEnum.INTERNAL, false, List.of());
        var older = post("post-a", "更早的一篇", now.minus(5, ChronoUnit.DAYS));
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.just(current));
        stubPosts(internal, current, older);
        stubAnonymous();

        StepVerifier.create(postRelationService.fetchNeighbors("post-b", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNull();
                assertThat(neighbors.next().name()).isEqualTo("post-a");
            })
            .verifyComplete();
    }

    /** 文章不存在、或本身不在可见列表里（草稿/已删除）：不猜邻居，返回空。 */
    @Test
    void shouldReturnEmptyWhenPostNotResolvable() {
        when(client.fetch(Post.class, "missing")).thenReturn(Mono.empty());
        StepVerifier.create(postRelationService.fetchNeighbors("missing", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNull();
                assertThat(neighbors.next()).isNull();
            })
            .verifyComplete();

        var draft = post("post-draft", "草稿", Instant.now(), false, false,
            Post.VisibleEnum.PUBLIC, false, List.of());
        when(client.fetch(Post.class, "post-draft")).thenReturn(Mono.just(draft));
        StepVerifier.create(postRelationService.fetchNeighbors("post-draft", null))
            .assertNext(neighbors -> assertThat(neighbors.prev()).isNull())
            .verifyComplete();
    }

    /** 空 name 直接返回空，不去打数据库。 */
    @Test
    void shouldHandleBlankName() {
        StepVerifier.create(postRelationService.fetchNeighbors("   ", null))
            .assertNext(neighbors -> assertThat(neighbors.next()).isNull())
            .verifyComplete();
    }

    /** 查询抛异常时返回空邻居（详情页只是少一块内容，不该因此报错）。 */
    @Test
    void shouldSwallowQueryErrors() {
        when(client.fetch(Post.class, "post-b")).thenReturn(Mono.error(new IllegalStateException("boom")));
        StepVerifier.create(postRelationService.fetchNeighbors("post-b", null))
            .assertNext(neighbors -> {
                assertThat(neighbors.prev()).isNull();
                assertThat(neighbors.next()).isNull();
            })
            .verifyComplete();
    }
}
