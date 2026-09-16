package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 会员状态（用户视图）。
 */
@Data
@Builder
public class MembershipStatusDTO {

    /** 站点是否开启了会员卡（关闭时不展示入口） */
    private boolean enabled;

    /** 当前是否在会员有效期内 */
    private boolean active;

    /** 会员期内付费文章是否免券 */
    private boolean freeContent;

    /** 到期时间（yyyy-MM-dd HH:mm）；未开通为 null */
    private String expireAt;

    /** 到期时间（Unix 秒），供前端算剩余时间；未开通为 0 */
    private long expireAtEpoch;

    /** 剩余天数（不足一天按一天计）；未开通为 0 */
    private int daysRemaining;

    /** 开通时额外赠送的券张数，用于前端展示权益 */
    private int bonusTickets;

    /** 权益说明文案（前端直接展示，避免各处自己拼） */
    private List<String> benefits;
}
