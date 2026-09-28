package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestCategoryRankingDTO;
import reactor.core.publisher.Mono;

/**
 * 订阅相关的全站统计。
 */
public interface DigestSubscriptionStatsService {

    /**
     * 分类订阅热度榜：每个分类被多少用户**主动订阅**（不含邀请继承项）。
     *
     * <p>全站聚合的结果会缓存几分钟——它只是「发现」页的排序依据，不需要实时。</p>
     */
    Mono<DigestCategoryRankingDTO> categoryRanking();
}
