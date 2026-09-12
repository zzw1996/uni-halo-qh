package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.RankArticleDTO;
import pro.uhalo.uni.dto.UserActivityFeedItemDTO;
import pro.uhalo.uni.dto.UserRankResponse;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RankService {

    /** 按浏览量取文章热榜（Top N），返回文章名/标题/封面/浏览量/点赞数。 */
    Mono<List<RankArticleDTO>> topArticles(int limit);

    /**
     * 用户榜：积分榜（累计获得积分）/ 活跃榜（近 7 日获得积分）/ 邀请榜（邀请人数）。
     *
     * @param openid 可为空；传入时额外返回「我的排名」，榜单本身公开可读
     */
    Mono<UserRankResponse> topUsers(String type, String range, int limit, String openid);

    /**
     * 互动动态：最近用户的评论/点赞/投票动作。
     * 站长关闭榜单或关闭动态墙时返回空列表。
     */
    Mono<List<UserActivityFeedItemDTO>> recentActivities(int limit);

    /**
     * 周榜奖励结算：结算上一自然周活跃榜并发放积分。由定时任务调用，
     * 同一周同一用户只发一次；站长未开启周榜奖励时不产生任何发放。
     */
    void settleWeeklyReward();
}
