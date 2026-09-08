package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 领取每日任务奖励请求。
 */
@Data
public class ClaimDailyTaskRequest {

    /** 任务类型：sign_in | browse | comment | upvote | vote */
    private String type;
}
