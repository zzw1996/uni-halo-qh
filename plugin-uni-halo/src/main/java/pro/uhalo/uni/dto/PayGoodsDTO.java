package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 虚拟支付道具商品（前端展示 + 下单换算）。
 */
@Data
@Builder
public class PayGoodsDTO {

    private String goodsId;

    private String name;

    /** 单价（分），与微信后台道具价格一致 */
    private Long price;

    /**
     * 道具类型：content_ticket（阅读券）| outfit（装扮）| membership（会员卡）| bundle（套餐）。
     *
     * <p>dashboard（数据看板当日高级版）已降级为纯积分兑换权益，不再作为付费道具下发；
     * 订单模型中仍可能出现该值，用于履约升级前的历史已支付订单。</p>
     *
     * <p>个人主体小程序仅允许「道具直购」，官方明确不引入代币等额外功能，
     * 因此不支持 points（现金充值积分）类型。</p>
     *
     * <p>membership 是「单次购买的有效期权益」，属官方明确的适用场景
     * （会员订阅：VIP 会员、专属权益包），不是需要单独签约的自动续费订阅。</p>
     */
    private String type;

    /**
     * 道具数量。
     *
     * <p>publish_ticket = 发放的发布券张数；content_ticket = 发放的阅读券张数；
     * membership = 会员天数（0 或缺失时回落 memberConfig.defaultDays）；outfit 固定为 1。</p>
     */
    private Integer value;

    /** 装扮 key（type=outfit 时必填，对应 levelConfig.outfits.key） */
    private String style;

    /**
     * 套餐子项（仅 type=bundle 有值且非空）。
     *
     * <p>为空的 bundle 商品不会下发：一条什么权益都不含的套餐卖出去只会产生退款纠纷。</p>
     */
    private List<PayGoodsItemDTO> items;

    /**
     * 划线原价（分），仅用于前端展示「￥19.9 ~~￥30~~」。
     *
     * <p>不参与任何计算：微信侧的道具价格由 {@link #price} 决定且必须与 MP 后台一致，
     * 这里纯粹是促单展示。原价必须高于现价，否则解析时丢弃——
     * 虚构或倒挂的原价既误导用户也违反价格标示要求。</p>
     */
    private Long originalPrice;

    /**
     * 限时特价截止时间（Unix 秒），仅用于前端展示倒计时。
     *
     * <p>过期后前端自动隐藏「限时」标记与划线价，实际售价始终是 {@link #price}，
     * 因此不存在「活动结束后价格对不上微信后台」的风险。</p>
     */
    private Long promoEndsAt;

    /**
     * 道具图片（可选），小程序商品卡展示。
     *
     * <p>微信 MP 后台【道具管理】里配置的图片<b>不会下发给小程序</b>——微信没有提供
     * 读取道具详情的接口，图片只在支付相关的推送报文里出现。所以商品卡想展示图片，
     * 需要站长把同一张图的 URL 填在插件配置里；留空则前端回落到类型图标。</p>
     */
    private String image;
}
