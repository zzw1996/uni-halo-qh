package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 后台邀请汇总：按邀请人聚合，一人邀请多人时汇总邀请人数与总积分。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminInviteSummaryItemDTO {

    private String inviterOpenid;

    private String inviterNickname;

    private String inviterAvatar;

    private String inviterHaloUserName;

    /** 累计成功邀请人数 */
    private long invitedCount;

    /** 累计发放积分 */
    private long totalPoints;

    /** 最近一次邀请时间（可读） */
    private String lastInviteTime;
}
