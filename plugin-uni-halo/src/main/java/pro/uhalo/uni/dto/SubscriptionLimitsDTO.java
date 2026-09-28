package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 订阅额度视图：告诉客户端「现在订了几个、还能订几个、怎么才能多订」。
 *
 * <p>由服务端算好下发，客户端不再自己判断上限——上限与免费额度都是可配的，
 * 客户端硬编码一个 5 迟早和服务端对不上（现在就是这样：`maxSubscribe: 5` 抄的是服务端常量）。</p>
 */
@Data
@Builder
public class SubscriptionLimitsDTO {

    /** 是否允许用积分解锁（运营开关，关着时客户端不显示解锁入口） */
    private boolean purchaseEnabled;

    /** 免费额度 */
    private int freeQuota;

    /** 已购额度（永久，取消订阅不退还） */
    private int purchasedQuota;

    /** 每份额度的积分价格 */
    private int quotaPrice;

    /** 最多还能买几份 */
    private int maxQuota;

    /** 当前生效的订阅上限（会员期内是会员上限） */
    private int limit;

    /** 会员是否有效 */
    private boolean memberActive;

    /** 会员到期时间（展示用，格式 yyyy-MM-dd HH:mm；未开通为空） */
    private String memberExpireAt;

    /** 已用的订阅数（自己订阅的部分，不含继承） */
    private int used;

    /** 是否还能再买一份（开关开 && 未达最大份数） */
    private boolean canPurchase;

    /** 当前积分余额（解锁弹层里显示「当前 X 积分」） */
    private long pointsBalance;

    /**
     * 因超出额度而未生效的分类（按订阅顺序排在额度之外的那些）。
     *
     * <p>它们<b>没有丢失</b>：记录还在、顺序还在，续费会员或解锁额度后自动恢复。
     * 发现页要把它们标出来并给一键恢复入口，否则用户会以为订阅被吞了。</p>
     */
    private List<String> pausedCategories;
}
