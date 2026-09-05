package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.PayController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class PayEndpoint implements CustomEndpoint {

    private final PayController payController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/VirtualPay";
        final String basePath = Constants.END_POINT_API_BASE_PATH;

        return SpringdocRouteBuilder.route()
            .GET(basePath + "/pay/config", payController::publicConfig,
                builder -> builder
                    .operationId("GetVirtualPayConfig")
                    .description("Get virtual payment public config (goods list).")
                    .tag(apiTag))
            .POST(basePath + "/pay/order", payController::createOrder,
                builder -> builder
                    .operationId("CreateVirtualPayOrder")
                    .description("Create virtual payment order and build signed payData.")
                    .tag(apiTag))
            .POST(basePath + "/pay/query", payController::queryOrder,
                builder -> builder
                    .operationId("QueryVirtualPayOrder")
                    .description("Query order status; triggers fallback query_order sync when pending.")
                    .tag(apiTag))
            .POST(basePath + "/pay/notify", payController::deliverNotify,
                builder -> builder
                    .operationId("VirtualPayDeliverNotify")
                    .description("xpay_goods_deliver_notify endpoint, returns XML ErrCode.")
                    .tag(apiTag))
            .build();
    }
}
