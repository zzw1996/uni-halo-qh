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
            .GET(basePath + "/userPublish/articles", userPublishController::listMyArticles,
                builder -> builder
                    .operationId("ListUserPublishArticles")
                    .description("List my own articles (published and pending drafts); "
                        + "filtered by owner on the Halo side.")
                    .tag(apiTag))
            .DELETE(basePath + "/userPublish/article/{name}", userPublishController::deleteArticle,
                builder -> builder
                    .operationId("DeleteUserPublishArticle")
                    .description("Recycle one of my own articles: owners can delete their own, "
                        + "blogger/admin can delete any.")
                    .tag(apiTag))
            .GET(basePath + "/userPublish/article/{name}", userPublishController::getArticle,
                builder -> builder
                    .operationId("GetUserPublishArticle")
                    .description("Get one of my own articles with the raw markdown content, "
                        + "used to fill the edit form.")
                    .tag(apiTag))
            .PUT(basePath + "/userPublish/article/{name}", userPublishController::updateArticle,
                builder -> builder
                    .operationId("UpdateUserPublishArticle")
                    .description("Save changes to one of my own articles (no points are charged). "
                        + "Changes of a published article go live immediately only for direct "
                        + "publish roles; pending ones wait for the blogger.")
                    .tag(apiTag))
            .PUT(basePath + "/userPublish/article/{name}/publish",
                userPublishController::publishMyArticle,
                builder -> builder
                    .operationId("PublishUserPublishArticle")
                    .description("Publish or re-publish one of my own articles (direct publish "
                        + "roles only).")
                    .tag(apiTag))
            .PUT(basePath + "/userPublish/article/{name}/unpublish",
                userPublishController::unpublishMyArticle,
                builder -> builder
                    .operationId("UnpublishUserPublishArticle")
                    .description("Unpublish one of my own articles: it turns back into a draft and "
                        + "disappears from the site.")
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
