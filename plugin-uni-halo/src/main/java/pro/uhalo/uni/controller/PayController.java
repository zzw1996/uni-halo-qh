package pro.uhalo.uni.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.CreatePayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.VirtualPayService;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayController {

    private static final int NOTIFY_RATE_LIMIT_PER_MINUTE = 30;

    private final VirtualPayService virtualPayService;
    private final RateLimiter rateLimiter;

    public Mono<ServerResponse> publicConfig(ServerRequest request) {
        return virtualPayService.getPublicConfig()
            .flatMap(this::jsonOk)
            .onErrorResume(this::mapError);
    }

    public Mono<ServerResponse> createOrder(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, CreatePayOrderRequest.class))
            .flatMap(body -> virtualPayService.createOrder(openid, body))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> queryOrder(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, QueryPayOrderRequest.class))
            .flatMap(body -> virtualPayService.queryOrder(openid, body))
            .flatMap(this::jsonOk));
    }

    /**
     * 我的订单列表（仅当前 openid 自己的订单）。
     *
     * <p>openid 一律取自请求头，不接受 query 参数传入，避免越权查看他人订单。</p>
     */
    public Mono<ServerResponse> myOrders(ServerRequest request) {
        var page = parseInt(request.queryParam("page").orElse(null), 0);
        var size = parseInt(request.queryParam("size").orElse(null), 20);
        return withOpenid(request, openid -> virtualPayService.listMyOrders(openid, page, size)
            .flatMap(this::jsonOk));
    }

    /**
     * 微信虚拟支付发货推送入口：输入输出均为 XML，返回 ErrCode=0 表示成功（否则微信最多重试 15 次）。
     */
    public Mono<ServerResponse> deliverNotify(ServerRequest request) {
        // 防推送滥用：按来源 IP 限流（微信推送间隔 ≥2 秒，30 次/分钟远超正常频率）
        var clientIp = resolveClientIp(request);
        if (!rateLimiter.tryAcquire("notify:" + clientIp, NOTIFY_RATE_LIMIT_PER_MINUTE,
            Duration.ofMinutes(1))) {
            log.warn("虚拟支付发货推送触发限流，remote={}，forwarded={}", clientIp,
                resolveForwardedFor(request));
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue("<xml><ErrCode>1</ErrCode><ErrMsg><![CDATA[rate limited]]></ErrMsg></xml>");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(virtualPayService::handleDeliverNotify)
            .flatMap(xml -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(xml))
            .onErrorResume(ex -> {
                log.warn("虚拟支付发货推送处理异常", ex);
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .bodyValue("<xml><ErrCode>1</ErrCode><ErrMsg><![CDATA[internal error]]></ErrMsg></xml>");
            });
    }

    /**
     * 解析用于限流的来源标识：只使用 TCP 连接的对端地址（不可伪造）。
     *
     * <p>不能把 X-Forwarded-For 作为限流键：该请求头由客户端完全控制，
     * 攻击者每次换一个值即可让每个请求落入不同限流桶，从而绕过限流并放大
     * 对微信 query_order 的调用（可能触发 268490015 频率限制）与数据库压力。
     * 该头仅用于日志排查，不参与安全决策。</p>
     *
     * <p>注意：若站点部署在反向代理之后，对端地址会是代理地址，
     * 此时限流退化为全局共享一个桶。微信推送间隔 ≥2 秒且单笔最多重试 15 次，
     * 30 次/分钟对个人主体小程序（月限额 10 万元）的正常交易量足够。</p>
     */
    private static String resolveClientIp(ServerRequest request) {
        return request.remoteAddress()
            .map(InetSocketAddress::getAddress)
            .map(InetAddress::getHostAddress)
            .orElse("unknown");
    }

    /** 仅用于日志诊断的转发头，不参与限流等安全决策。 */
    private static String resolveForwardedFor(ServerRequest request) {
        var forwarded = request.headers().firstHeader("X-Forwarded-For");
        if (!StringUtils.hasText(forwarded)) {
            return "-";
        }
        var first = forwarded.split(",")[0].trim();
        return StringUtils.hasText(first) ? first : "-";
    }

    private Mono<ServerResponse> withOpenid(ServerRequest request,
        Function<String, Mono<ServerResponse>> handler) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return handler.apply(openid)
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("virtual payment endpoint failed: {}", e.getMessage(), e);
                return badRequest("支付操作失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private Mono<ServerResponse> mapError(Throwable ex) {
        if (ex instanceof WxAuthException wxAuthException) {
            return badRequest(wxAuthException.getReason());
        }
        log.warn("virtual payment endpoint failed: {}", ex.getMessage(), ex);
        return badRequest("支付操作失败，请稍后重试");
    }

    private static Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", message));
    }

    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    /** 解析整数 query 参数：缺失或非法时回落默认值，不报错。 */
    private static int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
