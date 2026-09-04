package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsRulesDTO {

    @Builder.Default
    private PointsActionRuleDTO browse = PointsActionRuleDTO.builder()
        .points(1).dailyMaxCount(10).dailyMaxPoints(10).build();

    @Builder.Default
    private PointsActionRuleDTO comment = PointsActionRuleDTO.builder()
        .points(5).dailyMaxCount(5).dailyMaxPoints(25).build();

    @Builder.Default
    private PointsActionRuleDTO upvote = PointsActionRuleDTO.builder()
        .points(2).dailyMaxCount(20).dailyMaxPoints(40).build();

    @Builder.Default
    private PointsSignInRuleDTO signIn = PointsSignInRuleDTO.builder()
        .points(10)
        .consecutiveBonus(List.of(
            ConsecutiveBonusDTO.builder().days(3).bonus(5).build(),
            ConsecutiveBonusDTO.builder().days(7).bonus(20).build()
        ))
        .build();
}
