package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.PostNeighborDTO;
import pro.uhalo.uni.dto.PostNeighborsDTO;
import pro.uhalo.uni.services.PostRelationService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 文章相邻关系实现：按**首页主列表的口径**取当前文章上下相邻的两篇。
 *
 * <p>首页列表的口径是「置顶优先 + 发布时间倒序」，登录且订阅了分类时只显示订阅分类里的文章
 * （见小程序首页的多路合并）。这里逐条对齐：</p>
 * <ol>
 *   <li>候选集：未登录 → 全站已发布文章；已登录且有订阅 → 只保留分类命中订阅集合的文章；</li>
 *   <li>订阅口径用不了（订阅为空 / 订阅分类下没有内容 / 当前文章不在订阅范围内）→ 退回全站，
 *       否则用户读一篇不在订阅里的文章时，上下篇按钮会凭空消失；</li>
 *   <li>排序：置顶优先，其次发布时间倒序；</li>
 *   <li>取位：上一篇 = 列表里上面那篇，下一篇 = 下面那篇。</li>
 * </ol>
 *
 * <p><b>为什么不用范围查询</b>：{@code spec.publishTime} 是 Instant，索引比较值的格式随版本可能变化，
 * 而「置顶优先」本身也只能在内存里排（Halo 的排序不会把 pinned 当主键），所以统一用
 * 「按发布时间倒序取一页 + 内存排序」这种与索引格式无关的写法，并用 {@link #NEIGHBOR_SCAN_LIMIT} 兜住扫描量。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostRelationServiceImpl implements PostRelationService {

    /** 邻居查找最多扫描的文章数（按发布时间倒序的头部） */
    static final int NEIGHBOR_SCAN_LIMIT = 500;

    private final ReactiveExtensionClient client;
    private final DigestSubscriptionService digestSubscriptionService;

    /** 与首页一致：置顶优先，其次发布时间倒序 */
    static final Comparator<Post> FEED_ORDER = (a, b) -> {
        var pinnedA = Boolean.TRUE.equals(a.getSpec().getPinned());
        var pinnedB = Boolean.TRUE.equals(b.getSpec().getPinned());
        if (pinnedA != pinnedB) {
            return pinnedA ? -1 : 1;
        }
        return b.getSpec().getPublishTime().compareTo(a.getSpec().getPublishTime());
    };

    @Override
    public Mono<PostNeighborsDTO> fetchNeighbors(String postName, String openid) {
        if (!StringUtils.hasText(postName)) {
            return Mono.just(PostNeighborsDTO.empty());
        }
        var normalizedName = postName.trim();
        return client.fetch(Post.class, normalizedName)
            .filter(post -> post.getSpec() != null && post.getSpec().getPublishTime() != null)
            .flatMap(post -> visiblePosts()
                .collectList()
                .flatMap(all -> narrowBySubscription(all, post, openid))
                .map(ordered -> toNeighbors(ordered, normalizedName)))
            .defaultIfEmpty(PostNeighborsDTO.empty())
            .onErrorResume(ex -> {
                // 邻居只是详情页底部的辅助内容：算不出来就不显示，绝不能因此让详情页报错
                log.warn("查询文章上下篇失败（详情页将不显示该模块）：post={}", normalizedName, ex);
                return Mono.just(PostNeighborsDTO.empty());
            });
    }

    /** 已发布且公众可见的文章（先按发布时间倒序取一页，顺序稍后按首页口径重排） */
    private Flux<Post> visiblePosts() {
        return client.listAll(Post.class, new ListOptions(),
                Sort.by("spec.publishTime").descending())
            .filter(candidate -> isVisibleToPublic(candidate, Instant.now()))
            .take(NEIGHBOR_SCAN_LIMIT);
    }

    /**
     * 按查看者的订阅收窄候选集；用不了订阅口径时退回全站。
     *
     * <p>四种「用不了」都退回全站：没登录、没订阅、订阅分类里没有内容，
     * 以及当前文章本身不在订阅范围内（那样订阅列表里根本没有这篇文章，谈不上上下相邻）。</p>
     */
    private Mono<List<Post>> narrowBySubscription(List<Post> all, Post current, String openid) {
        if (!StringUtils.hasText(openid) || all.isEmpty()) {
            return Mono.just(sortedByFeedOrder(all));
        }
        return digestSubscriptionService.load(openid.trim())
            .map(DigestSubscriptionDTO::getEffectiveCategories)
            .defaultIfEmpty(List.of())
            .map(subscribed -> {
                if (subscribed.isEmpty()) {
                    return sortedByFeedOrder(all);
                }
                var narrowed = all.stream()
                    .filter(candidate -> hitsSubscription(candidate, subscribed))
                    .sorted(FEED_ORDER)
                    .toList();
                if (narrowed.isEmpty() || !containsName(narrowed, current)) {
                    return sortedByFeedOrder(all);
                }
                return narrowed;
            });
    }

    private static List<Post> sortedByFeedOrder(List<Post> all) {
        return all.stream().sorted(FEED_ORDER).toList();
    }

    /** 文章的分类与订阅集合是否有交集（首页的「按分类逐个取文章」等价于这个交集判断） */
    static boolean hitsSubscription(Post post, Collection<String> subscribed) {
        var categories = post.getSpec() == null ? null : post.getSpec().getCategories();
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        var wanted = new HashSet<>(subscribed);
        return categories.stream().anyMatch(wanted::contains);
    }

    private static boolean containsName(List<Post> posts, Post target) {
        var name = target.getMetadata() == null ? null : target.getMetadata().getName();
        if (!StringUtils.hasText(name)) {
            return false;
        }
        return posts.stream().anyMatch(post -> post.getMetadata() != null
            && name.equals(post.getMetadata().getName()));
    }

    /**
     * 是否算作「公众可见的已发布文章」。
     *
     * <p>五个条件缺一不可：有发布时间、不在回收站、已发布（publish 标记）、
     * 发布时间不在未来（定时发布的文章此刻还打不开）、可见性为公开
     * （{@code spec.visible=INTERNAL} 的文章只在后台/登录可见，站点列表里根本不出现）。</p>
     */
    static boolean isVisibleToPublic(Post post, Instant now) {
        if (post == null || post.getSpec() == null) {
            return false;
        }
        var spec = post.getSpec();
        if (spec.getPublishTime() == null || spec.getPublishTime().isAfter(now)) {
            return false;
        }
        if (Boolean.TRUE.equals(spec.getDeleted())) {
            return false;
        }
        if (!Boolean.TRUE.equals(spec.getPublish())) {
            return false;
        }
        // 空值按公开处理：Halo 建稿不写 visible 时等效于 PUBLIC
        var visible = spec.getVisible();
        return visible == null || visible == Post.VisibleEnum.PUBLIC;
    }

    /**
     * 取列表里上下相邻的两篇。
     *
     * <p>方向按**列表位置**而不是时间：首页是「最新在前」，所以列表里上面那篇更新、下面那篇更早。
     * 产品口径就是「按钮指向列表里挨着的那两篇」，因此 index-1 是上一篇、index+1 是下一篇。</p>
     */
    static PostNeighborsDTO toNeighbors(List<Post> ordered, String currentName) {
        var index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            var post = ordered.get(i);
            var name = post.getMetadata() == null ? null : post.getMetadata().getName();
            if (currentName.equals(name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // 当前文章不在候选集里：不猜邻居，返回空（前端隐藏这两块按钮）
            return PostNeighborsDTO.empty();
        }
        var prev = index - 1 >= 0 ? toNeighbor(ordered.get(index - 1)) : null;
        var next = index + 1 < ordered.size() ? toNeighbor(ordered.get(index + 1)) : null;
        return new PostNeighborsDTO(prev, next);
    }

    private static PostNeighborDTO toNeighbor(Post post) {
        var name = post.getMetadata() == null ? null : post.getMetadata().getName();
        if (!StringUtils.hasText(name) || post.getSpec() == null) {
            return null;
        }
        var title = StringUtils.hasText(post.getSpec().getTitle()) ? post.getSpec().getTitle() : "未命名文章";
        return new PostNeighborDTO(name, title);
    }
}
