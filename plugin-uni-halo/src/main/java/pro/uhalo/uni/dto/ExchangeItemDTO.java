package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExchangeItemDTO {

    /** 权益 key（功能页面以此判断是否已兑换，如 dashboard_advanced） */
    private String key;

    /** 权益名称（展示用） */
    private String name;

    /** 每次兑换消耗积分 */
    private int points;

    /** 每日可兑换次数上限 */
    private int dailyMaxCount;

    /** 说明文案 */
    private String description;

    /**
     * 每次兑换发放的券张数（仅券类兑换项有值，如 content_ticket）。
     *
     * <p>前端据此显示「100 积分 / 2 张券」这类单价信息，让用户能判断值不值。</p>
     */
    private int ticketCount;

    /** 今日已兑换次数 */
    private int todayCount;
}
