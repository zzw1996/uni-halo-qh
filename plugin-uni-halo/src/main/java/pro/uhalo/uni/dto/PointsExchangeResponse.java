package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsExchangeResponse {

    /** publish_ticket | dashboard_advanced */
    private String item;

    /** 本次消耗积分 */
    private int points;

    /** 当前余额 */
    private Long balance;

    /** 发布券余额 */
    private Integer publishTickets;

    /** 每日免费发布数量 */
    private Integer freeQuota;

    /** 今日可再兑换次数 limit / 已用 dailyLimit：remaining = 剩余可兑换 */
    private Integer remaining;

    /** 今日是否已解锁数据看板高级版 */
    private Boolean dashboardUnlockedToday;

    private Boolean alreadyExchanged;

    private String message;
}
