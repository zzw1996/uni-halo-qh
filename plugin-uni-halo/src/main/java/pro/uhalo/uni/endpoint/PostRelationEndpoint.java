package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.PostNeighborsDTO;
import pro.uhalo.uni.services.PostRelationService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 文章关系接口（文章详情页底部模块用）。
 *
 * <p>匿名可读：详情页对未登录访客同样要展示「上一篇 / 下一篇」，
 * 因此这个路由必须在匿名角色的放行清单里（见 extensions/role-post-relation-anonymous.yaml）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostRelationEndpoint implements CustomEndpoint {

    private final PostRelationService postRelationService;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/PostRelation";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/posts/neighbors", this::neighbors,
                builder -> builder
                    .operationId("GetPostNeighbors")
                    .description("上一篇 / 下一篇：口径与首页主列表一致（置顶优先 + 发布时间倒序，"
                        + "登录且订阅了分类时只看订阅分类内的文章），取列表里上下相邻的两篇。"
                        + "匿名可读；带 Wechat-Session-Id 时按该用户的订阅算。"
                        + "没有对应文章时相应字段为 null，前端隐藏按钮。")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("name")
                        .in(ParameterIn.QUERY)
                        .description("文章 metadata.name")
                        .required(true)
                        .implementation(String.class)))
            .build();
    }

    Mono<ServerResponse> neighbors(ServerRequest request) {
        var name = request.queryParam("name").orElse("");
        // 带上会话头就按该用户的订阅算（和首页看到的列表一致）；没带就是未登录，走全站口径
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return postRelationService.fetchNeighbors(name, openid)
            .defaultIfEmpty(PostNeighborsDTO.empty())
            .flatMap(neighbors -> ServerResponse.ok().bodyValue(neighbors))
            .onErrorResume(ex -> {
                // 这个模块只是详情页的点缀，接口出错时返回「没有邻居」比返回 500 更合适：
                // 前者页面安静地少一块，后者会在控制台留一条红字并可能让调用方弹错误提示
                log.warn("查询文章上下篇失败：name={}", name, ex);
                return ServerResponse.ok().bodyValue(PostNeighborsDTO.empty());
            });
    }
}
