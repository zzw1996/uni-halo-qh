package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.LuckyShipRequest;
import pro.uhalo.uni.dto.LuckyWinningUpdateRequest;
import pro.uhalo.uni.services.InviteService;
import pro.uhalo.uni.services.LuckyDrawService;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.UserActivityService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Component
@Slf4j
public class ConsoleEndpoint implements CustomEndpoint {

    private final WxUserService wxUserService;
    private final UserActivityService userActivityService;
    private final PointsService pointsService;
    private final LuckyDrawService luckyDrawService;
    private final InviteService inviteService;

    public ConsoleEndpoint(WxUserService wxUserService,
                           UserActivityService userActivityService,
                           PointsService pointsService,
                           LuckyDrawService luckyDrawService,
                           InviteService inviteService) {
        this.wxUserService = wxUserService;
        this.userActivityService = userActivityService;
        this.pointsService = pointsService;
        this.luckyDrawService = luckyDrawService;
        this.inviteService = inviteService;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.CONSOLE_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.CONSOLE_CUSTOM_API_GROUP_NAME + "/Console";

        return SpringdocRouteBuilder.route()
            .GET("/wxUsers", this::listWxUsers, builder -> {
                builder.operationId("ConsoleListWxUsers")
                    .description("List WeChat mini program users for console.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter by nickname, openid, Halo username, unionid or user id")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, zero-based")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/userActivities", this::listUserActivities, builder -> {
                builder.operationId("ConsoleListUserActivities")
                    .description("List WeChat user activities for console.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("type")
                        .in(ParameterIn.QUERY)
                        .description("Activity type: browse, comment, upvote")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter by nickname, openid, Halo username, article title or name")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, zero-based")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/pointsRecords", this::listPointsRecords, builder -> {
                builder.operationId("ConsoleListPointsRecords")
                    .description("List WeChat user points records for console.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("action")
                        .in(ParameterIn.QUERY)
                        .description("Points action: browse, comment, upvote, sign_in")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter by nickname, openid, Halo username, ref name or description")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, zero-based")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/inviteRecords", this::listInviteRecords, builder -> {
                builder.operationId("ConsoleListInviteRecords")
                    .description("List invite records and per-inviter summary for console.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter by inviter/invitee nickname, openid or Halo username")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, zero-based")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/luckyWinnings", this::listLuckyWinnings, builder -> {
                builder.operationId("ConsoleListLuckyWinnings")
                    .description("List lucky draw winning orders for console (shipping).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("status")
                        .in(ParameterIn.QUERY)
                        .description("Status: pending, submitted or shipped")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter by prize name, contact name, phone or openid")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, zero-based")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class));
            })
            .PUT("/luckyWinnings/{name}/ship", this::shipLuckyWinning, builder -> {
                builder.operationId("ConsoleShipLuckyWinning")
                    .description("Mark a lucky winning as shipped (express info optional).")
                    .tag(apiTag);
            })
            .PUT("/luckyWinnings/{name}/reopen", this::reopenLuckyWinning, builder -> {
                builder.operationId("ConsoleReopenLuckyWinning")
                    .description("Revert a shipped lucky winning back to submitted.")
                    .tag(apiTag);
            })
            .PUT("/luckyWinnings/{name}", this::updateLuckyWinning, builder -> {
                builder.operationId("ConsoleUpdateLuckyWinning")
                    .description("Update a lucky winning (status, contact info, remark).")
                    .tag(apiTag);
            })
            .DELETE("/luckyWinnings/{name}", this::deleteLuckyWinning, builder -> {
                builder.operationId("ConsoleDeleteLuckyWinning")
                    .description("Delete a lucky winning.")
                    .tag(apiTag);
            })
            .build();
    }

    Mono<ServerResponse> updateLuckyWinning(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(LuckyWinningUpdateRequest.class)
            .defaultIfEmpty(new LuckyWinningUpdateRequest())
            .flatMap(body -> luckyDrawService.update(name, body)
                .flatMap(response -> jsonOk(response)))
            .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console updateLuckyWinning failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "更新中奖单失败"));
            });
    }

    Mono<ServerResponse> deleteLuckyWinning(ServerRequest request) {
        var name = request.pathVariable("name");
        return luckyDrawService.delete(name)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console deleteLuckyWinning failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "删除中奖单失败"));
            });
    }

    Mono<ServerResponse> shipLuckyWinning(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(LuckyShipRequest.class)
            .defaultIfEmpty(new LuckyShipRequest())
            .flatMap(body -> luckyDrawService.ship(name, body)
                .flatMap(response -> jsonOk(response)))
            .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console shipLuckyWinning failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "标记发货失败"));
            });
    }

    Mono<ServerResponse> reopenLuckyWinning(ServerRequest request) {
        var name = request.pathVariable("name");
        return luckyDrawService.reopenShipment(name)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console reopenLuckyWinning failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "取消发货失败"));
            });
    }

    Mono<ServerResponse> listLuckyWinnings(ServerRequest request) {
        var status = request.queryParam("status").orElse(null);
        var keyword = request.queryParam("keyword").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return luckyDrawService.listAllWinningsForAdmin(status, keyword, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listLuckyWinnings failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询中奖记录失败"));
            });
    }

    Mono<ServerResponse> listInviteRecords(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return inviteService.listInviteRecordsForAdmin(keyword, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listInviteRecords failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询邀请记录失败"));
            });
    }

    Mono<ServerResponse> listWxUsers(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return wxUserService.listUsers(keyword, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listWxUsers failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询小程序用户失败"));
            });
    }

    Mono<ServerResponse> listUserActivities(ServerRequest request) {
        var type = request.queryParam("type").orElse(null);
        var keyword = request.queryParam("keyword")
            .filter(StringUtils::hasText)
            .or(() -> request.queryParam("openid").filter(StringUtils::hasText))
            .orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return userActivityService.listAllActivities(type, keyword, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listUserActivities failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询用户行为失败"));
            });
    }

    Mono<ServerResponse> listPointsRecords(ServerRequest request) {
        var action = request.queryParam("action").orElse(null);
        var keyword = request.queryParam("keyword").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return pointsService.listAllRecordsForAdmin(action, keyword, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listPointsRecords failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询积分记录失败"));
            });
    }

    private static int parseIntParam(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
            .filter(StringUtils::hasText)
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    private static Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private static Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.badRequest()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("error", message));
    }
}
