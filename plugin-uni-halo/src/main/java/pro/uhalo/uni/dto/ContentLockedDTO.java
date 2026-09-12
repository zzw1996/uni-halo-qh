package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 付费文章锁定信息。
 *
 * <p>⚠️ 安全要求：该响应体<b>绝不可包含正文</b>。拦截 Filter 用它替代
 * Halo 原生详情响应，因此只能携带标题、摘要、封面等列表页本来就公开的信息，
 * 一旦混入正文就等于付费墙形同虚设。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentLockedDTO {

    /** 文章 metadata.name */
    private String articleName;

    private String title;

    /** Halo 自动生成的摘要（post.status.excerpt），列表页本就公开 */
    private String excerpt;

    private String cover;

    /** 是否需要阅读券解锁 */
    private Boolean locked;

    /** 当前用户是否已解锁（未登录时为 false） */
    private Boolean unlocked;

    /** 当前用户持有的阅读券张数（未登录时为 0） */
    private Integer tickets;

    /** 解锁本文所需券张数 */
    private Integer costTickets;

    /** 给前端展示的提示文案 */
    private String message;
}
