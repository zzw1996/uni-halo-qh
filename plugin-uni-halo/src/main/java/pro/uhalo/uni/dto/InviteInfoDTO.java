package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InviteInfoDTO {

    private boolean enabled;

    /** 每邀请 1 人奖励积分 */
    private int rewardPoints;

    /** 当前用户邀请码 */
    private String inviteCode;

    /** 已成功邀请人数 */
    private long invitedCount;
}
