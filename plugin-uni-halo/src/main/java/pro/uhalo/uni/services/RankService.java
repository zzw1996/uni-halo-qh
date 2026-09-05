package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.RankArticleDTO;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RankService {

    /** 按浏览量取文章热榜（Top N），返回文章名/标题/封面/浏览量/点赞数。 */
    Mono<List<RankArticleDTO>> topArticles(int limit);
}
