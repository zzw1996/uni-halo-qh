package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/** 投稿结果 */
@Data
@Builder
public class UserPublishResultDTO {

    /** 内容类型：article | moment */
    private String type;

    /** 实际生效的发布方式：direct（已发布/已公开）| draft（待审/草稿） */
    private String mode;

    /** 给用户看的结果说明，例如「已发布」「已提交，等待站长审核」 */
    private String statusText;

    /** 文章名称 / 瞬间名称（便于后续跳转详情） */
    private String name;

    /** 文章链接（瞬间无链接时为空） */
    private String permalink;

    /** 本次消耗积分 */
    private int pointsCost;

    /** 扣分后的余额 */
    private long balance;
}
