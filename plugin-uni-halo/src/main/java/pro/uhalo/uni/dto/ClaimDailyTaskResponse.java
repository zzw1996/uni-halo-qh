package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 领取每日任务奖励响应。
 */
@Data
@Builder
public class ClaimDailyTaskResponse {

    /** 本次获得积分（已领取时为 0） */
    private int earned;

    private long balance;

    private String message;
}
