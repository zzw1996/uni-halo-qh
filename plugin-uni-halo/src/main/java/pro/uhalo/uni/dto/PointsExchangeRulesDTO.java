package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsExchangeRulesDTO {

    /** 瞬间发布券 */
    @Builder.Default
    private PointsExchangeItemRuleDTO publishTicket = PointsExchangeItemRuleDTO.builder()
        .points(50).dailyMaxCount(5).freeCount(3).build();

    /** 数据看板高级版（单次解锁，当日有效） */
    @Builder.Default
    private PointsExchangeItemRuleDTO dashboardAdvanced = PointsExchangeItemRuleDTO.builder()
        .points(30).dailyMaxCount(1).freeCount(0).build();
}
