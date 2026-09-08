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

    /** 到账积分（仅 type=points 旧模式使用，建议改用道具直购） */
    private Integer points;

    /** 道具类型：points（积分充值，旧模式）| dashboard（数据看板当日高级版）| publish_ticket（发布券）| outfit（装扮） */
    private String type;

    /** 道具数量（publish_ticket 为发布券张数；其余默认 1） */
    private Integer value;

    /** 装扮 key（type=outfit 时必填，对应 levelConfig.outfits.key） */
    private String style;
}
