package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.DashboardController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class DashboardEndpoint implements CustomEndpoint {

    private final DashboardController dashboardController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Dashboard";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/dashboard/summary", dashboardController::summary,
                builder -> builder
                    .operationId("GetDashboardSummary")
                    .description("Site dashboard summary computed by plugin itself (no third-party plugin).")
                    .tag(apiTag))
            .build();
    }
}
