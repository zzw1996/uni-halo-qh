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

    /** 本次同时获得的阅读券张数（0 = 无） */
    private int tickets;

    private long balance;

    private String message;
}
