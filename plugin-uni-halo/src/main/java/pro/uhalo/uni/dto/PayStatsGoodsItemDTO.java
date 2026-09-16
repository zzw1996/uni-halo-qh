package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 支付看板的商品维度统计。
 */
@Data
@Builder
public class PayStatsGoodsItemDTO {

    private String goodsId;

    private String goodsName;

    /** content_ticket | outfit | membership | bundle | dashboard | publish_ticket */
    private String goodsType;

    /** 已到货订单数 */
    private long orderCount;

    /** 已到货金额（分） */
    private long amountCents;
}
