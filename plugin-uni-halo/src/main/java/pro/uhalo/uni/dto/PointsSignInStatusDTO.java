package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class PointsSignInStatusDTO {

    private boolean signedToday;

    private int consecutiveDays;

    @Builder.Default
    private List<Boolean> weekRecords = new ArrayList<>();

    /** 最近一次签到日期，格式 yyyy-MM-dd */
    private String lastSignInDate;
}
