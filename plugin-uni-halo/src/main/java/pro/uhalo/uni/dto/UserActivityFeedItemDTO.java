package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 互动动态墙条目：把最近用户的评论/点赞/投票动作聚合成一条可读动态。
 *
 * <p>只包含站点上本就公开的信息（昵称、头像、内容标题），不返回 openid。</p>
 */
@Data
@Builder
public class UserActivityFeedItemDTO {

    private String nickname;

    private String avatar;

    /** 头像框样式值（装扮），未佩戴为空 */
    private String avatarFrame;

    /** 昵称前缀文本（装扮），未佩戴为空 */
    private String nickFrame;

    /** 动作类型：comment / upvote / vote / reaction */
    private String type;

    /** 动作文案（服务端给出，前端直接展示，如「评论了」） */
    private String actionText;

    /** post 或 moment */
    private String contentType;

    /** 内容名称，用于跳转详情 */
    private String targetName;

    private String title;

    /** 评论摘要（仅评论动作有值） */
    private String excerpt;

    /** 事件时间（ISO-8601，UTC），前端换算相对时间 */
    private String time;
}
