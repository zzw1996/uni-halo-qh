package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsRulesDTO {

    @Builder.Default
    private PointsActionRuleDTO browse = PointsActionRuleDTO.builder()
        .points(1).dailyMaxCount(5).dailyMaxPoints(5).build();

    @Builder.Default
    private PointsActionRuleDTO comment = PointsActionRuleDTO.builder()
        .points(5).dailyMaxCount(2).dailyMaxPoints(10).build();

    @Builder.Default
    private PointsActionRuleDTO upvote = PointsActionRuleDTO.builder()
        .points(1).dailyMaxCount(5).dailyMaxPoints(5).build();

    @Builder.Default
    private PointsActionRuleDTO vote = PointsActionRuleDTO.builder()
        .points(5).dailyMaxCount(2).dailyMaxPoints(10).build();

    @Builder.Default
    private PointsSignInRuleDTO signIn = PointsSignInRuleDTO.builder()
        .points(5)
        .consecutiveBonus(List.of(
            ConsecutiveBonusDTO.builder().days(3).bonus(5).build(),
            ConsecutiveBonusDTO.builder().days(7).bonus(15).build()
        ))
        .build();

    /** 新用户见面礼：points=0 表示关闭 */
    @Builder.Default
    private PointsActionRuleDTO newUserGift = PointsActionRuleDTO.builder()
        .points(100).dailyMaxCount(1).dailyMaxPoints(100).build();

    /** 积分兑换（发布券 / 数据看板高级版） */
    @Builder.Default
    private PointsExchangeRulesDTO exchange = PointsExchangeRulesDTO.builder().build();
}
