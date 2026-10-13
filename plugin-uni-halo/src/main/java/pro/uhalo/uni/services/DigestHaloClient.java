package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 调 Halo 官方接口的轻量客户端。
 *
 * <p>与 {@code HaloUserSyncServiceImpl} 同一口径：走 127.0.0.1 本机回环 + 个人令牌，
 * 不经过外网域名，也不依赖 Halo 内部服务类，因此不受 Halo 内部 API 变动影响。</p>
 */
@Component
public class DigestHaloClient {

    private static final String BASIC_CONFIG_GROUP = "basicConfig";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DIGEST_CONFIG_GROUP = "aiDigestConfig";

    private final ReactiveSettingFetcher settingFetcher;
    private final String localBaseUrl;
    private final Map<String, WebClient> clients = new java.util.concurrent.ConcurrentHashMap<>();

    public DigestHaloClient(ReactiveSettingFetcher settingFetcher,
        @Value("${server.port:8090}") int serverPort) {
        this.settingFetcher = settingFetcher;
        this.localBaseUrl = "http://127.0.0.1:" + serverPort;
    }

    /**
     * 解析要使用的接口地址。
     *
     * <p>默认走本机回环（插件与 Halo 同进程，最快也最稳）。若站长在设置里填了站点地址，
     * 就用它——这样插件的请求与后台浏览器的请求走完全相同的链路，
     * 便于判断"后台能发文、插件不能"这类中间层差异。</p>
     */
    public Mono<String> resolveBaseUrl() {
        return settingFetcher.get(DIGEST_CONFIG_GROUP)
            .map(config -> config.path("haloBaseUrl").asText("").trim())
            .map(configured -> configured.isEmpty()
                ? localBaseUrl
                : configured.replaceAll("/+$", ""))
            .defaultIfEmpty(localBaseUrl);
    }

    private WebClient clientFor(String baseUrl) {
        return clients.computeIfAbsent(baseUrl, base -> WebClient.builder()
            .baseUrl(base)
            // 日报正文接近百 KB，图片上传还有二进制体，默认 256KB 缓冲不够用
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build());
    }

    /**
     * 统一补齐请求头。
     *
     * <p>对齐后台浏览器的请求特征：带上 X-Requested-With（部分过滤器按它区分 XHR），
     * 外部地址时再带上 Origin/Referer，避免站点上的中间层对"看起来不像后台的请求"
     * 做不同处理。</p>
     */
    private org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec<?> withCommonHeaders(
        org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec<?> spec,
        String baseUrl, String token) {
        spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("X-Requested-With", "XMLHttpRequest");
        if (!baseUrl.startsWith("http://127.0.0.1")) {
            spec = spec.header(HttpHeaders.ORIGIN, baseUrl)
                .header(HttpHeaders.REFERER, baseUrl + "/console/");
        }
        return spec;
    }

    /** 读取插件「基本配置」里的个人令牌 */
    public Mono<String> requireToken() {
        return settingFetcher.get(BASIC_CONFIG_GROUP)
            .map(config -> config.path("tokenConfig").path("personalToken").asText(""))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "未配置「基本配置 - 个人令牌」，无法调用 Halo 发布接口")));
    }

    public Mono<JsonNode> get(String path, Duration timeout) {
        return resolveBaseUrl().flatMap(baseUrl -> requireToken()
            .flatMap(token -> withCommonHeaders(clientFor(baseUrl).get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON), baseUrl, token)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)))
            .defaultIfEmpty("")
            .map(raw -> readTree(raw, path));
    }

    /**
     * 匿名读取（不带个人令牌）。
     *
     * <p>站点内容接口对游客开放，插件内部取首页内容流时不该因为它依赖令牌——
     * 没配令牌的站点照样能用订阅功能。未登录访问受限的站点会返回 401/403，
     * 由调用方降级（客户端会回落到全站最新列表）。</p>
     */
    public Mono<JsonNode> getAnonymous(String path, Duration timeout) {
        return resolveBaseUrl()
            .flatMap(baseUrl -> clientFor(baseUrl).get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout))
            .defaultIfEmpty("")
            .map(raw -> readTree(raw, path));
    }

    public Mono<JsonNode> post(String path, Object body, Duration timeout) {
        return resolveBaseUrl().flatMap(baseUrl -> requireToken()
            .flatMap(token -> withCommonHeaders(clientFor(baseUrl).post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body == null ? java.util.Map.of() : body), baseUrl, token)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)))
            .defaultIfEmpty("")
            .map(raw -> readTree(raw, path));
    }

    public Mono<JsonNode> put(String path, Object body, Duration timeout) {
        return resolveBaseUrl().flatMap(baseUrl -> requireToken()
            .flatMap(token -> {
                var spec = clientFor(baseUrl).put()
                    .uri(path)
                    .accept(MediaType.APPLICATION_JSON);
                var withBody = body == null
                    ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                return withCommonHeaders(withBody, baseUrl, token)
                    .retrieve().bodyToMono(String.class).timeout(timeout);
            }))
            .defaultIfEmpty("")
            .map(raw -> readTree(raw, path));
    }

    /** DELETE：Halo 删除成功通常返回空体，读成 String 再丢弃，避免空体解码歧义 */
    public Mono<Void> delete(String path, Duration timeout) {
        return resolveBaseUrl().flatMap(baseUrl -> requireToken()
            .flatMap(token -> withCommonHeaders(clientFor(baseUrl).delete()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON), baseUrl, token)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)))
            .then();
    }

    public Mono<JsonNode> postMultipart(String path, MultiValueMap<String, HttpEntity<?>> parts,
        Duration timeout) {
        return resolveBaseUrl().flatMap(baseUrl -> requireToken()
            .flatMap(token -> withCommonHeaders(clientFor(baseUrl).post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(parts)), baseUrl, token)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)))
            .defaultIfEmpty("")
            .map(raw -> readTree(raw, path));
    }

    private JsonNode readTree(String raw, String path) {
        if (!StringUtils.hasText(raw)) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Halo 接口响应解析失败（" + path + "）：" + snippet(raw), ex);
        }
    }

    /** 把 WebClient 异常转成带响应体的可读错误，后台能直接看到 Halo 给出的原因 */
    public static String describe(Throwable ex) {
        if (ex instanceof WebClientResponseException responseException) {
            return "HTTP " + responseException.getStatusCode().value() + " "
                + summarize(responseException.getResponseBodyAsString());
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * 把 Halo 的错误响应压成一行有用信息。
     *
     * <p>原始响应体是一整串 JSON（detail / instance / requestId / timestamp …），
     * 塞进客户端弹窗里既读不懂也放不下。这里只留「title：detail + requestId」——
     * requestId 是 Halo 日志里的检索键，排障时必须带上；解析不出来就退回截断原文。</p>
     */
    private static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            var node = OBJECT_MAPPER.readTree(text);
            var title = node.path("title").asText("");
            var detail = node.path("detail").asText("");
            var requestId = node.path("requestId").asText("");
            var message = title;
            if (!detail.isBlank() && !detail.equals(title)) {
                message = message.isBlank() ? detail : message + "：" + detail;
            }
            if (!requestId.isBlank()) {
                message = message.isBlank() ? "requestId=" + requestId
                    : message + "（requestId=" + requestId + "）";
            }
            if (!message.isBlank()) {
                return snippet(message);
            }
        } catch (Exception ignored) {
            // 不是 JSON（网关 HTML、纯文本报错等）时按原文截断
        }
        return snippet(text);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        var normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }
}
