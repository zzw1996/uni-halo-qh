package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class PointsSignInRuleDTO {

    private int points;

    @Builder.Default
    private List<ConsecutiveBonusDTO> consecutiveBonus = new ArrayList<>();
}
