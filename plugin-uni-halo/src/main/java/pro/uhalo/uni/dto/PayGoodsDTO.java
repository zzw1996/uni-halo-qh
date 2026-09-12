package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

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
     * 道具类型：publish_ticket（发布券）| content_ticket（阅读券）| outfit（装扮）。
     *
     * <p>dashboard（数据看板当日高级版）已降级为纯积分兑换权益，不再作为付费道具下发；
     * 订单模型中仍可能出现该值，用于履约升级前的历史已支付订单。</p>
     *
     * <p>个人主体小程序仅允许「道具直购」，官方明确不引入代币等额外功能，
     * 因此不支持 points（现金充值积分）类型。</p>
     */
    private String type;

    /**
     * 道具数量。
     *
     * <p>publish_ticket = 发放的发布券张数；content_ticket = 发放的阅读券张数；
     * outfit 固定为 1。</p>
     */
    private Integer value;

    /** 装扮 key（type=outfit 时必填，对应 levelConfig.outfits.key） */
    private String style;
}
