package pro.uhalo.uni.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.services.impl.DigestPublicService;
import pro.uhalo.uni.services.impl.DigestSubscriptionService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * 小程序端的 AI 日报接口（公开只读）。
 *
 * <p>日报此前只有后台接口，小程序用户完全看不到，每天生成的内容等于白做。
 * 这里把已发布的日报开放给小程序，是「日报 → 用户 → 订阅 → 分享」这条链路的入口。</p>
 *
 * <p>安全边界：只读、只返回已发布的日报；不返回正文（正文走文章详情，复用阅读量/评论/付费判定），
 * 因此这个匿名接口不会泄露未发布内容。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestPublicEndpoint implements CustomEndpoint {

    private final DigestPublicService digestPublicService;
    private final DigestSubscriptionService digestSubscriptionService;
    private final pro.uhalo.uni.services.DigestSubscriptionStatsService subscriptionStatsService;
    private final pro.uhalo.uni.services.impl.HomeFeedService homeFeedService;
    private final pro.uhalo.uni.services.impl.SubscriptionQuotaService quotaService;
    private final pro.uhalo.uni.services.PointsService pointsService;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Digest";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/list", this::list,
                builder -> builder
                    .operationId("GetPublicDigestList")
                    .description("Public AI digest list for the mini program: published reports "
                        + "in reverse date order, each with its articles (title, cover, AI "
                        + "category, keywords, summary, source) and the post to open. "
                        + "Never returns drafts or article bodies.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, starts from 0")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size, max 30")
                        .required(false)
                        .implementation(Integer.class))
                    .response(responseBuilder().implementation(
                        pro.uhalo.uni.dto.DigestPublicListResponse.class)))
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/subscription",
                this::getSubscription,
                builder -> builder
                    .operationId("GetDigestSubscription")
                    .description("Current user's AI digest subscription preferences, together with "
                        + "the selectable filter options (only values that actually appeared in "
                        + "recent published digests).")
                    .tag(apiTag))
            .POST(Constants.END_POINT_API_BASE_PATH + "/digest/subscription",
                this::saveSubscription,
                builder -> builder
                    .operationId("SaveDigestSubscription")
                    .description("Save AI digest subscription preferences. Anonymous-writable, "
                        + "therefore item counts and lengths are hard-limited server-side.")
                    .tag(apiTag)
                    .requestBody(org.springdoc.core.fn.builders.requestbody.Builder
                        .requestBodyBuilder()
                        .implementation(pro.uhalo.uni.dto.DigestSubscriptionRequest.class)))
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/feed", this::homeFeed,
                builder -> builder
                    .operationId("GetDigestHomeFeed")
                    .description("Home content feed: the union of the user's effective "
                        + "categories (own subscriptions within quota + at most 3 inherited) "
                        + "merged into ONE request. When the user has no subscription it falls "
                        + "back to the hottest categories (scope=hot). Categories are resolved "
                        + "server-side - the client cannot pass its own list.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("scope")
                        .in(ParameterIn.QUERY)
                        .description("mine (default) or hot")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, starts from 1")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size, max 30")
                        .required(false)
                        .implementation(Integer.class))
                    .response(responseBuilder().implementation(
                        pro.uhalo.uni.dto.HomeFeedDTO.class)))
            .POST(Constants.END_POINT_API_BASE_PATH + "/digest/subscription/quota",
                this::purchaseQuota,
                builder -> builder
                    .operationId("PurchaseDigestSubscriptionQuota")
                    .description("Unlock one more subscription slot with points. The quota is "
                        + "permanent capacity (cancel a subscription frees the slot but keeps the "
                        + "quota). Idempotent by quota index: a double submit cannot charge twice.")
                    .tag(apiTag))
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/shared", this::shared,
                builder -> builder
                    .operationId("GetSharedDigest")
                    .description("Landing data for a shared personal digest: resolve the sharer by "
                        + "invite code and return their picked articles plus a coarse category "
                        + "summary. Deliberately never returns the sharer's raw keywords/sources "
                        + "- the match reason is rewritten to category level for the same reason.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("code")
                        .in(ParameterIn.QUERY)
                        .description("Sharer's invite code")
                        .required(false)
                        .implementation(String.class)))
            .POST(Constants.END_POINT_API_BASE_PATH + "/digest/shared/subscribe",
                this::subscribeFromShare,
                builder -> builder
                    .operationId("SubscribeFromSharedDigest")
                    .description("Follow the same direction as the shared digest: copies the "
                        + "sharer's categories and keywords (never sources) into the viewer's "
                        + "subscription. Requires login; invite binding is handled by the "
                        + "existing login flow.")
                    .tag(apiTag))
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/categories/ranking",
                this::categoryRanking,
                builder -> builder
                    .operationId("GetDigestCategoryRanking")
                    .description("Subscription popularity of site categories: how many users "
                        + "actively subscribe each one (inherited categories are excluded, as are "
                        + "records with the subscription turned off). Powers the \"discover\" page "
                        + "ranking. Aggregated over all subscription records and cached for a few "
                        + "minutes, so it is not real-time.")
                    .tag(apiTag)
                    .response(responseBuilder().implementation(
                        pro.uhalo.uni.dto.DigestCategoryRankingDTO.class)))
            .GET(Constants.END_POINT_API_BASE_PATH + "/digest/my", this::myFeed,
                builder -> builder
                    .operationId("GetMyDigestFeed")
                    .description("Personalised AI digest feed: articles from published digests "
                        + "within the recent window that match the user's preferences. "
                        + "No preferences means the full feed (never empty).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, starts from 0")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size, max 30")
                        .required(false)
                        .implementation(Integer.class)))
            .build();
    }

    /** 读取订阅偏好；顺带下发可选项，前端不必再猜能选什么。 */
    private Mono<ServerResponse> getSubscription(ServerRequest request) {
        var openid = resolveOpenid(request);
        return Mono.zip(
                digestSubscriptionService.load(openid),
                digestPublicService.filterOptions())
            .flatMap(tuple -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("subscription", tuple.getT1());
                payload.put("options", tuple.getT2());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload);
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取订阅失败 openid={}：{}",
                    maskOpenid(openid), ex.toString());
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "订阅设置加载失败，请稍后重试"));
            });
    }

    private Mono<ServerResponse> saveSubscription(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!org.springframework.util.StringUtils.hasText(openid)) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "请先登录"));
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw,
                pro.uhalo.uni.dto.DigestSubscriptionRequest.class))
            .flatMap(body -> digestSubscriptionService.save(openid, body))
            .flatMap(subscription -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(subscription))
            // 校验类错误原样返回原因：用户需要知道是「选太多了」还是「某项过长」
            .onErrorResume(pro.uhalo.uni.exception.WxAuthException.class, ex ->
                ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", ex.getReason())))
            .onErrorResume(ex -> {
                // 以前这里既不打日志、又只回一句「保存失败」——线上出问题根本无从查起。
                // 异常必须留下痕迹。
                log.error("[UniHalo][Digest] 保存订阅失败 openid={}：{}",
                    maskOpenid(openid), ex.toString(), ex);
                if (DigestSubscriptionService.isIndexBuildingError(ex)) {
                    // 这是 Halo 的索引状态问题，不是订阅数据的问题：说清楚该重启服务，
                    // 用户才不会反复点、也不会以为是自己操作错了。
                    return ServerResponse.status(503)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", "Halo 正在为该类数据重建索引，暂时写不进订阅。"
                            + "稍等一会儿重试即可；若一直失败，重启一次 Halo 服务就能恢复"
                            + "（这是服务端的索引状态问题，与你的订阅内容无关）"));
                }
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "订阅设置保存失败，请稍后重试"));
            });
    }

    /** openid 是用户标识，日志里只留前几位：够定位是谁，又不至于整串落盘 */
    private static String maskOpenid(String openid) {
        if (!org.springframework.util.StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***"
            : trimmed.substring(0, 6) + "***";
    }

    private Mono<ServerResponse> shared(ServerRequest request) {
        var code = request.queryParam("code").orElse(null);
        return digestPublicService.shared(code)
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 分享落地页加载失败：{}", ex.toString());
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "分享内容加载失败，请稍后重试"));
            });
    }

    /**
     * 首页内容流（并集）。
     *
     * <p>分类由服务端解析，客户端不能传——否则这个接口就成了「任意分类的通用查询」，
     * 而它的取数是按分类逐个打 Halo 的，必须把可控的分类数量捏在服务端手里。</p>
     */
    private Mono<ServerResponse> homeFeed(ServerRequest request) {
        var openid = resolveOpenid(request);
        var page = intParam(request, "page", 1);
        var size = intParam(request, "size", 10);
        var forceHot = "hot".equalsIgnoreCase(
            request.queryParam("scope").orElse("").trim());
        return quotaService.loadConfig().flatMap(config ->
            digestSubscriptionService.load(openid).flatMap(subscription -> {
                var effective = subscription.getEffectiveCategories() == null
                    ? java.util.List.<String>of() : subscription.getEffectiveCategories();
                var paused = subscription.getLimits() == null
                    ? java.util.List.<String>of()
                    : subscription.getLimits().getPausedCategories();
                var ownCount = subscription.getPostCategories() == null
                    ? 0 : subscription.getPostCategories().size();
                var inheritedCount = subscription.getInheritedCategories() == null
                    ? 0 : subscription.getInheritedCategories().size();
                var pausedCount = paused == null ? 0 : paused.size();
                if (!forceHot && !effective.isEmpty()) {
                    return homeFeedService.feed(effective, config, page, size)
                        .map(feed -> {
                            feed.setScope("mine");
                            feed.setPausedCategories(paused == null ? java.util.List.of() : paused);
                            // 降级与提示统一由 HomeFeedHints 决定：口径只有一处，
                            // 页面文案与测试不会再各写一套
                            var decision = pro.uhalo.uni.utils.HomeFeedHints.decide("mine",
                                ownCount, inheritedCount, pausedCount, feed.getItems().isEmpty());
                            feed.setFallback(decision.fallback());
                            feed.setHint(decision.hint());
                            return feed;
                        });
                }
                // 零订阅 / 游客 / 显式要人气：用热度最高的几个分类当内容源
                return homeFeedService.hotCategories(config).flatMap(hot -> {
                    if (hot.isEmpty()) {
                        // 连人气分类都没有：把「为什么没内容」也交给同一个口径去说
                        var decision = pro.uhalo.uni.utils.HomeFeedHints.decide("hot",
                            ownCount, inheritedCount, 0, true);
                        return Mono.just(pro.uhalo.uni.dto.HomeFeedDTO.builder()
                            .items(java.util.List.of())
                            .hasMore(false)
                            .scope("hot")
                            .fallback(decision.fallback())
                            .categories(java.util.List.of())
                            .pausedCategories(java.util.List.of())
                            .hint(decision.hint())
                            .page(Math.max(1, page))
                            .size(size)
                            .build());
                    }
                    return homeFeedService.feed(hot, config, page, size).map(feed -> {
                        feed.setScope("hot");
                        feed.setPausedCategories(java.util.List.of());
                        var decision = pro.uhalo.uni.utils.HomeFeedHints.decide(
                            forceHot ? "hot-forced" : "hot", ownCount, inheritedCount, 0,
                            feed.getItems().isEmpty());
                        feed.setFallback(decision.fallback());
                        feed.setHint(decision.hint());
                        return feed;
                    });
                });
            }))
            .flatMap(feed -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(feed))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 首页内容流加载失败 openid={}：{}",
                    maskOpenid(openid), ex.toString());
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "首页内容加载失败，请稍后重试"));
            });
    }

    /** 用积分解锁一份订阅额度 */
    private Mono<ServerResponse> purchaseQuota(ServerRequest request) {
        var openid = resolveOpenid(request);
        return request.bodyToMono(pro.uhalo.uni.dto.SubscribeQuotaRequest.class)
            .defaultIfEmpty(new pro.uhalo.uni.dto.SubscribeQuotaRequest())
            .flatMap(body -> pointsService.purchaseSubscribeQuota(openid, body.getCount()))
            .flatMap(result -> quotaService.limitsOf(openid, java.util.List.of())
                .map(limits -> {
                    var payload = new java.util.LinkedHashMap<String, Object>();
                    payload.put("balance", result.getBalance());
                    payload.put("subscribeQuota", result.getSubscribeQuota());
                    payload.put("message", result.getMessage());
                    payload.put("limits", limits);
                    return payload;
                }))
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload))
            .onErrorResume(pro.uhalo.uni.exception.WxAuthException.class, ex ->
                ServerResponse.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", ex.getReason())))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Subscription] 解锁订阅额度失败 openid={}：{}",
                    maskOpenid(openid), ex.toString());
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "解锁失败，请稍后重试"));
            });
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        return request.queryParam(name)
            .map(value -> {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    return fallback;
                }
            })
            .orElse(fallback);
    }

    private Mono<ServerResponse> subscribeFromShare(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!org.springframework.util.StringUtils.hasText(openid)) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "请先登录"));
        }
        var code = request.queryParam("code").orElse(null);
        return digestPublicService.subscribeFromShare(openid, code)
            .flatMap(subscription -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(subscription))
            .onErrorResume(pro.uhalo.uni.exception.WxAuthException.class, ex ->
                ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", ex.getReason())))
            .onErrorResume(ex -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "订阅失败，请稍后重试")));
    }

    private Mono<ServerResponse> myFeed(ServerRequest request) {
        var openid = resolveOpenid(request);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 10);
        return digestPublicService.myFeed(openid, page, size)
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(ex -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "订阅内容加载失败，请稍后重试")));
    }

    /**
     * 分类订阅热度榜（匿名可读）。
     *
     * <p>只涉及「每个分类有多少人订阅」这种聚合数字，不返回任何用户标识，
     * 因此可以公开；聚合失败时 service 内部会回落（旧缓存或空榜），
     * 这里再兜一层，最多让「发现」页少一个区块，不会整页报错。</p>
     */
    private Mono<ServerResponse> categoryRanking(ServerRequest request) {
        return subscriptionStatsService.categoryRanking()
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 分类热度榜加载失败：{}", ex.toString());
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "分类热度加载失败，请稍后重试"));
            });
    }

    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return org.springframework.util.StringUtils.hasText(openid) ? openid.trim() : null;
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 10);
        return digestPublicService.list(page, size)
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(ex -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "日报加载失败，请稍后重试")));
    }

    private static int parseIntParam(ServerRequest request, String name, int fallback) {
        return request.queryParam(name)
            .map(value -> {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    return fallback;
                }
            })
            .orElse(fallback);
    }
}
