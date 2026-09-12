package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 付费内容（阅读券）配置。
 *
 * <p>来自设置分组 {@code contentConfig}。付费文章由站长在 Halo 后台打标签标记，
 * 插件不维护文章清单，因此这里只需要「用哪个标签」和「解锁花几张券」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentTicketConfigDTO {

    /** 是否开启付费内容功能；关闭时所有文章均可自由阅读 */
    private Boolean enabled;

    /**
     * 付费标记标签的 slug。
     *
     * <p>文章只要带上该 slug 的标签即视为付费文章。用 slug 而非 displayName，
     * 因为 slug 唯一且稳定，displayName 可被随意修改。</p>
     */
    private String paidTagSlug;

    /** 解锁一篇文章消耗的券张数 */
    private Integer costTickets;

    /** 博主本人（Halo 账号与文章 owner 一致）是否免费解锁 */
    private Boolean freeForOwner;

    /** 解锁后是否永久有效（当前恒为 true，保留字段以便将来支持限时） */
    private Boolean permanent;
}
