package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsExchangeResponse {

    /** publish_ticket | dashboard_advanced */
    private String item;

    /** 本次消耗积分 */
    private int points;

    /** 当前余额 */
    private Long balance;

    /**
     * 兑换后的阅读券余额。
     *
     * <p>券类兑换必须回传：兑换前用户看到「0 张」，若响应里不带新余额，
     * 前端只能等下一次拉取才更新，用户会以为兑换没生效而重复点。</p>
     */
    private Integer contentTickets;

    /** 兑换后的发布券余额（历史券种，保留兼容） */
    private Integer publishTickets;

    /** 解锁后的订阅额度份数（仅「积分解锁订阅额度」会回传，其余为 null） */
    private Integer subscribeQuota;

    /** 可兑换权益列表（含今日已兑换次数），由插件后台 exchangeConfig 配置 */
    private java.util.List<ExchangeItemDTO> items;

    /** 兼容字段：今日是否已解锁数据看板高级版（由 items 中 dashboard_advanced 推导，保留旧前端兼容） */
    private Boolean dashboardUnlockedToday;

    private Boolean alreadyExchanged;

    private String message;
}
