package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.ToutiaoController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 今日头条号后台接口路由表（只挂 console 分组：管理员的写稿台，小程序端不消费）。
 *
 * <p>字面量路径（{@code /toutiao/generate}、{@code /toutiao/history}）必须声明在
 * {@code /toutiao/history/{name}} 之前——路由按声明顺序匹配，
 * 否则 {@code generate} 会被当成记录名（见 {@code MpEndpoint} 里的同一处教训）。</p>
 */
@Component
@RequiredArgsConstructor
public class ToutiaoEndpoint implements CustomEndpoint {

    private final ToutiaoController toutiaoController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.CONSOLE_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.CONSOLE_CUSTOM_API_GROUP_NAME + "/Toutiao";
        return SpringdocRouteBuilder.route()
            .GET("/toutiao/status", toutiaoController::status, builder -> builder
                .operationId("GetToutiaoStatus")
                .description("Today's Toutiao writing desk: enabled flag, generation defaults "
                    + "(words / title count / styles) and the current generation progress.")
                .tag(apiTag))
            .POST("/toutiao/generate", toutiaoController::generate, builder -> builder
                .operationId("GenerateToutiaoArticle")
                .description("Trigger one Toutiao article generation in background. Body: "
                    + "{keyword, style?, words?, titleCount?}. Returns immediately; "
                    + "poll /toutiao/status for progress and read the result from the history.")
                .tag(apiTag))
            .GET("/toutiao/history", toutiaoController::listHistory, builder -> builder
                .operationId("ListToutiaoHistory")
                .description("List generated Toutiao articles, newest first (summary only).")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("limit")
                    .in(ParameterIn.QUERY)
                    .description("Max records to return, 1-200, default 30")
                    .required(false)
                    .implementation(Integer.class)))
            .GET("/toutiao/history/{name}", toutiaoController::getArticle, builder -> builder
                .operationId("GetToutiaoArticle")
                .description("One Toutiao article record with title candidates, body, tags and "
                    + "the AI cost snapshot.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("ToutiaoArticle metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .DELETE("/toutiao/history/{name}", toutiaoController::deleteArticle, builder -> builder
                .operationId("DeleteToutiaoArticle")
                .description("Delete one Toutiao article record.")
                .tag(apiTag)
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .description("ToutiaoArticle metadata.name")
                    .required(true)
                    .implementation(String.class)))
            .build();
    }
}
