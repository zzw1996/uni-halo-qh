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

    /** 到账积分 */
    private Integer points;
}
