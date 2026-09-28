package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestCategoryRankingDTO;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.services.DigestSubscriptionStatsService;
import pro.uhalo.uni.services.DigestTaxonomyService;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类订阅热度：把「每个分类被多少用户主动订阅」算出来。
 *
 * <p><b>为什么是全表聚合</b>：订阅记录是「一个用户一条」（{@code DigestSubscription}），
 * 分类存在记录内部的数组里，Halo 只给 openid 建了索引，没法按分类查。所以只能把记录全读出来在内存里累加——
 * 这与插件里既有的统计口径一致（{@code PushDispatchService} 的推送派发、
 * {@code RankServiceImpl} 的用户榜都是这么做的，见各自类注释）。</p>
 *
 * <p><b>临界点</b>：记录里带着 keywords / sources 数组，量大了全表读的内存开销会上去。
 * 到了十万级用户时，应改成分批读（{@code client.listBy(..., PageRequest)}）边读边累加，
 * 或者另建一份「分类 → 订阅数」的计数记录在写入时维护。当前量级（与用户榜同量级）
 * 用「全表读 + 5 分钟缓存」足够。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestSubscriptionStatsServiceImpl implements DigestSubscriptionStatsService {

    /**
     * 聚合结果缓存时长。
     *
     * <p>比用户榜（60 秒）长：热度是「发现」页的排序依据，用户不会因为它晚几分钟而受影响，
     * 但每次进页面都全表扫一遍就纯属浪费。</p>
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** 榜单上限：站点分类不会有这么多，纯粹是防御脏数据把响应撑大 */
    private static final int MAX_ITEMS = 200;

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveExtensionClient client;
    private final DigestTaxonomyService taxonomyService;

    /** 只有一份聚合结果，用 volatile 单值缓存即可（并发时最坏情况是多算一次，不影响正确性） */
    private volatile Cached cached;

    @Override
    public Mono<DigestCategoryRankingDTO> categoryRanking() {
        // 用 defer 把缓存判断推迟到「订阅时」：否则同一个请求里先取两个 Mono 再依次订阅，
        // 两次都会在组装阶段看到空缓存，缓存就白做了（这个坑是单测抓出来的）
        return Mono.defer(() -> {
            var current = cached;
            if (current != null && current.expireAt().isAfter(Instant.now())) {
                return Mono.just(current.data());
            }
            return aggregate()
                .doOnNext(data -> cached = new Cached(Instant.now().plus(CACHE_TTL), data))
                .onErrorResume(ex -> {
                    // 聚合失败不能把「发现」页打挂：有旧数据就给旧数据，没有就返回空榜
                    log.warn("[UniHalo][Digest] 分类订阅热度聚合失败：{}", ex.toString());
                    var fallback = cached;
                    return Mono.just(fallback != null ? fallback.data() : empty());
                });
        });
    }

    private Mono<DigestCategoryRankingDTO> aggregate() {
        return client.listAll(DigestSubscription.class, new ListOptions(), null)
            .filter(DigestSubscriptionStatsServiceImpl::countsAsSubscriber)
            .reduce(new Tally(), (tally, subscription) -> {
                // 用「自己订阅的」口径函数（含老数据回落到 categories），不在这里重写一遍规则：
                // 首页筛选、推送目标、热度榜三处口径必须一致
                var own = DigestSubscriptionService.ownCategoriesOf(subscription);
                if (own.isEmpty()) {
                    // 只继承了别人的分类、自己一个都没订：不算「主动订阅用户」，也不进任何分类的计数
                    return tally;
                }
                tally.subscribers++;
                for (var category : own) {
                    if (StringUtils.hasText(category)) {
                        tally.counts.merge(category.trim(), 1, Integer::sum);
                    }
                }
                return tally;
            })
            .flatMap(this::toDto);
    }

    /**
     * 这条记录算不算「一个订阅用户」。
     *
     * <p>排除两类：用户自己关掉了订阅（{@code enabled=false}），以及没有 openid 的记录。
     * 后者是历史/预建数据，算进去会让热度虚高。</p>
     */
    private static boolean countsAsSubscriber(DigestSubscription subscription) {
        if (subscription == null || Boolean.FALSE.equals(subscription.getEnabled())) {
            return false;
        }
        return StringUtils.hasText(subscription.getWxOpenid());
    }

    private Mono<DigestCategoryRankingDTO> toDto(Tally tally) {
        var sorted = tally.counts.entrySet().stream()
            .sorted(rankingOrder())
            .limit(MAX_ITEMS)
            .toList();
        return taxonomyService.categoryLabels()
            .map(labels -> build(sorted, labels, tally.subscribers))
            .onErrorResume(ex -> {
                // 拿不到显示名不该让榜单消失：label 回落成 name，前端还会按站点分类清单过滤
                log.warn("[UniHalo][Digest] 读取分类显示名失败，热度榜回落为 name：{}", ex.toString());
                return Mono.just(build(sorted, Map.of(), tally.subscribers));
            });
    }

    private static DigestCategoryRankingDTO build(List<Map.Entry<String, Integer>> sorted,
        Map<String, String> labels, int subscribers) {
        var items = new ArrayList<DigestCategoryRankingDTO.Item>();
        for (var entry : sorted) {
            items.add(DigestCategoryRankingDTO.Item.builder()
                .name(entry.getKey())
                .label(labels.getOrDefault(entry.getKey(), entry.getKey()))
                .subscribers(entry.getValue())
                .build());
        }
        return DigestCategoryRankingDTO.builder()
            .items(List.copyOf(items))
            .totalSubscribers(subscribers)
            .updatedAt(Instant.now().atZone(ZoneId.systemDefault()).format(TIME_FORMAT))
            .metric("own")
            .build();
    }

    private static DigestCategoryRankingDTO empty() {
        return DigestCategoryRankingDTO.builder()
            .items(List.of())
            .totalSubscribers(0)
            .updatedAt(Instant.now().atZone(ZoneId.systemDefault()).format(TIME_FORMAT))
            .metric("own")
            .build();
    }

    /** 累加器：分类 → 订阅人数，以及参与统计的用户数 */
    private static final class Tally {

        private final Map<String, Integer> counts = new HashMap<>();

        private int subscribers;
    }

    private record Cached(Instant expireAt, DigestCategoryRankingDTO data) {
    }

    /** 排序口径：先按订阅人数倒序，人数相同按分类名，保证同一份数据每次的榜单顺序一致 */
    private static Comparator<Map.Entry<String, Integer>> rankingOrder() {
        return Map.Entry.<String, Integer>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey());
    }
}
