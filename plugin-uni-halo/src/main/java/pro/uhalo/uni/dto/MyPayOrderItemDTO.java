package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户侧单条订单（「我的订单」页展示）。
 *
 * <p>只暴露用户自己需要判断的东西：买了什么、多少钱、当前状态、什么时候付的。
 * 微信平台单号（wxOrderId / transactionId）一律不下发——用户拿它没用，
 * 而退款沟通时由站长在后台核对即可。</p>
 */
@Data
@Builder
public class MyPayOrderItemDTO {

    private String outTradeNo;

    private String goodsName;

    /** content_ticket | outfit | dashboard | publish_ticket */
    private String goodsType;

    /** 道具类型的中文名，便于前端直接展示 */
    private String goodsTypeLabel;

    /** 单价（分），与下单时一致 */
    private Long price;

    private Integer quantity;

    /** 实付金额（分）= price * quantity */
    private Long amount;

    /** pending | delivered | closed | needs_refund */
    private String status;

    /** 状态的中文说明（含用户下一步该做什么） */
    private String statusLabel;

    private String paidTime;

    private String createTime;

    private String deliverTime;

    /** 需人工处理的原因（仅 needs_refund 有值） */
    private String remark;
}
