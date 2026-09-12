package pro.uhalo.uni.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

/**
 * 装扮全站可见：评论列表头像框/昵称框注入。
 *
 * <p>拦截 Halo 原生评论列表 GET（/apis/api.halo.run/v1alpha1/comments），
 * 读取响应 JSON，按每条评论的 unihalo.openid annotation 批量反查 UserPoints，
 * 向其顶层注入 avatarFrame / nickFrame 字段后重写响应。
 *
 * <p>安全性：纯注入、不删除任何原有字段；任何解析/查询异常均 fail-open
 * （放行原始响应），绝不影响评论列表本身的可读性。查询超时 2s。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutfitAvatarFrameFilter implements AdditionalWebFilter {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ServerWebExchangeMatcher pathMatcher = createMatcher();
    private final PointsPersistence pointsPersistence;

    private static ServerWebExchangeMatcher createMatcher() {
        // 评论列表（含瞬间评论）：GET /apis/api.halo.run/v1alpha1/comments
        return pathMatchers(HttpMethod.GET, Constants.HALO_COMMENT_API_PREFIX);
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return pathMatcher.matches(exchange)
            .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
            .flatMap(match -> injectFrames(exchange, chain))
            .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> injectFrames(ServerWebExchange exchange, WebFilterChain chain) {
        // 先让上游写入响应，再读取响应体改写：使用装饰后的 Response 读原始 body。
        // 为降低对既有链路的侵入，采用「先缓存上游 body、随后重写」的两段式。
        var fullResponse = new CachingServerHttpResponse(exchange.getResponse());
        return chain.filter(new org.springframework.web.server.ServerWebExchangeDecorator(exchange) {
            @Override
            public org.springframework.http.server.reactive.ServerHttpResponse getResponse() {
                return fullResponse;
            }
        }).then(Mono.defer(() -> {
            var body = fullResponse.getCachedBody();
            var status = fullResponse.getStatusCode();
            if (body == null || !status.is2xxSuccessful() || body.length == 0) {
                // 无内容或非成功响应不处理。
                return writeBody(exchange, status, body);
            }
            try {
                var root = OBJECT_MAPPER.readTree(body);
                var rewritten = rewrite(root);
                if (rewritten == null) {
                    // 解析不到 items（非法响应）→ 原样放行。
                    return writeBody(exchange, status, body);
                }
                var payload = OBJECT_MAPPER.writeValueAsBytes(rewritten);
                return writeBody(exchange, status, payload);
            } catch (Exception ex) {
                log.warn("评论列表装扮注入解析失败，回退原始响应: {}", ex.getMessage());
                return writeBody(exchange, status, body);
            }
        }));
    }

    /**
     * 改写响应：items[] 中每条评论注入 avatarFrame/nickFrame（若该用户有装扮）。
     * 返回 null 表示响应结构不符合预期（不放行改写）。
     */
    private JsonNode rewrite(JsonNode root) {
        if (!root.isObject()) {
            return null;
        }
        var items = root.get("items");
        if (items != null && items.isArray()) {
            var openids = new ArrayList<String>();
            var nodes = new ArrayList<ObjectNode>();
            for (var item : items) {
                if (!(item instanceof ObjectNode obj)) {
                    continue;
                }
                var openid = extractOpenid(obj);
                if (openid != null && !openid.isEmpty()) {
                    openids.add(openid);
                }
                nodes.add(obj);
            }
            if (openids.isEmpty()) {
                return root;
            }
            var uniqueOpenids = new HashSet<>(openids);
            Map<String, UserPoints> accounts = queryAccounts(uniqueOpenids);
            for (var obj : nodes) {
                var openid = extractOpenid(obj);
                if (openid == null || openid.isEmpty()) {
                    continue;
                }
                var account = accounts.get(openid);
                if (account == null) {
                    continue;
                }
                // 仅注入有效帧；空值也写入 null 方便前端统一读取。
                obj.put("avatarFrame", account.getActiveAvatarFrame());
                obj.put("nickFrame", account.getActiveNickFrame());
            }
            return root;
        }
        // 若非 ListResult 结构（含 items 数组），返回 null 放行。
        return null;
    }

    private String extractOpenid(ObjectNode item) {
        var metadata = item.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        var annotations = metadata.get("annotations");
        if (annotations == null || !annotations.isObject()) {
            return null;
        }
        var value = annotations.get(Constants.UNIHALO_OPENID_ANNOTATION);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private Map<String, UserPoints> queryAccounts(Set<String> openids) {
        try {
            return pointsPersistence.findAccountsByOpenidsIn(openids)
                .timeout(QUERY_TIMEOUT)
                .block();
        } catch (Exception ex) {
            log.warn("批量查询装扮用户失败（按空处理）: {}", ex.getMessage());
            return Map.of();
        }
    }

    private Mono<Void> writeBody(ServerWebExchange exchange,
        org.springframework.http.HttpStatusCode status, byte[] body) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (body == null || body.length == 0) {
            return response.setComplete();
        }
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() - 1;
    }

    /**
     * 缓存上游响应体的装饰 Response：先让上游写入内存，供后续改写。
     */

    /**
     * 缓存上游响应体的装饰 Response（基于官方 ServerHttpResponseDecorator）。
     * 先让上游写入内存，供 Filter 改写后重写。
     */
    static class CachingServerHttpResponse
        extends org.springframework.http.server.reactive.ServerHttpResponseDecorator {

        private byte[] cachedBody;

        CachingServerHttpResponse(org.springframework.http.server.reactive.ServerHttpResponse delegate) {
            super(delegate);
        }

        byte[] getCachedBody() {
            return cachedBody != null ? cachedBody : new byte[0];
        }

        @Override
        public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                .flatMap(dataBuffer -> {
                    cachedBody = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(cachedBody);
                    DataBufferUtils.release(dataBuffer);
                    // 上游内容不写入真实响应（由 Filter 在 then 中重写）。
                    return Mono.empty();
                });
        }

        @Override
        public Mono<Void> writeAndFlushWith(
            org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
            return Mono.from(body).flatMap(this::writeWith);
        }
    }
}
