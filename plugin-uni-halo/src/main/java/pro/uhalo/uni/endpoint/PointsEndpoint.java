package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.PointsController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Component
@RequiredArgsConstructor
public class PointsEndpoint implements CustomEndpoint {

    private final PointsController pointsController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Points";
        final String basePath = Constants.END_POINT_API_BASE_PATH;

        return SpringdocRouteBuilder.route()
            .GET(basePath + "/points/overview", pointsController::overview,
                builder -> builder
                    .operationId("GetPointsOverview")
                    .description("Get user points overview and sign-in status.")
                    .tag(apiTag))
            .GET(basePath + "/points/history", pointsController::history,
                builder -> builder
                    .operationId("ListPointsHistory")
                    .description("List user points history with pagination.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, starts from 0")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class)))
            .GET(basePath + "/points/earn/status", pointsController::earnStatus,
                builder -> builder
                    .operationId("GetPointsEarnStatus")
                    .description("Check whether points can be earned for an article action today.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("action")
                        .in(ParameterIn.QUERY)
                        .description("browse | comment | upvote")
                        .required(true)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("articleName")
                        .in(ParameterIn.QUERY)
                        .description("Article name")
                        .required(true)
                        .implementation(String.class)))
            .POST(basePath + "/points/earn", pointsController::earn,
                builder -> builder
                    .operationId("EarnPoints")
                    .description("Earn points by browse/comment/upvote action.")
                    .tag(apiTag))
            .POST(basePath + "/points/sign-in", pointsController::signIn,
                builder -> builder
                    .operationId("PointsSignIn")
                    .description("Daily sign-in to earn points.")
                    .tag(apiTag))
            .build();
    }
}
