package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.UserPublishController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 用户投稿接口路由（积分兑换发布文章 / 瞬间）。
 */
@Component
@RequiredArgsConstructor
public class UserPublishEndpoint implements CustomEndpoint {

    private final UserPublishController userPublishController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/UserPublish";
        final String basePath = Constants.END_POINT_API_BASE_PATH;

        return SpringdocRouteBuilder.route()
            .GET(basePath + "/userPublish/status", userPublishController::status,
                builder -> builder
                    .operationId("GetUserPublishStatus")
                    .description("Get user publish status: switch, points threshold, costs, "
                        + "role policy result, daily limits and remaining balance.")
                    .tag(apiTag))
            .POST(basePath + "/userPublish/article", userPublishController::publishArticle,
                builder -> builder
                    .operationId("PublishUserArticle")
                    .description("Publish an article with points: deducts points first and "
                        + "publishes as draft or live according to the role policy.")
                    .tag(apiTag))
            .POST(basePath + "/userPublish/moment", userPublishController::publishMoment,
                builder -> builder
                    .operationId("PublishUserMoment")
                    .description("Publish a moment with points: deducted points are refunded "
                        + "automatically when publishing fails.")
                    .tag(apiTag))
            .POST(basePath + "/userPublish/vote/{name}", userPublishController::submitVote,
                builder -> builder
                    .operationId("SubmitUserPublishVote")
                    .description("Submit a vote through the server-side token.")
                    .tag(apiTag))
            .DELETE(basePath + "/userPublish/moment/{name}", userPublishController::deleteMoment,
                builder -> builder
                    .operationId("DeleteUserPublishMoment")
                    .description("Delete a moment: owners can delete their own, blogger/admin any.")
                    .tag(apiTag))
            .POST(basePath + "/userPublish/image", userPublishController::uploadMomentImage,
                builder -> builder
                    .operationId("UploadUserPublishImage")
                    .description("Upload an image for a moment submission (multipart field: file).")
                    .tag(apiTag))
            .build();
    }
}
