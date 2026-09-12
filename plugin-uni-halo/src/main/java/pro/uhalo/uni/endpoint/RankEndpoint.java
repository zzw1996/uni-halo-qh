package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.RankController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class RankEndpoint implements CustomEndpoint {

    private final RankController rankController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Rank";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/rank/articles", rankController::topArticles,
                builder -> builder
                    .operationId("GetTopArticles")
                    .description("Top articles by visit counters for home hot rank module.")
                    .tag(apiTag))
            .GET(Constants.END_POINT_API_BASE_PATH + "/rank/activities",
                rankController::recentActivities,
                builder -> builder
                    .operationId("GetRankActivities")
                    .description("Recent comment/vote (and optionally upvote) activities for the "
                        + "ranking page activity wall. Public read.")
                    .tag(apiTag))
            .GET(Constants.END_POINT_API_BASE_PATH + "/rank/users", rankController::topUsers,
                builder -> builder
                    .operationId("GetUserRank")
                    .description("User ranking by earned points / weekly activity / invites. "
                        + "Public read; the current user's own rank is returned when the "
                        + "Wechat-Session-Id header is present.")
                    .tag(apiTag))
            .build();
    }
}
