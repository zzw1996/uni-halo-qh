package pro.uhalo.uni.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.SubscriptionLockedDTO;
import pro.uhalo.uni.services.SubscriptionWallService;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 分类订阅墙 Filter。
 *
 * <p>拦截文章详情接口 {@code GET /apis/api.content.halo.run/v1alpha1/posts/{name}}，
 * 若文章所属分类当前用户未订阅，则<b>直接返回 403 + 订阅引导信息</b>，
 * 引导用户先订阅再阅读。列表接口一律放行：用户能浏览、不能读全文，
 * 是刻意的转化漏斗。</p>
 *
 * <p>与 {@link ContentTicketFilter}（付费阅读券墙）互不叠加：订阅墙判定里
 * 付费文章直接放行，付费文章只由阅读券墙管辖，用户不会面对两张墙。</p>
 *
 * <p><b>失败方向（fail-open）</b>：判定任何异常或超时都放行到 Halo 原生链路。
 * 墙的目的是引导订阅转化，不是强安全；误拦全站文章比漏拦一篇文章代价大得多。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionWallFilter implements AdditionalWebFilter {

    /** 判定超时：超时即放行，避免拖慢或阻塞正常阅读 */
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SubscriptionWallService subscriptionWallService;

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        var request = exchange.getRequest();
        // 路径判定直接复用付费墙的静态方法：两个墙必须始终拦同一条接口，
        // 复制一份口径迟早会漂移（例如将来详情 API 换了路径只改了一处）
        if (request.getMethod() != HttpMethod.GET
            || !ContentTicketFilter.isPostDetailPath(request.getPath().value())) {
            return chain.filter(exchange);
        }
        var articleName = ContentTicketFilter.extractArticleName(request.getPath().value());
        if (!StringUtils.hasText(articleName)) {
            return chain.filter(exchange);
        }
        var openid = resolveOpenid(exchange);
        return subscriptionWallService.resolveLockIfRequired(openid, articleName)
            .timeout(RESOLVE_TIMEOUT)
            .flatMap(locked -> writeLocked(exchange, locked))
            // empty = 无需拦截（已订阅/豁免/付费文章/配置关闭），交给 Halo 原生链路
            .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)))
            .onErrorResume(TimeoutException.class, ex -> {
                log.warn("订阅墙判定超时，放行原生链路: articleName={}", articleName);
                return chain.filter(exchange);
            })
            .onErrorResume(Exception.class, ex -> {
                log.warn("订阅墙判定异常，放行原生链路: articleName={}, {}: {}",
                    articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return chain.filter(exchange);
            });
    }

    private Mono<Void> writeLocked(ServerWebExchange exchange, SubscriptionLockedDTO locked) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            var payload = OBJECT_MAPPER.writeValueAsBytes(locked);
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception ex) {
            log.error("订阅墙锁定响应序列化失败: {}", ex.getMessage());
            // 序列化失败时退回最小响应体，仍然保持 403，绝不放行正文
            var payload = "{\"locked\":true,\"type\":\"subscription\",\"error\":\"响应序列化失败\"}"
                .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        }
    }

    private static String resolveOpenid(ServerWebExchange exchange) {
        var openid = exchange.getRequest().getHeaders().getFirst(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    @Override
    public int getOrder() {
        // 与 ContentTicketFilter 同层：在授权判定之前介入，匿名游客也能被拦下引导登录。
        // 两墙各自独立判定，且订阅墙对付费文章直接放行，先后顺序不影响结果。
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() - 1;
    }
}
