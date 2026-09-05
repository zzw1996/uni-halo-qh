package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsExchangeItemRuleDTO {

    /** 每次兑换消耗积分 */
    @Builder.Default
    private int points = 0;

    /** 每日可兑换次数上限 */
    @Builder.Default
    private int dailyMaxCount = 0;

    /** 每日免费额度（仅发布券使用，0 表示无 */
    @Builder.Default
    private int freeCount = 0;
}
