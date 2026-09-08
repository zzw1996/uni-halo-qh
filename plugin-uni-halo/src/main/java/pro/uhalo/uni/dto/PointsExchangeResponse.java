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

    /** 可兑换权益列表（含今日已兑换次数），由插件后台 exchangeConfig 配置 */
    private java.util.List<ExchangeItemDTO> items;

    /** 兼容字段：今日是否已解锁数据看板高级版（由 items 中 dashboard_advanced 推导，保留旧前端兼容） */
    private Boolean dashboardUnlockedToday;

    private Boolean alreadyExchanged;

    private String message;
}
