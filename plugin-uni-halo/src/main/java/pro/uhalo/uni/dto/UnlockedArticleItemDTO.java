package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户已解锁的付费文章（「已解锁文章」页展示）。
 *
 * <p>券花掉之后用户此前在别处看不到自己买过什么，券就只是一次性消耗品；
 * 列出解锁历史后券变成可回溯的资产，用户更愿意继续囤。</p>
 */
@Data
@Builder
public class UnlockedArticleItemDTO {

    /** 文章 metadata.name，前端跳详情页用 */
    private String articleName;

    private String title;

    private String slug;

    private String cover;

    /** 解锁时间（yyyy-MM-dd HH:mm） */
    private String unlockTime;

    /** 解锁时消耗的券张数 */
    private Integer ticketsCost;

    /**
     * 文章当前是否仍可访问。
     *
     * <p>文章被删除或取消发布后记录仍在（解锁记录永久保存，防误清），
     * 此时前端要标成「已下架」而不是给一个点进去 404 的链接。</p>
     */
    private boolean available;
}
