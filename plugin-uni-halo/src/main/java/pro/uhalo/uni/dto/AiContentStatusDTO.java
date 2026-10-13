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

    /**
     * 是否因「站点所有者 / 管理员」身份而开通。
     *
     * <p>特权身份无需购买即为已开通（便于上架前自测），前端可用它把状态文案
     * 显示成「管理员」而不是「剩余 N 天」。</p>
     */
    private boolean viaPrivilege;

    /**
     * 还能生成几次简报；{@code -1} 表示不限（特权用户）。
     *
     * <p>付费功能的成本阀门：用户在有效期内随时可以重新生成，但总次数有限。</p>
     */
    private long remainingGenerations;

    /** 今天已经生成几次（与前端的每日上限提示同一口径） */
    private int todayGenerations;

    /** 每天最多生成几次；0 = 不限制每天 */
    private int dailyGenerationLimit;

    /** 每个权益日赠送几次（购买页说明用） */
    private int creditsPerDay;
}
