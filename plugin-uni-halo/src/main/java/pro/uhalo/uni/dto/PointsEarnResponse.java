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

    /**
     * 本次同时发放的阅读券张数（签到送券 / 新人赠券等赠券路径）。
     *
     * <p>{@code earned} 只统计积分，券不计入积分；单独给一个字段让前端能在提示里
     * 把「+10 积分 +1 张阅读券」一次说清，而不是让用户觉得券是凭空多出来的。</p>
     */
    private Integer tickets;

    private PointsSignInStatusDTO signInStatus;
}
