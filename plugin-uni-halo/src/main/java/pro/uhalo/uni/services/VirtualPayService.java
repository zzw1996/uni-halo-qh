package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.CreatePayOrderRequest;
import pro.uhalo.uni.dto.CreatePayOrderResponse;
import pro.uhalo.uni.dto.PayPublicConfigDTO;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderResponse;
import reactor.core.publisher.Mono;

public interface VirtualPayService {

    Mono<PayPublicConfigDTO> getPublicConfig();

    Mono<CreatePayOrderResponse> createOrder(String openid, CreatePayOrderRequest request);

    Mono<QueryPayOrderResponse> queryOrder(String openid, QueryPayOrderRequest request);

    /**
     * 处理微信发货推送（xpay_goods_deliver_notify），返回微信要求的 XML 响应：
     * {@code <xml><ErrCode>0</ErrCode></xml>} 表示成功，否则平台重试（最多 15 次）。
     */
    Mono<String> handleDeliverNotify(String rawXml);

    /** 定时兜底：每 5 分钟查单，发现已支付但未发货的订单补发货。 */
    void syncPendingOrders();
}
