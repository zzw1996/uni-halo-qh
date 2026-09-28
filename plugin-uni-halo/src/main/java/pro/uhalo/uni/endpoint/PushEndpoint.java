package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.impl.PushSubscriptionService;
import pro.uhalo.uni.utils.PushConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序端的推送设置接口（匿名可读、可写，但只写自己的额度）。
 *
 * <p>这组接口是「一次性订阅」机制的必要配套：微信侧不提供额度查询，用户每点一次授权
 * 只能在<b>前端</b>拿到结果——所以必须有地方把「我拿到了几条」「我想不想收」记下来。</p>
 *
 * <p>写入做了限流：额度接口被刷不会造成实际影响（每人每天每类最多推一条），
 * 但没必要让匿名接口变成高频写入通道。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushEndpoint implements CustomEndpoint {

    /** 额度上报的频率上限：正常用户一分钟点不了几次授权 */
    private static final int QUOTA_RATE_LIMIT = 30;
    private static final Duration QUOTA_RATE_WINDOW = Duration.ofHours(1);

    private final PushSubscriptionService subscriptionService;
    private final ReactiveSettingFetcher settingFetcher;
    private final RateLimiter rateLimiter;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Push";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/push/status", this::status,
                builder -> builder
                    .operationId("GetPushStatus")
                    .description("Subscription-message status for the current user: remaining quotas "
                        + "per template, whether each line is enabled, and whether the template is "
                        + "configured. Never exposes other users' data.")
                    .tag(apiTag))
            .POST(Constants.END_POINT_API_BASE_PATH + "/push/quota", this::grantQuota,
                builder -> builder
                    .operationId("GrantPushQuota")
                    .description("Report one successful wx.requestSubscribeMessage authorization: "
                        + "+1 quota for each accepted template. One authorization equals exactly "
                        + "one allowed send (WeChat one-time subscription).")
                    .tag(apiTag))
            .POST(Constants.END_POINT_API_BASE_PATH + "/push/setting", this::updateSetting,
                builder -> builder
                    .operationId("UpdatePushSetting")
                    .description("Turn all subscription-message pushes on or off for this user. "
                        + "Quota is kept, so turning it back on resumes immediately.")
                    .tag(apiTag))
            .build();
    }

    private Mono<ServerResponse> status(ServerRequest request) {
        var openid = resolveOpenid(request);
        return loadConfig().flatMap(config -> subscriptionService.load(openid).map(subscription -> {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("enabled", config.isEnabled());
            payload.put("userEnabled", !Boolean.FALSE.equals(subscription.getEnabled()));
            payload.put("templateConfigured", config.hasNewPostTemplate());
            payload.put("miniprogramState", config.getMiniprogramState());
            payload.put("newPostEnabled", PushConfigHelper.kindEnabled(config,
                PushConfigHelper.KIND_NEW_POST));
            payload.put("aiDigestEnabled", PushConfigHelper.kindEnabled(config,
                PushConfigHelper.KIND_AI_DIGEST));
            payload.put("newPostQuota", orZero(subscription.getNewPostQuota()));
            payload.put("aiDigestQuota", orZero(subscription.getAiDigestQuota()));
            payload.put("lastPushAt", PushSubscriptionService.formatTime(
                subscription.getLastPushAt()));
            payload.put("message", describe(subscription));
            return payload;
        })).flatMap(this::jsonOk)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Push] 读取推送状态失败 openid={}：{}", mask(openid),
                    ex.toString());
                if (PushSubscriptionService.isIndexBuildingError(ex)) {
                    return indexBuilding();
                }
                return error(500, "推送设置加载失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> grantQuota(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return error(400, "请先登录");
        }
        if (!rateLimiter.tryAcquire("wx:push:quota:" + openid, QUOTA_RATE_LIMIT,
            QUOTA_RATE_WINDOW)) {
            return error(429, "操作太频繁，请稍后再试");
        }
        return loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return error(400, "站长暂未开启消息推送");
            }
            return WxRequestParser.readRawBody(request)
                .flatMap(raw -> WxRequestParser.parseBody(raw, QuotaRequest.class))
                .defaultIfEmpty(new QuotaRequest())
                .flatMap(body -> {
                    var kinds = normalizeKinds(body.getKinds());
                    if (kinds.isEmpty()) {
                        return error(400, "未指定要记账的消息类型");
                    }
                    return subscriptionService.grant(openid, kinds)
                        .map(saved -> {
                            var payload = new LinkedHashMap<String, Object>();
                            payload.put("newPostQuota", orZero(saved.getNewPostQuota()));
                            payload.put("aiDigestQuota", orZero(saved.getAiDigestQuota()));
                            payload.put("message", "已记录，收到更新时会通知你");
                            return (Object) payload;
                        });
                })
                .flatMap(this::jsonOk)
                .onErrorResume(ex -> {
                    log.error("[UniHalo][Push] 记录额度失败 openid={}：{}", mask(openid),
                        ex.toString(), ex);
                    return writeFailure(ex, "记录失败");
                });
        });
    }

    private Mono<ServerResponse> updateSetting(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return error(400, "请先登录");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, SettingRequest.class))
            .defaultIfEmpty(new SettingRequest())
            .flatMap(body -> subscriptionService.setEnabled(openid,
                    body.getEnabled() == null || Boolean.TRUE.equals(body.getEnabled()))
                .map(saved -> {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("userEnabled", !Boolean.FALSE.equals(saved.getEnabled()));
                    payload.put("message", Boolean.FALSE.equals(saved.getEnabled())
                        ? "已关闭推送，额度会保留" : "已开启推送");
                    return (Object) payload;
                }))
            .flatMap(this::jsonOk)
            .onErrorResume(WxAuthException.class, ex -> error(400, ex.getReason()))
            .onErrorResume(ex -> {
                log.error("[UniHalo][Push] 保存开关失败 openid={}：{}", mask(openid), ex.toString(),
                    ex);
                return writeFailure(ex, "保存失败");
            });
    }

    /** 只接受已知类型，最多 3 个（与 wx.requestSubscribeMessage 一次最多 3 个模板一致） */
    private static List<String> normalizeKinds(List<String> kinds) {
        if (kinds == null) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var kind : kinds) {
            var normalized = PushConfigHelper.normalizeKind(kind);
            if (!normalized.isEmpty() && !result.contains(normalized)) {
                result.add(normalized);
            }
            if (result.size() >= 3) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String describe(PushSubscription subscription) {
        var quota = PushSubscriptionService.describeQuota(subscription);
        return "剩余提醒额度：" + quota + "（一次授权对应一条消息）";
    }

    private Mono<PushConfigDTO> loadConfig() {
        return settingFetcher.get(PushConfigHelper.groupName())
            .map(PushConfigHelper::parse)
            .defaultIfEmpty(PushConfigHelper.defaults());
    }

    /**
     * 写入失败的统一回应。
     *
     * <p>这里刻意把原因带上：推送的失败几乎都是「服务端环境问题」而不是用户操作问题，
     * 只回一句「请稍后重试」会让用户和站长都无从下手。日志里仍然是完整堆栈。</p>
     */
    private static Mono<ServerResponse> writeFailure(Throwable ex, String prefix) {
        if (PushSubscriptionService.isIndexBuildingError(ex)) {
            return indexBuilding();
        }
        var reason = ex.getMessage() == null ? "" : ex.getMessage();
        if (reason.length() > 120) {
            reason = reason.substring(0, 120);
        }
        return error(500, reason.isEmpty() ? prefix + "，请稍后重试" : prefix + "：" + reason);
    }

    /** Halo 正在建索引：这是可操作的（等一会儿，或重启一次 Halo） */
    private static Mono<ServerResponse> indexBuilding() {
        return error(503, "服务端正在为推送数据建索引，请等一两分钟后重试；"
            + "若一直失败，重启一次 Halo 服务即可恢复（与你的操作无关）");
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> error(int status, String message) {
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", message));
    }

    /** openid 只从会话头取：绝不能从参数里拿，否则可以给别人刷额度 */
    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }

    /** 额度上报请求体 */
    public static class QuotaRequest {

        private List<String> kinds;

        public List<String> getKinds() {
            return kinds;
        }

        public void setKinds(List<String> kinds) {
            this.kinds = kinds;
        }
    }

    /** 开关请求体 */
    public static class SettingRequest {

        private Boolean enabled;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
