package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 后台邀请明细：谁邀请了谁，邀请人获得多少积分。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminInviteRecordItemDTO {

    private String id;

    /** 邀请人 openid */
    private String inviterOpenid;

    /** 邀请人昵称 */
    private String inviterNickname;

    private String inviterAvatar;

    private String inviterHaloUserName;

    /** 被邀请人 openid */
    private String inviteeOpenid;

    /** 被邀请人昵称 */
    private String inviteeNickname;

    private String inviteeAvatar;

    private String inviteeHaloUserName;

    /** 本次邀请发放积分 */
    private int points;

    /** 邀请人发放后余额 */
    private Long balance;

    private String createTime;

    /** 可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai） */
    private String createTimeText;
}
