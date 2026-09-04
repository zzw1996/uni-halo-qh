package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class PointsEarnRequest {

    private String action;

    private String articleName;

    private String title;

    private String refId;
}
