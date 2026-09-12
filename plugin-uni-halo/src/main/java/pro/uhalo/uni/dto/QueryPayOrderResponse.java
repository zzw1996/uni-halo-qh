package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryPayOrderResponse {

    private String outTradeNo;

    /** pending | delivered | closed */
    private String status;

    private Boolean delivered;

    /** 道具类型：publish_ticket | outfit（dashboard 仅见于历史订单；已发货时返回，供前端展示到账文案） */
    private String goodsType;

    /** 道具名称（已发货时返回） */
    private String goodsName;

    /** 当前积分余额（已发货时返回；道具直购不改变积分，仅用于前端刷新展示） */
    private Long balance;

    private String message;
}
