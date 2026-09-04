package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsActionRuleDTO {

    private int points;

    private int dailyMaxCount;

    private int dailyMaxPoints;
}
