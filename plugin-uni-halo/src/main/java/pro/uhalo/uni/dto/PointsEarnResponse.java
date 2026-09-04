package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsEarnResponse {

    private long earned;

    private long balance;

    private long todayEarned;

    private String message;

    private Boolean capped;

    private Boolean earnedToday;

    private Boolean signedToday;

    private PointsSignInStatusDTO signInStatus;
}
