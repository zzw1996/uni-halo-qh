package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.RankArticleDTO;
import pro.uhalo.uni.services.RankService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Comparator;
import java.util.List;

/**
 * 热榜：基于 Halo 内容计数器（Counter，访问量/点赞量）排序。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RankServiceImpl implements RankService {

    private static final String POST_COUNTER_PREFIX = "posts.content.halo.run/";

    private final ReactiveExtensionClient client;

    @Override
    public Mono<List<RankArticleDTO>> topArticles(int limit) {
        var normalizedLimit = Math.min(Math.max(limit, 1), 20);
        return client.listAll(Counter.class, new ListOptions(), null)
            .filter(counter -> counter != null && counter.getMetadata() != null
                && StringUtils.hasText(counter.getMetadata().getName())
                && counter.getMetadata().getName().startsWith(POST_COUNTER_PREFIX))
            .sort(Comparator.comparing(Counter::getVisit,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .take(normalizedLimit)
            .concatMap(this::toRankArticle)
            .filter(article -> article != null && StringUtils.hasText(article.getName()))
            .collectList();
    }

    private Mono<RankArticleDTO> toRankArticle(Counter counter) {
        var postName = counter.getMetadata().getName().substring(POST_COUNTER_PREFIX.length());
        return client.fetch(Post.class, postName)
            .map(post -> RankArticleDTO.builder()
                .name(post.getMetadata().getName())
                .title(post.getSpec() == null ? "" : post.getSpec().getTitle())
                .cover(post.getSpec() == null ? null : post.getSpec().getCover())
                .visit(counter.getVisit() == null ? 0 : counter.getVisit())
                .upvote(counter.getUpvote() == null ? 0 : counter.getUpvote())
                .build())
            .onErrorResume(ex -> {
                log.warn("热榜加载文章失败：{}", postName, ex);
                return Mono.empty();
            });
    }
}
