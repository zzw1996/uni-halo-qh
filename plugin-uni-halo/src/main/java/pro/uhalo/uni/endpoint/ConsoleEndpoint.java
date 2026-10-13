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
import pro.uhalo.uni.services.InviteService;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.UserActivityService;
import pro.uhalo.uni.services.VirtualPayService;
import pro.uhalo.uni.dto.CommercialConfigSaveRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.impl.CommercialConfigService;
import pro.uhalo.uni.services.impl.PayStatsService;
import pro.uhalo.uni.services.impl.PushDiagnoseService;
import pro.uhalo.uni.services.impl.PushStatsService;
import pro.uhalo.uni.utils.WxRequestParser;
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
    private final InviteService inviteService;
    private final VirtualPayService virtualPayService;
    private final PayStatsService payStatsService;
    private final PushStatsService pushStatsService;
    private final PushDiagnoseService pushDiagnoseService;
    private final CommercialConfigService commercialConfigService;
    private final pro.uhalo.uni.services.impl.AiContentUsageService aiContentUsageService;

    public ConsoleEndpoint(WxUserService wxUserService,
                           UserActivityService userActivityService,
                           PointsService pointsService,
                           InviteService inviteService,
                           VirtualPayService virtualPayService,
                           PayStatsService payStatsService,
                           PushStatsService pushStatsService,
                           PushDiagnoseService pushDiagnoseService,
                           CommercialConfigService commercialConfigService,
                           pro.uhalo.uni.services.impl.AiContentUsageService aiContentUsageService) {
        this.wxUserService = wxUserService;
        this.userActivityService = userActivityService;
        this.pointsService = pointsService;
        this.inviteService = inviteService;
        this.virtualPayService = virtualPayService;
        this.payStatsService = payStatsService;
        this.pushStatsService = pushStatsService;
        this.pushDiagnoseService = pushDiagnoseService;
        this.commercialConfigService = commercialConfigService;
        this.aiContentUsageService = aiContentUsageService;
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
            .GET("/commercialConfig", this::getCommercialConfig, builder -> {
                builder.operationId("ConsoleGetCommercialConfig")
                    .description("Commercial configuration page: related setting groups "
                        + "(payment/member/content/ticket/points/tasks/mall/level) with their "
                        + "form schema (from the Setting extension), current values and "
                        + "cross-group consistency checks.")
                    .tag(apiTag);
            })
            .PUT("/commercialConfig", this::saveCommercialConfig, builder -> {
                builder.operationId("ConsoleSaveCommercialConfig")
                    .description("Save commercial configuration groups into the plugin's ConfigMap "
                        + "(read-modify-write merge: other groups and unknown fields are preserved).")
                    .tag(apiTag);
            })
            .GET("/pushDiagnose", this::pushDiagnose, builder -> {
                builder.operationId("ConsoleDiagnosePush")
                    .description("Step-by-step push self-check: config, reading subscriptions, "
                        + "writing a subscription (create+delete probe), reading the scheduler "
                        + "state and fetching a WeChat access token. Each step reports the raw "
                        + "error message when it fails.")
                    .tag(apiTag);
            })
            .GET("/pushStats", this::pushStats, builder -> {
                builder.operationId("ConsoleGetPushStats")
                    .description("Subscription-message dashboard: today's sent/failed counts, "
                        + "failure reasons grouped by WeChat error code (with what to do about "
                        + "each), and today's records.")
                    .tag(apiTag);
            })
            .GET("/aiContentUsage", this::aiContentUsage, builder -> {
                builder.operationId("ConsoleGetAiContentUsage")
                    .description("AI content (paid) usage: per-user remaining generations, "
                        + "expiry, today's and total generations, plus the generation log "
                        + "(what was generated, which trigger, how many credits were used, "
                        + "and the failure reason when it failed).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("keyword")
                        .in(ParameterIn.QUERY)
                        .description("Filter users by nickname or openid")
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
                        .description("Page size, default 20")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/aiContentLogs", this::aiContentLogs, builder -> {
                builder.operationId("ConsoleListAiContentLogs")
                    .description("AI content generation log, newest first (filter by openid).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("openid")
                        .in(ParameterIn.QUERY)
                        .description("Only this user's records")
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
                        .description("Page size, default 20")
                        .required(false)
                        .implementation(Integer.class));
            })
            .GET("/payStats", this::payStats, builder -> {
                builder.operationId("ConsoleGetPayStats")
                    .description("Payment dashboard: order counts by status, GMV of delivered "
                        + "orders, paying users, ticket issue/consume ratio, 14-day trend and "
                        + "per-goods ranking.")
                    .tag(apiTag);
            })
            .GET("/payOrders", this::listPayOrders, builder -> {
                builder.operationId("ConsoleListPayOrders")
                    .description("List virtual payment orders (default: orders needing manual refund).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("status")
                        .in(ParameterIn.QUERY)
                        .description("Status: needs_refund (default), pending, delivered or closed")
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
            .build();
    }

    Mono<ServerResponse> getCommercialConfig(ServerRequest request) {
        return commercialConfigService.load()
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console getCommercialConfig failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "读取商业化配置失败"));
            });
    }

    Mono<ServerResponse> saveCommercialConfig(ServerRequest request) {
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, CommercialConfigSaveRequest.class))
            .flatMap(body -> commercialConfigService.save(body))
            .flatMap(response -> jsonOk(response))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console saveCommercialConfig failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "保存商业化配置失败"));
            });
    }

    /** 推送看板：今日发送/失败 + 按错误码分组的失败原因（含「该做什么」） */
    /** 推送自检：一步一问 + 最近失败样本（失败时最有信息量） */
    Mono<ServerResponse> pushDiagnose(ServerRequest request) {
        return pushDiagnoseService.diagnose()
            .flatMap(items -> pushDiagnoseService.recentFailures().map(failures -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("items", items);
                payload.put("failed", items.stream().filter(item -> !item.isOk()).count());
                payload.put("recentFailures", failures.stream().map(record -> {
                    var map = new java.util.LinkedHashMap<String, Object>();
                    map.put("kind", record.getSourceKind());
                    map.put("status", record.getStatus());
                    map.put("errorCode", record.getErrorCode());
                    map.put("message", record.getMessage());
                    return map;
                }).toList());
                return (Object) payload;
            }))
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console pushDiagnose failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "推送自检失败"));
            });
    }

    Mono<ServerResponse> pushStats(ServerRequest request) {
        return pushStatsService.summary()
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console pushStats failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "推送统计查询失败"));
            });
    }

    /** AI 定制内容用量：用户额度 + 生成明细（两块一起给，页面一次就能画出来） */
    Mono<ServerResponse> aiContentUsage(ServerRequest request) {
        var page = Math.max(0, parseIntParam(request, "page", 0));
        var size = Math.min(100, Math.max(1, parseIntParam(request, "size", 20)));
        var keyword = request.queryParam("keyword").orElse(null);
        return Mono.zip(
                aiContentUsageService.usage(page, size, keyword),
                aiContentUsageService.logs(0, 20, null))
            .map(tuple -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("users", tuple.getT1());
                payload.put("logs", tuple.getT2());
                return (Object) payload;
            })
            .flatMap(payload -> jsonOk(payload))
            .onErrorResume(e -> {
                log.warn("Console aiContentUsage failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "AI 定制内容用量查询失败"));
            });
    }

    /** 生成明细（可按 openid 筛） */
    Mono<ServerResponse> aiContentLogs(ServerRequest request) {
        var page = Math.max(0, parseIntParam(request, "page", 0));
        var size = Math.min(100, Math.max(1, parseIntParam(request, "size", 20)));
        var openid = request.queryParam("openid").orElse(null);
        return aiContentUsageService.logs(page, size, openid)
            .flatMap(payload -> jsonOk(payload))
            .onErrorResume(e -> {
                log.warn("Console aiContentLogs failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "AI 生成明细查询失败"));
            });
    }

    Mono<ServerResponse> payStats(ServerRequest request) {
        return payStatsService.summary()
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console payStats failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "支付统计查询失败"));
            });
    }

    Mono<ServerResponse> listPayOrders(ServerRequest request) {
        var status = request.queryParam("status").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 20);
        return virtualPayService.listOrdersForAdmin(status, page, size)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(Exception.class, e -> {
                log.warn("Console listPayOrders failed: {}", e.getMessage(), e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("error", "查询订单列表失败"));
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
