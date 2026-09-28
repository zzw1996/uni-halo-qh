package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 分类订阅的额度规则（设置分组 {@code subscriptionConfig}）。
 *
 * <p>订阅份额从「写死的 5 个」变成「免费额度 + 可用积分解锁的额度」，会员期内再放宽到一个
 * 更大的上限。所有数字都在这里，运行时不散落任何魔数。</p>
 *
 * <p><b>上限为什么还要有硬顶</b>：首页要按订阅的分类并发取内容，订阅数直接等于请求数；
 * 会员的 {@link #memberMaxQuota} 就是那个硬顶（默认 30，对绝大多数站点已等于不限量），
 * 防止「一个账号订光全站分类」把首页拖成几十路请求。</p>
 */
@Data
@Builder
public class SubscriptionConfigDTO {

    /**
     * 是否允许用积分解锁订阅额度（默认关）。
     *
     * <p>默认关是刻意的：价格与开关是运营决策，站长没配之前不该悄悄上线一个能花积分的入口。</p>
     */
    @Builder.Default
    private boolean enabled = false;

    /** 免费额度：不花积分就能订阅的站点分类数 */
    @Builder.Default
    private int freeQuota = 5;

    /** 每份额度的积分价格 */
    @Builder.Default
    private int quotaPrice = 50;

    /** 最多可购买几份额度（防止无限扩容） */
    @Builder.Default
    private int maxQuota = 10;

    /** 会员有效期内的订阅上限（对普通站点等同于「不限量」） */
    @Builder.Default
    private int memberMaxQuota = 30;

    /** 首页并集接口每个分类取多少条（越大首页越全、内部请求越重） */
    @Builder.Default
    private int homeFeedPerCategory = 20;

    /** 零订阅时取人气前几个分类做推荐 */
    @Builder.Default
    private int hotCategoryCount = 3;

    /** 首页并集结果的缓存秒数（同一批分类在这段时间内只取一次） */
    @Builder.Default
    private int homeFeedCacheSeconds = 60;

    /** 非会员的订阅上限：免费额度 + 已购额度 */
    public int limitFor(int purchasedQuota) {
        return Math.max(1, freeQuota) + Math.max(0, Math.min(purchasedQuota, maxQuota));
    }

    /** 会员期的订阅上限 */
    public int memberLimit() {
        return Math.max(Math.max(1, freeQuota), memberMaxQuota);
    }
}
