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
import pro.uhalo.uni.dto.ContentLockedDTO;
import pro.uhalo.uni.services.ContentTicketService;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

/**
 * 付费文章内容拦截 Filter。
 *
 * <p>拦截文章详情接口 {@code GET /apis/api.content.halo.run/v1alpha1/posts/{name}}，
 * 若文章被标记为付费（带指定 slug 的标签）且当前微信用户未解锁，
 * 则<b>直接返回 403 + 锁定信息</b>，不再执行后续链路，因此 Halo 根本不会读取正文。</p>
 *
 * <p><b>为什么不用「改写响应体」的方案</b>：改写需要先把整个响应体缓冲到内存
 * 再替换正文，一旦缓冲或 content-length 处理出错，付费正文就会原样泄露给客户端；
 * 而直接短路返回锁定信息，从结构上就不存在「正文已进入本 Filter 处理范围」的可能，
 * 安全边界清晰得多。这也是本类唯一的 {@code ContentLockedDTO} 数据来源。</p>
 *
 * <p><b>锁定响应绝不包含正文</b>：{@link ContentLockedDTO} 只携带标题、摘要
 * （取自 {@code post.status.excerpt}，Halo 自动生成、列表页本就公开）、封面，
 * 服务端全程不读取文章 Snapshot 内容。</p>
 *
 * <p><b>失败方向（fail-open）</b>：判定过程任何异常都放行到 Halo 原生链路，
 * 而不是拦截。误拦截会让全站付费文章（甚至因标签解析失败波及其他文章）打不开，
 * 影响面远大于「少收一次费」；异常均记录 warn 日志便于排查。</p>
 *
 * <p>⚠️ <b>生效范围</b>：本 Filter 只保护小程序调用的文章详情 API。
 * Halo 网页版主题通过 permalink（{@code /{slug}}）直接渲染 Snapshot，不走该 API，
 * 因此博客网站本身仍可阅读全文。需要网页端也付费时，应改用 Halo 原生的
 * INTERNAL / PRIVATE 可见性或改造主题模板。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentTicketFilter implements AdditionalWebFilter {

    /** 判定超时：超时即放行，避免拖慢或阻塞正常阅读 */
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ContentTicketService contentTicketService;

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        var request = exchange.getRequest();
        // 只处理文章详情的 GET 请求；其余请求（含列表、评论、后台）一律直接放行，
        // 保证付费功能异常时不会影响小程序其他任何能力
        if (request.getMethod() != HttpMethod.GET || !isPostDetailPath(request.getPath().value())) {
            return chain.filter(exchange);
        }
        var articleName = extractArticleName(request.getPath().value());
        if (!StringUtils.hasText(articleName)) {
            return chain.filter(exchange);
        }
        var openid = resolveOpenid(exchange);
        return contentTicketService.resolveLockIfRequired(openid, articleName)
            .timeout(RESOLVE_TIMEOUT)
            // 防御性放行：unlocked=true 表示博主本人或已解锁用户，本就不该拦截。
            // 服务层已对这两类返回 empty，此处再兜一层——若判定语义将来被改回
            // 「返回 DTO 表示已解锁」，也不至于让用户花了券还看到 403。
            .flatMap(locked -> {
                if (Boolean.TRUE.equals(locked.getUnlocked())) {
                    log.warn("付费判定返回已解锁但要求拦截，按放行处理: articleName={}", articleName);
                    return chain.filter(exchange);
                }
                return writeLocked(exchange, locked);
            })
            // empty = 无需拦截，交给 Halo 原生链路返回完整文章
            .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)))
            .onErrorResume(TimeoutException.class, ex -> {
                log.warn("付费内容判定超时，放行原生链路: articleName={}", articleName);
                return chain.filter(exchange);
            })
            .onErrorResume(Exception.class, ex -> {
                log.warn("付费内容判定异常，放行原生链路: articleName={}, {}: {}",
                    articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return chain.filter(exchange);
            });
    }

    /** 是否为文章详情路径（排除列表 {@code /posts} 与 {@code /posts?xxx} 等）。 */
    static boolean isPostDetailPath(String path) {
        // path 可能为 null（畸形请求），此处不得抛异常：
        // Filter 位于所有请求的热路径上，未捕获异常会波及正常阅读
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (!path.startsWith(Constants.HALO_POST_API_PREFIX + "/")) {
            return false;
        }
        var rest = path.substring(Constants.HALO_POST_API_PREFIX.length() + 1);
        // 形如 {name} 才是详情；含 "/" 的是子资源（如 /posts/{name}/xxx），不拦截
        return StringUtils.hasText(rest) && !rest.contains("/");
    }

    /**
     * 从路径提取文章 metadata.name。
     *
     * <p>⚠️ 口径必须与前端一致：小程序所有详情页跳转传的都是
     * {@code post.metadata.name}（见 pagesA/article-detail/article-detail.vue 的
     * {@code onLoad} 与 10 处列表跳转），不是 slug。两侧口径不一致会导致
     * 拦截不到（内容泄露）或误拦截（文章打不开）。</p>
     */
    static String extractArticleName(String path) {
        if (!isPostDetailPath(path)) {
            return null;
        }
        var name = path.substring(Constants.HALO_POST_API_PREFIX.length() + 1);
        try {
            // 前端对 name 做了 encodeURIComponent，含中文或特殊字符时需解码后再比对
            return java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("文章名 URL 解码失败，按原值处理: {}", ex.getMessage());
            return name;
        }
    }

    private Mono<Void> writeLocked(ServerWebExchange exchange, ContentLockedDTO locked) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            var payload = OBJECT_MAPPER.writeValueAsBytes(locked);
            return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception ex) {
            log.error("付费内容锁定响应序列化失败: {}", ex.getMessage());
            // 序列化失败时退回最小响应体，仍然保持 403 拦截，绝不放行正文
            var payload = "{\"locked\":true,\"unlocked\":false,\"error\":\"响应序列化失败\"}"
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
        // 与 WxCommentProxyFilter 同层：在授权判定之前介入，
        // 这样匿名（未登录）用户访问付费文章也能被拦截并提示登录
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() - 1;
    }
}
