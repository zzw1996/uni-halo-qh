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

    /** 我的邀请人昵称（无邀请人时为 null） */
    private String inviterNickname;

    /** 我的邀请人头像（无邀请人时为 null） */
    private String inviterAvatar;

    /** 最近一次生成邀请海报时间（可读，null=未生成过） */
    private String posterGeneratedAt;

    /** 邀请海报图片地址（已上传后台，null=未上传） */
    private String posterImageUrl;
}
