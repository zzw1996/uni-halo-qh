package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI 定制内容的权益状态（给小程序展示用）。
 *
 * <p>三种情况要能分开：功能未开启（入口都不该显示）、未开通/已过期（引导购买）、
 * 有效（配置与简报可用）。因此除了 {@code active} 之外还有 {@code expired}——
 * 「过期」与「从未购买」的引导文案不同，且过期用户的配置仍然保留。</p>
 */
@Data
@Builder
public class AiContentStatusDTO {

    /** 站长的总开关；false 时前端不显示任何入口 */
    private boolean enabled;

    /** 当前是否有效（已购买且在有效期内，或永久） */
    private boolean active;

    /** 永久买断 */
    private boolean permanent;

    /** 曾经购买过但已过期（配置保留，续费即可继续用） */
    private boolean expired;

    /** 到期时间展示串；永久为「永久」，未开通为 null */
    private String expireAt;

    private long expireAtEpoch;

    /** 剩余天数（不足一天按一天计）；永久或未开通为 0 */
    private int daysRemaining;

    /** 当前有效性来自「会员期内免条件使用」而非已购道具（前端展示「会员期内免费」） */
    private boolean viaMember;
}
