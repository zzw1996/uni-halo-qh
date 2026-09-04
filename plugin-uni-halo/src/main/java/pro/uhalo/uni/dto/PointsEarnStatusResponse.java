package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsEarnStatusResponse {

    private boolean earnedToday;

    private long earned;

    private Boolean capped;

    private String message;
}
