package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.HomeFeedDTO;
import pro.uhalo.uni.dto.SubscriptionConfigDTO;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.DigestSubscriptionStatsService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 首页内容流的并集取数：把多个分类的最新文章合并成一次请求返回。
 *
 * <p><b>为什么在服务端做并集</b>：客户端原来是「每个分类一路请求」再在前端合并，订阅额度放开后
 * 这个数字会继续涨；放到服务端后接口与订阅数解耦，合并/去重/排序的口径也只有一处。</p>
 *
 * <p><b>取数走 Halo 官方接口</b>（{@code categories/{name}/posts}，与客户端原先调的完全同一个）：
 * 排序、可见性、已发布过滤都由 Halo 保证，插件不必去赌「数组字段能不能当索引查」。
 * 代价是内部要发 N 个本机回环请求，所以窗口结果带短 TTL 缓存。</p>
 *
 * <p>本类只负责「取数与合并」，降级文案与 scope/fallback 由端点层组装——
 * 那里才知道用户有没有订阅、有哪些分类被额度暂停。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeFeedService {

    /** 单页最大条数：与其它列表接口保持一致 */
    public static final int MAX_PAGE_SIZE = 30;

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(8);

    private static final String CATEGORY_POSTS_PATH =
        "/apis/api.content.halo.run/v1alpha1/categories/%s/posts?page=%d&size=%d";

    private final DigestHaloClient haloClient;
    private final DigestSubscriptionStatsService subscriptionStatsService;

    /** 并集结果缓存：键 = 分类集合 + 每类条数；值 = 合并排序后的完整窗口 */
    private final Map<String, CachedWindow> cache = new ConcurrentHashMap<>();

    /**
     * 取一页内容流。
     *
     * @param categories 生效的分类（调用方已按额度/订阅解析好；服务端不接受客户端直接传分类）
     */
    public Mono<HomeFeedDTO> feed(List<String> categories, SubscriptionConfigDTO config, int page,
        int size) {
        var pageIndex = Math.max(1, page);
        var pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return window(categories, config).map(items -> {
            var from = (pageIndex - 1) * pageSize;
            var slice = from >= items.size()
                ? List.<JsonNode>of()
                : List.copyOf(items.subList(from, Math.min(items.size(), from + pageSize)));
            return HomeFeedDTO.builder()
                .items(slice)
                .hasMore(from + pageSize < items.size())
                .categories(List.copyOf(categories == null ? List.of() : categories))
                .page(pageIndex)
                .size(pageSize)
                .build();
        });
    }

    /**
     * 人气分类：取订阅热度前 N 个分类当内容源。
     *
     * <p>零订阅用户（含游客）的首页就靠它——比全站最新更像「推荐」，也不会一屏空白。</p>
     */
    public Mono<List<String>> hotCategories(SubscriptionConfigDTO config) {
        return subscriptionStatsService.categoryRanking()
            .map(ranking -> {
                var names = new ArrayList<String>();
                for (var item : ranking.getItems()) {
                    if (names.size() >= config.getHotCategoryCount()) {
                        break;
                    }
                    if (StringUtils.hasText(item.getName())) {
                        names.add(item.getName());
                    }
                }
                return List.copyOf(names);
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Feed] 读取分类热度失败，无人气分类可用：{}", ex.getMessage());
                return Mono.just(List.<String>of());
            });
    }

    /** 合并后的窗口（带缓存） */
    private Mono<List<JsonNode>> window(List<String> categories, SubscriptionConfigDTO config) {
        if (categories == null || categories.isEmpty()) {
            return Mono.just(List.of());
        }
        var perCategory = config.getHomeFeedPerCategory();
        var ttlSeconds = config.getHomeFeedCacheSeconds();
        var key = String.join(",", categories) + "@" + perCategory;
        var cached = cache.get(key);
        if (ttlSeconds > 0 && cached != null && !cached.expired(ttlSeconds)) {
            return Mono.just(cached.items());
        }
        return fetchAll(categories, perCategory)
            .map(items -> {
                if (ttlSeconds > 0) {
                    cache.put(key, new CachedWindow(items, System.currentTimeMillis()));
                }
                return items;
            });
    }

    /** 并发按分类取数，然后合并去重排序 */
    private Mono<List<JsonNode>> fetchAll(List<String> categories, int perCategory) {
        return Flux.fromIterable(categories)
            .flatMap(category -> fetchCategory(category, perCategory)
                .subscribeOn(Schedulers.boundedElastic()), Math.max(1, categories.size()))
            .collectList()
            .map(HomeFeedService::merge);
    }

    private Mono<List<JsonNode>> fetchCategory(String category, int perCategory) {
        var path = CATEGORY_POSTS_PATH.formatted(encode(category), 0, perCategory);
        return haloClient.getAnonymous(path, FETCH_TIMEOUT)
            .map(body -> {
                var items = body.path("items");
                if (!items.isArray()) {
                    return List.<JsonNode>of();
                }
                var result = new ArrayList<JsonNode>();
                for (var item : items) {
                    result.add(item);
                }
                return List.copyOf(result);
            })
            .onErrorResume(ex -> {
                // 单个分类失败不能让整页空掉：其余分类照常合并，日志留痕
                log.warn("[UniHalo][Feed] 取分类内容失败（已跳过）category={}：{}", category,
                    DigestHaloClient.describe(ex));
                return Mono.just(List.<JsonNode>of());
            });
    }

    /**
     * 合并：按文章名去重，置顶优先、其次发布时间倒序。
     *
     * <p>与前端原来的 {@code fnMergeArticles} 同一口径——否则同一次请求在两处得到不同顺序，
     * 翻页时会出现重复或漏条（接口与客户端各排一次序，边界上必然对不齐）。</p>
     */
    static List<JsonNode> merge(List<List<JsonNode>> lists) {
        var seen = new LinkedHashSet<String>();
        var merged = new ArrayList<JsonNode>();
        for (var list : lists) {
            for (var item : list) {
                var name = item.path("metadata").path("name").asText("");
                if (!StringUtils.hasText(name) || !seen.add(name)) {
                    continue;
                }
                merged.add(item);
            }
        }
        merged.sort(Comparator
            .comparing((JsonNode item) -> item.path("spec").path("pinned").asBoolean(false) ? 0 : 1)
            .thenComparing(item -> item.path("spec").path("publishTime").asText(""),
                Comparator.reverseOrder()));
        return List.copyOf(merged);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record CachedWindow(List<JsonNode> items, long cachedAt) {
        boolean expired(int ttlSeconds) {
            return System.currentTimeMillis() - cachedAt > ttlSeconds * 1000L;
        }
    }
}
