package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 每日任务配置项（来自 dailyTaskConfig 设置分组）。
 */
@Data
@Builder
public class DailyTaskItemDTO {

    /** 行为类型：sign_in | browse | comment | upvote | vote */
    private String type;

    /** 每日完成目标次数 */
    private int target;

    /** 完成奖励积分 */
    private int points;

    private boolean enabled;
}
