package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.DigestController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * AI 日报后台接口路由表。
 *
 * <p>只挂在 console 分组下：这是站长的运维入口，小程序端不消费这些数据。</p>
 */
@Component
@RequiredArgsConstructor
public class DigestEndpoint implements CustomEndpoint {

    private final DigestController digestController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.CONSOLE_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.CONSOLE_CUSTOM_API_GROUP_NAME + "/Digest";
        return SpringdocRouteBuilder.route()
            .GET("/digest/overview", digestController::overview, builder -> builder
                .operationId("GetDigestOverview")
                .description("AI digest overview: enable flags, next scheduled run and the last run.")
                .tag(apiTag))
            .GET("/digest/reports", digestController::listReports, builder -> builder
                .operationId("ListDigestReports")
                .description("List AI digest run records, newest first.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("limit")
                    .in(ParameterIn.QUERY)
                    .description("Max records to return, 1-200, default 30")
                    .required(false)
                    .implementation(Integer.class)))
            .GET("/digest/reports/{name}", digestController::getReport, builder -> builder
                .operationId("GetDigestReport")
                .description("Digest run detail including the generated markdown.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("DigestReport metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .GET("/digest/preflight", digestController::preflight, builder -> builder
                .operationId("GetDigestPreflight")
                .description("Preflight check for the publish environment: personal token, "
                    + "post owner user and category/tag matching.")
                .tag(apiTag))
            .POST("/digest/cleanup", digestController::cleanupResidues, builder -> builder
                .operationId("CleanupDigestResidues")
                .description("Preview (?dryRun=true, default) or purge posts written by the plugin "
                    + "that never published successfully. Finalizers are removed before deleting.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("dryRun")
                    .in(ParameterIn.QUERY)
                    .description("true = preview only, false = delete")
                    .required(false)
                    .implementation(Boolean.class)))
            .DELETE("/digest/reports/{name}", digestController::deleteReport, builder -> builder
                .operationId("DeleteDigestReport")
                .description("Delete one digest run record. Published posts are not affected.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("DigestReport metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .POST("/digest/run", digestController::run, builder -> builder
                .operationId("RunDigest")
                .description("Trigger a digest run in background. Body: {\"publish\": true|false}. "
                    + "The pipeline takes 1-3 minutes; poll the overview endpoint for progress.")
                .tag(apiTag))
            .POST("/digest/reports/{name}/publish", digestController::publishReport,
                builder -> builder
                    .operationId("PublishDigestReport")
                    .description("Publish an already generated digest as a Halo post.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.PATH)
                        .description("DigestReport metadata.name")
                        .required(true)
                        .implementation(String.class)))
            .GET("/digest/classify/status", digestController::classifyStatus, builder -> builder
                .operationId("GetDigestClassifyStatus")
                .description("How many published posts already carry one of the digest "
                    + "categories, and how many are still unclassified.")
                .tag(apiTag))
            .GET("/digest/models", digestController::aiModels, builder -> builder
                .operationId("GetDigestModels")
                .description("AI model channels: the configured list, the channel in effect, the "
                    + "per-task channel in effect, and the legacy main/fallback pair. Each "
                    + "channel may carry a task list (score/write/overview/classify/content); "
                    + "empty means every task. API keys are masked before sending.")
                .tag(apiTag))
            .PUT("/digest/models", digestController::saveAiModels, builder -> builder
                .operationId("SaveDigestModels")
                .description("Save the AI model channel list. Only the aiProviders field is "
                    + "replaced; every other digest setting is preserved. Blank or masked keys "
                    + "keep the stored value, task lists are taken as submitted.")
                .tag(apiTag))
            .GET("/digest/models/balance", digestController::aiBalances, builder -> builder
                .operationId("GetDigestModelBalances")
                .description("Query the account balance of each usable AI channel via the "
                    + "provider's official endpoint (DeepSeek: GET /user/balance). Channels that "
                    + "do not support it report that instead of failing. No tokens consumed.")
                .tag(apiTag))
            .POST("/digest/models/prices/refresh", digestController::refreshAiPrices,
                builder -> builder
                    .operationId("RefreshDigestPrices")
                    .description("Read the model price table from the provider's official pricing "
                        + "page (DeepSeek documents no price API). Same-model entries are replaced; "
                        + "other entries in the table are kept. On failure the built-in table stays.")
                    .tag(apiTag))
            .POST("/digest/models/test", digestController::testAiModel, builder -> builder
                .operationId("TestDigestModel")
                .description("Test one AI channel with a single tiny request (8 output tokens). "
                    + "The body is one panel row; a blank or masked key resolves to the stored one.")
                .tag(apiTag))
            .GET("/digest/categories", digestController::digestCategories, builder -> builder
                .operationId("GetDigestCategories")
                .description("Effective digest category list: where each configured category lands "
                    + "when publishing, plus rows that would be silently skipped.")
                .tag(apiTag))
            .GET("/digest/categories/auto-created", digestController::autoCreatedCategories,
                builder -> builder
                    .operationId("ListAutoCreatedCategories")
                    .description("Categories created by the plugin automatically, with how many "
                        + "posts reference them (those with zero can be purged).")
                    .tag(apiTag))
            .DELETE("/digest/categories/auto-created",
                digestController::purgeAutoCreatedCategories, builder -> builder
                    .operationId("PurgeAutoCreatedCategories")
                    .description("Delete auto-created categories that no post references. "
                        + "Categories still in use are skipped.")
                    .tag(apiTag))
            .POST("/digest/classify/preview", digestController::classifyPreview, builder -> builder
                .operationId("PreviewDigestClassify")
                .description("Ask the AI to classify the next batch of unclassified posts. "
                    + "Read-only: nothing is written until the suggestions are applied.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("limit")
                    .in(ParameterIn.QUERY)
                    .description("Batch size, 1-30, default 15")
                    .required(false)
                    .implementation(Integer.class)))
            .POST("/digest/classify/apply", digestController::classifyApply, builder -> builder
                .operationId("ApplyDigestClassify")
                .description("Apply the accepted suggestions. Categories and tags are only "
                    + "appended to spec.categories/spec.tags; existing data is never overwritten.")
                .tag(apiTag))
            .build();
    }
}
