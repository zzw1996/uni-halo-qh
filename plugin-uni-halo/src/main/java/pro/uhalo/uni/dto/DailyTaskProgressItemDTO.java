package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 单个任务今日进度（用户视图）。
 */
@Data
@Builder
public class DailyTaskProgressItemDTO {

    private String type;

    private String label;

    private int target;

    private int progress;

    private int points;

    /** 今日进度是否达标，可领取奖励 */
    private boolean done;

    /** 今日是否已领取奖励 */
    private boolean claimed;
}
