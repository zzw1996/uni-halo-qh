package pro.uhalo.uni.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.exception.WxForbiddenException;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxCommentProxyService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

@Component
@RequiredArgsConstructor
@Slf4j
public class WxCommentProxyFilter implements AdditionalWebFilter {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final byte[] EMPTY_BODY = new byte[0];

    private final ServerWebExchangeMatcher pathMatcher = createPathMatcher();
    private final WxUserService wxUserService;
    private final WxCommentProxyService wxCommentProxyService;

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return pathMatcher.matches(exchange)
            .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
            .flatMap(match -> proxyIfWechatSession(exchange))
            .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> proxyIfWechatSession(ServerWebExchange exchange) {
        var openid = resolveOpenid(exchange);
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }

        var prepare = exchange.getRequest().getMethod() == HttpMethod.DELETE
            ? Mono.just(EMPTY_BODY)
            : readBody(exchange);

        return prepare
            .flatMap(bodyBytes -> wxUserService.fetchByOpenid(openid)
                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                .flatMap(wxUser -> dispatch(exchange, bodyBytes, wxUser, openid)))
            .timeout(REQUEST_TIMEOUT)
            .onErrorResume(TimeoutException.class,
                ex -> writeError(exchange, HttpStatus.GATEWAY_TIMEOUT, "评论操作超时，请稍后重试"))
            .onErrorResume(WxForbiddenException.class,
                ex -> writeError(exchange, HttpStatus.FORBIDDEN, ex.getReason()))
            .onErrorResume(WxAuthException.class,
                ex -> writeError(exchange, HttpStatus.BAD_REQUEST, ex.getReason()))
            .onErrorResume(Exception.class, ex -> {
                log.warn("wx comment proxy failed: {}", ex.getMessage());
                return writeError(exchange, HttpStatus.BAD_REQUEST, "评论操作失败，请稍后重试");
            });
    }

    private Mono<Void> dispatch(ServerWebExchange exchange, byte[] bodyBytes,
                                WxUser wxUser, String openid) {
        var path = exchange.getRequest().getPath().value();
        if (exchange.getRequest().getMethod() == HttpMethod.DELETE) {
            var commentName = extractSingleCommentName(path);
            return wxCommentProxyService.deleteComment(commentName, openid)
                .flatMap(result -> writeJson(exchange, HttpStatus.OK, result));
        }
        if (path.endsWith("/reply")) {
            var commentName = extractCommentName(path);
            return wxCommentProxyService.createReply(commentName, bodyBytes, wxUser, openid, exchange)
                .flatMap(reply -> writeJson(exchange, HttpStatus.CREATED, reply));
        }
        return wxCommentProxyService.createComment(bodyBytes, wxUser, openid, exchange)
            .flatMap(comment -> writeJson(exchange, HttpStatus.CREATED, comment));
    }

    private Mono<byte[]> readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .map(dataBuffer -> {
                var bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return bytes;
            })
            .defaultIfEmpty(EMPTY_BODY);
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, Object body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            var payload = OBJECT_MAPPER.writeValueAsBytes(body);
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception ex) {
            return writeError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "评论响应序列化失败");
        }
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            var payload = OBJECT_MAPPER.writeValueAsBytes(Map.of("error", message));
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception ex) {
            var payload = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        }
    }

    private static String extractCommentName(String path) {
        var prefix = Constants.HALO_COMMENT_API_PREFIX + "/";
        var suffix = "/reply";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        return path.substring(prefix.length(), path.length() - suffix.length());
    }

    private static String extractSingleCommentName(String path) {
        var prefix = Constants.HALO_COMMENT_API_PREFIX + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        var name = path.substring(prefix.length());
        if (!StringUtils.hasText(name) || name.contains("/")) {
            return null;
        }
        return name;
    }

    private static OrServerWebExchangeMatcher createPathMatcher() {
        var commentMatcher = pathMatchers(HttpMethod.POST, Constants.HALO_COMMENT_API_PREFIX);
        var replyMatcher = pathMatchers(HttpMethod.POST,
            Constants.HALO_COMMENT_API_PREFIX + "/{name}/reply");
        var deleteMatcher = pathMatchers(HttpMethod.DELETE,
            Constants.HALO_COMMENT_API_PREFIX + "/{name}");
        return new OrServerWebExchangeMatcher(commentMatcher, replyMatcher, deleteMatcher);
    }

    private static String resolveOpenid(ServerWebExchange exchange) {
        var openid = exchange.getRequest().getHeaders().getFirst(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() - 1;
    }
}
