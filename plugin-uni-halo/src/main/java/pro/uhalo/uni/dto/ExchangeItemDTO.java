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

    /** 今日已兑换次数 */
    private int todayCount;
}
