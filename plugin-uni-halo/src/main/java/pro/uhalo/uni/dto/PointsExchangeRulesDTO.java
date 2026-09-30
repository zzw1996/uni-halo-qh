package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsExchangeRulesDTO {

    /** 瞬间发布券（已下线：个人主体 UGC 发布收费不合规，points=0 不可兑换） */
    @Builder.Default
    private PointsExchangeItemRuleDTO publishTicket = PointsExchangeItemRuleDTO.builder()
        .points(0).dailyMaxCount(0).freeCount(0).build();

    /** 数据看板高级版（单次解锁，当日有效） */
    @Builder.Default
    private PointsExchangeItemRuleDTO dashboardAdvanced = PointsExchangeItemRuleDTO.builder()
        .points(100).dailyMaxCount(1).freeCount(0).build();
}
