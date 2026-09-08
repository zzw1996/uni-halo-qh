package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DashboardSummaryDTO;
import pro.uhalo.uni.dto.DashboardTrendItemDTO;
import pro.uhalo.uni.dto.RankArticleDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.DashboardService;
import pro.uhalo.uni.services.RankService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 站点数据看板：完全由插件自身统计（Halo 核心扩展 + 插件数据），不依赖任何第三方插件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardServiceImpl implements DashboardService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String POST_COUNTER_PREFIX = "posts.content.halo.run/";

    private final ReactiveExtensionClient client;
    private final RankService rankService;
    private final PointsPersistence pointsPersistence;

    @Override
    public Mono<DashboardSummaryDTO> summary(int topArticlesLimit) {
        return countPosts().cache()
            .flatMap(postCount -> countAll(Category.class).cache()
                .flatMap(categoryCount -> countAll(Tag.class).cache()
                    .flatMap(tagCount -> countAll(Comment.class).cache()
                        .flatMap(commentCount -> sumCounters().cache()
                            .flatMap(counterTotal -> countAll(WxUser.class).cache()
                                .flatMap(wxUserCount -> sumIssuedPoints().cache()
                                    .flatMap(pointsIssued -> buildWeekTrend().cache()
                                        .flatMap(trend -> rankService.topArticles(
                                            Math.min(Math.max(topArticlesLimit, 1), 10)).cache()
                                            .map(top -> DashboardSummaryDTO.builder()
                                                .totalPosts(postCount)
                                                .totalCategories(categoryCount)
                                                .totalTags(tagCount)
                                                .totalComments(commentCount)
                                                .totalVisits(counterTotal[0])
                                                .totalUpvotes(counterTotal[1])
                                                .totalWxUsers(wxUserCount)
                                                .totalPointsIssued(pointsIssued)
                                                .weekTrend(trend)
                                                .topArticles(top)
                                                .build())))))))))
            .onErrorResume(ex -> {
                log.warn("数据看板统计失败", ex);
                return Mono.just(DashboardSummaryDTO.builder()
                    .totalPosts(0)
                    .totalCategories(0)
                    .totalTags(0)
                    .totalComments(0)
                    .totalVisits(0)
                    .totalUpvotes(0)
                    .totalWxUsers(0)
                    .totalPointsIssued(0)
                    .weekTrend(List.of())
                    .topArticles(List.of())
                    .build());
            });
    }

    private Mono<Integer> countPosts() {
        return client.listAll(Post.class, new ListOptions(), null)
            .filter(post -> post != null && post.getSpec() != null
                && Boolean.TRUE.equals(post.getSpec().getPublish())
                && !Boolean.TRUE.equals(post.getSpec().getDeleted()))
            .count()
            .map(Long::intValue);
    }

    private <E extends run.halo.app.extension.Extension> Mono<Integer> countAll(Class<E> type) {
        return client.listAll(type, new ListOptions(), null).count().map(Long::intValue);
    }

    private Mono<int[]> sumCounters() {
        return client.listAll(Counter.class, new ListOptions(), null)
            .filter(counter -> counter != null && counter.getMetadata() != null
                && StringUtils.hasText(counter.getMetadata().getName())
                && counter.getMetadata().getName().startsWith(POST_COUNTER_PREFIX))
            .collectList()
            .map(counters -> {
                var total = new int[2];
                for (var counter : counters) {
                    total[0] += counter.getVisit() == null ? 0 : counter.getVisit();
                    total[1] += counter.getUpvote() == null ? 0 : counter.getUpvote();
                }
                return total;
            });
    }

    private Mono<Long> sumIssuedPoints() {
        return pointsPersistence.listAllRecords()
            .map(records -> records.stream()
                .filter(record -> record != null && record.getPoints() != null && record.getPoints() > 0)
                .mapToLong(PointsRecord::getPoints)
                .sum());
    }

    private Mono<List<DashboardTrendItemDTO>> buildWeekTrend() {
        var today = LocalDate.now(ZONE);
        var from = today.minusDays(6);
        return client.listAll(Post.class, new ListOptions(), null)
            .filter(post -> post != null && post.getSpec() != null
                && Boolean.TRUE.equals(post.getSpec().getPublish()))
            .collectList()
            .map(posts -> {
                var items = new ArrayList<DashboardTrendItemDTO>(7);
                for (int i = 0; i < 7; i++) {
                    var date = from.plusDays(i);
                    var dayStart = date.atStartOfDay(ZONE).toInstant();
                    var dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();
                    var count = (int) posts.stream()
                        .filter(post -> {
                            var time = publishTimeOf(post);
                            return time != null && !time.isBefore(dayStart) && time.isBefore(dayEnd);
                        })
                        .count();
                    items.add(DashboardTrendItemDTO.builder()
                        .date(date.format(DATE_FMT))
                        .count(count)
                        .build());
                }
                return items;
            });
    }

    private static Instant publishTimeOf(Post post) {
        if (post.getSpec() != null && post.getSpec().getPublishTime() != null) {
            return post.getSpec().getPublishTime();
        }
        return post.getMetadata() == null ? null : post.getMetadata().getCreationTimestamp();
    }
}
