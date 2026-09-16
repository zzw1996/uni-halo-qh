package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 支付数据看板（后台）。
 *
 * <p>做这个的原因：此前后台只有「文章/评论/访问量」这类内容维度指标，
 * 完全不含订单量、GMV、付费用户数与券的发放消耗比。站长因此无法判断
 * 哪个档位该提价、哪款装扮该下架、赠券力度是不是已经过头——
 * 所有商业化决策都只能靠感觉。</p>
 *
 * <p>口径说明：GMV 与各商品销量只统计 <b>已到货（delivered）</b> 的订单。
 * 待支付、需退款都不是有效收入，把它们算进去会让看板虚高。</p>
 */
@Data
@Builder
public class PayStatsDTO {

    private long totalOrders;

    private long deliveredOrders;

    private long pendingOrders;

    private long needsRefundOrders;

    private long closedOrders;

    /** 已到货订单的实付合计（分） */
    private long grossAmountCents;

    /** 付费用户数（有已到货订单的去重 openid 数） */
    private long payingUsers;

    /** 客单价（分）：grossAmountCents / deliveredOrders，无订单时为 0 */
    private long avgAmountCents;

    /** 现金购买的阅读券张数 */
    private long ticketsPurchased;

    /** 免费赠出的阅读券张数（签到/连签/新人/首购/任务/会员开通礼） */
    private long ticketsFreeGranted;

    /** 已消耗的阅读券张数（解锁付费文章） */
    private long ticketsConsumed;

    /**
     * 券消耗率（%）：已消耗 / （现金购买 + 免费赠送）。
     *
     * <p>这是判断赠券力度是否过头的唯一依据：接近 100% 说明券一到手就被用掉、
     * 免费券确实带来了阅读；长期偏低说明用户拿了券却没用，赠券只是在稀释付费意愿。</p>
     */
    private long ticketConsumeRate;

    /** 券余额合计（所有账户当前持有的券） */
    private long ticketsInHand;

    /** 已售装扮数量（已到货订单里的装扮类商品件数） */
    private long outfitSold;

    /** 已售会员卡数量 */
    private long membershipSold;

    private long contentTicketOrders;

    /** 近 14 天趋势（含今天，升序） */
    private List<PayStatsTrendItemDTO> trend;

    /** 商品维度排行（按已到货订单金额倒序） */
    private List<PayStatsGoodsItemDTO> goodsRanking;
}
