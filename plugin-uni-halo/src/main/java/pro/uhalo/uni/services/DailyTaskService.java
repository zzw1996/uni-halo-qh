package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.ClaimDailyTaskResponse;
import pro.uhalo.uni.dto.DailyTasksResponseDTO;
import reactor.core.publisher.Mono;

/**
 * 每日任务：进度统计与奖励领取。
 */
public interface DailyTaskService {

    /**
     * 查询用户今日任务列表与进度。
     *
     * @param openid 微信 openid（Wechat-Session-Id）
     */
    Mono<DailyTasksResponseDTO> getDailyTasks(String openid);

    /**
     * 领取指定任务的今日奖励（每天每任务一次，幂等）。
     *
     * @param openid 微信 openid（Wechat-Session-Id）
     * @param type   任务类型：sign_in | browse | comment | upvote | vote
     */
    Mono<ClaimDailyTaskResponse> claim(String openid, String type);
}
