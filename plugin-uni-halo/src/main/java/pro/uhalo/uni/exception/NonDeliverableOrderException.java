package pro.uhalo.uni.exception;

/**
 * 订单已支付但权益不可发放（如重复购买已拥有的装扮）。
 *
 * <p>与普通业务异常的关键区别是<b>处理方式</b>：这类订单重试多少次都不会成功，
 * 必须转入终态（needs_refund）并交由站长人工退款。发货链路捕获该异常后置终态、
 * 记 ERROR 日志，不再向上抛——否则订单会永久停留 pending，
 * 被 5 分钟兜底查单无限重试，用户已付款却既无权益也无退款入口。</p>
 */
public class NonDeliverableOrderException extends WxAuthException {

    public NonDeliverableOrderException(String reason) {
        super(reason);
    }
}
