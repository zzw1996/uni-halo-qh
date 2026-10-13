package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.MpController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 微信公众号后台接口路由表（只挂 console 分组：站长运维入口，小程序端不消费）。
 *
 * <p>字面量路径（{@code /mp/issues/generate} 等）必须声明在 {@code /mp/issues/{name}}
 * 之前——路由按声明顺序匹配，否则 {@code generate} 会被当成稿件名。</p>
 */
@Component
@RequiredArgsConstructor
public class MpEndpoint implements CustomEndpoint {

    private final MpController mpController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.CONSOLE_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.CONSOLE_CUSTOM_API_GROUP_NAME + "/Mp";
        return SpringdocRouteBuilder.route()
            .GET("/mp/status", mpController::status, builder -> builder
                .operationId("GetMpStatus")
                .description("WeChat Official Account channel: masked config, generation progress, "
                    + "push state and issue counts.")
                .tag(apiTag))
            .GET("/mp/issues", mpController::listIssues, builder -> builder
                .operationId("ListMpIssues")
                .description("List generated WeChat Official Account issues, newest first.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("limit")
                    .in(ParameterIn.QUERY)
                    .description("Max records to return, 1-200, default 30")
                    .required(false)
                    .implementation(Integer.class)))
            .POST("/mp/issues/generate", mpController::generate, builder -> builder
                .operationId("GenerateMpIssues")
                .description("Trigger WeChat article generation in background. Body/query may carry "
                    + "{direction} to limit the run to one direction. Poll /mp/status for progress.")
                .tag(apiTag))
            .POST("/mp/issues/push-all", mpController::pushAll, builder -> builder
                .operationId("PushAllMpIssues")
                .description("Push every direction of one date as its own separate draft "
                    + "(one WeChat article per direction). Query: date=yyyy-MM-dd, "
                    + "force=true to re-push. Poll /mp/status for per-item progress and results.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("date")
                    .in(ParameterIn.QUERY)
                    .description("Issue date, yyyy-MM-dd")
                    .required(true)
                    .implementation(String.class))
                .parameter(parameterBuilder()
                    .name("force")
                    .in(ParameterIn.QUERY)
                    .description("true = re-push even when a draft already exists")
                    .required(false)
                    .implementation(Boolean.class)))
            .POST("/mp/issues/push-merged", mpController::pushMerged, builder -> builder
                .operationId("PushMergedMpIssues")
                .description("Push every issue of one date as a single multi-article draft. "
                    + "Query: date=yyyy-MM-dd, force=true to re-push already pushed issues.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("date")
                    .in(ParameterIn.QUERY)
                    .description("Issue date, yyyy-MM-dd")
                    .required(true)
                    .implementation(String.class))
                .parameter(parameterBuilder()
                    .name("force")
                    .in(ParameterIn.QUERY)
                    .description("true = re-push even when a draft already exists")
                    .required(false)
                    .implementation(Boolean.class)))
            .GET("/mp/backfill", mpController::listBackfill, builder -> builder
                .operationId("ListMpBackfill")
                .description("Recent AI digest runs with the WeChat backfill drafts already built "
                    + "from them.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("limit")
                    .in(ParameterIn.QUERY)
                    .description("Max digest runs to return, 1-200, default 30")
                    .required(false)
                    .implementation(Integer.class)))
            .POST("/mp/backfill", mpController::backfill, builder -> builder
                .operationId("BuildMpBackfill")
                .description("Build WeChat drafts from one AI digest run. Body: "
                    + "{reportName, mode: merged|per-article}. No AI calls: the stored content "
                    + "of that run is used as-is.")
                .tag(apiTag))
            .GET("/mp/selfcheck", mpController::selfCheck, builder -> builder
                .operationId("SelfCheckMp")
                .description("WeChat connectivity self check: credentials, stable_token, IP "
                    + "whitelist, image upload, cover material. deep=true also creates and "
                    + "immediately deletes a test draft to verify draft-box access.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("deep")
                    .in(ParameterIn.QUERY)
                    .description("true = also probe draft/add (creates and deletes a test draft)")
                    .required(false)
                    .implementation(Boolean.class)))
            .GET("/mp/issues/{name}", mpController::getIssue, builder -> builder
                .operationId("GetMpIssue")
                .description("One WeChat issue with markdown, WeChat-safe HTML and the selected "
                    + "articles.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("MpIssue metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .POST("/mp/issues/{name}/prepare", mpController::prepareIssue, builder -> builder
                .operationId("PrepareMpIssue")
                .description("Upload in-article images to WeChat and cache the rewritten HTML. "
                    + "Used before copying the body into the MP editor.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("MpIssue metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .POST("/mp/issues/{name}/push", mpController::pushIssue, builder -> builder
                .operationId("PushMpIssue")
                .description("Push one issue to the WeChat draft box (and publish/broadcast when "
                    + "configured). Query: force=true to re-push.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("MpIssue metadata.name")
                    .required(true)
                    .implementation(String.class))
                .parameter(parameterBuilder()
                    .name("force")
                    .in(ParameterIn.QUERY)
                    .description("true = push again even when already pushed")
                    .required(false)
                    .implementation(Boolean.class)))
            .DELETE("/mp/issues/{name}", mpController::deleteIssue, builder -> builder
                .operationId("DeleteMpIssue")
                .description("Delete one WeChat issue record. Drafts already in the MP account "
                    + "are not affected.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("MpIssue metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .build();
    }
}
