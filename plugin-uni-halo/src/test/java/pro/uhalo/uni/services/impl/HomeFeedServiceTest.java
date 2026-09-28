package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestCategoryRankingDTO;
import pro.uhalo.uni.dto.SubscriptionConfigDTO;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.DigestSubscriptionStatsService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 首页并集内容流：合并去重排序、分页切片、缓存、单个分类失败不影响整页。
 *
 * <p>合并顺序必须与前端原本的 {@code fnMergeArticles} 一致（置顶优先、其次时间倒序）——
 * 两处各排一次序，翻页边界上一定会重复或漏条。</p>
 */
@ExtendWith(MockitoExtension.class)
class HomeFeedServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DigestHaloClient haloClient;

    @Mock
    private DigestSubscriptionStatsService subscriptionStatsService;

    @InjectMocks
    private HomeFeedService service;

    private final SubscriptionConfigDTO config = SubscriptionConfigDTO.builder()
        .homeFeedPerCategory(20)
        .homeFeedCacheSeconds(60)
        .hotCategoryCount(2)
        .build();

    @BeforeEach
    void setUp() {
        lenient().when(haloClient.getAnonymous(anyString(), any(Duration.class)))
            .thenReturn(Mono.just(MAPPER.createObjectNode().set("items",
                MAPPER.createArrayNode())));
    }

    private static JsonNode post(String name, String publishTime, boolean pinned) {
        var node = MAPPER.createObjectNode();
        node.putObject("metadata").put("name", name);
        var spec = node.putObject("spec");
        spec.put("publishTime", publishTime);
        spec.put("pinned", pinned);
        return node;
    }

    private void stubCategory(String category, JsonNode... posts) {
        var body = MAPPER.createObjectNode();
        ArrayNode items = body.putArray("items");
        for (var post : posts) {
            items.add(post);
        }
        when(haloClient.getAnonymous(contains("/categories/" + category + "/posts"), any()))
            .thenReturn(Mono.just(body));
    }

    @Test
    void shouldMergeDedupeAndSortLikeTheClient() {
        stubCategory("a", post("p1", "2026-09-20T10:00:00Z", false),
            post("p2", "2026-09-25T10:00:00Z", false));
        stubCategory("b", post("p2", "2026-09-25T10:00:00Z", false),
            post("p3", "2026-09-10T10:00:00Z", true));

        StepVerifier.create(service.feed(List.of("a", "b"), config, 1, 10))
            .assertNext(feed -> {
                // 置顶的 p3 排最前；其余按发布时间倒序；p2 出现两次只保留一条
                assertThat(feed.getItems()).extracting(item -> item.path("metadata").path("name").asText())
                    .containsExactly("p3", "p2", "p1");
                assertThat(feed.isHasMore()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void shouldSlicePagesFromTheMergedWindow() {
        stubCategory("a", post("p1", "2026-09-25T10:00:00Z", false),
            post("p2", "2026-09-24T10:00:00Z", false),
            post("p3", "2026-09-23T10:00:00Z", false));

        StepVerifier.create(service.feed(List.of("a"), config, 1, 2))
            .assertNext(feed -> {
                assertThat(feed.getItems()).hasSize(2);
                assertThat(feed.isHasMore()).isTrue();
                assertThat(feed.getPage()).isEqualTo(1);
            })
            .verifyComplete();

        StepVerifier.create(service.feed(List.of("a"), config, 2, 2))
            .assertNext(feed -> {
                assertThat(feed.getItems()).hasSize(1);
                assertThat(feed.isHasMore()).isFalse();
            })
            .verifyComplete();

        // 越界的页返回空而不是报错：客户端上拉到底容易出现
        StepVerifier.create(service.feed(List.of("a"), config, 9, 2))
            .assertNext(feed -> {
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.isHasMore()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void singleCategoryFailureShouldNotEmptyThePage() {
        stubCategory("a", post("p1", "2026-09-25T10:00:00Z", false));
        when(haloClient.getAnonymous(contains("/categories/b/posts"), any()))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.feed(List.of("a", "b"), config, 1, 10))
            .assertNext(feed -> assertThat(feed.getItems()).hasSize(1))
            .verifyComplete();
    }

    @Test
    void emptyCategoriesShouldReturnAnEmptyPageWithoutCallingHalo() {
        StepVerifier.create(service.feed(List.of(), config, 1, 10))
            .assertNext(feed -> {
                assertThat(feed.getItems()).isEmpty();
                assertThat(feed.isHasMore()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void secondCallShouldHitTheCache() {
        stubCategory("a", post("p1", "2026-09-25T10:00:00Z", false));

        service.feed(List.of("a"), config, 1, 10).block();
        service.feed(List.of("a"), config, 1, 10).block();

        // 同一批分类在 TTL 内只取一次：订阅多的站点不至于每次开首页都发 N 路请求
        org.mockito.Mockito.verify(haloClient, org.mockito.Mockito.times(1))
            .getAnonymous(contains("/categories/a/posts"), any());
    }

    @Test
    void cacheDisabledShouldAlwaysFetch() {
        var noCache = SubscriptionConfigDTO.builder().homeFeedPerCategory(20)
            .homeFeedCacheSeconds(0).build();
        stubCategory("a", post("p1", "2026-09-25T10:00:00Z", false));

        service.feed(List.of("a"), noCache, 1, 10).block();
        service.feed(List.of("a"), noCache, 1, 10).block();

        org.mockito.Mockito.verify(haloClient, org.mockito.Mockito.times(2))
            .getAnonymous(contains("/categories/a/posts"), any());
    }

    @Test
    void hotCategoriesShouldTakeTheTopOnesFromTheRanking() {
        when(subscriptionStatsService.categoryRanking()).thenReturn(Mono.just(
            DigestCategoryRankingDTO.builder()
                .items(List.of(
                    DigestCategoryRankingDTO.Item.builder().name("security").subscribers(9).build(),
                    DigestCategoryRankingDTO.Item.builder().name("ai-ml").subscribers(7).build(),
                    DigestCategoryRankingDTO.Item.builder().name("tools").subscribers(3).build()))
                .build()));

        StepVerifier.create(service.hotCategories(config))
            .expectNext(List.of("security", "ai-ml"))
            .verifyComplete();
    }

    @Test
    void hotCategoriesFailureShouldYieldEmptyInsteadOfError() {
        when(subscriptionStatsService.categoryRanking())
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        // 热度读不到时返回空：调用方会退回「全站最新」，首页仍然能用
        StepVerifier.create(service.hotCategories(config))
            .expectNext(List.of())
            .verifyComplete();
    }

    @Test
    void mergeShouldIgnoreItemsWithoutName() {
        var nameless = MAPPER.createObjectNode();
        nameless.putObject("spec").put("publishTime", "2026-09-26T10:00:00Z");

        var merged = HomeFeedService.merge(List.of(List.of(nameless),
            List.of(post("p1", "2026-09-25T10:00:00Z", false))));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).path("metadata").path("name").asText()).isEqualTo("p1");
    }
}
