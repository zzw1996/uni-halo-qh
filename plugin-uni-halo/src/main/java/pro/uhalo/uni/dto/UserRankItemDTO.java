package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户榜条目。仅包含评论/瞬间等公开场景本就可见的昵称与头像，
 * 不返回 openid 等身份标识。
 */
@Data
@Builder
public class UserRankItemDTO {

    /** 名次，从 1 开始 */
    private int rank;

    private String nickname;

    private String avatar;

    /** 头像框样式值（装扮，仅在持有且未过期时下发），未佩戴为空 */
    private String avatarFrame;

    /** 昵称前缀文本（装扮），未佩戴为空 */
    private String nickFrame;

    /** 等级（按累计获得积分解出，与身份中心同一口径） */
    private int level;

    private String levelTitle;

    /** 榜单数值：积分榜=累计获得积分，活跃榜=近 7 日获得积分，邀请榜=邀请人数 */
    private long value;
}
