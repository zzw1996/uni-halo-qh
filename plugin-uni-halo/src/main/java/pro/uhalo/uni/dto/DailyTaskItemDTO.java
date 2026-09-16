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

    /**
     * 完成奖励的阅读券张数（可与积分同时给，也可只给券）。
     *
     * <p>「任务送券」比「签到送券」更好用：它按完成次数结算，力度天然受限，
     * 不会让日活用户天天白拿券。因此让任务成为主要的免费获券途径更可控。</p>
     */
    private int tickets;

    private boolean enabled;
}
