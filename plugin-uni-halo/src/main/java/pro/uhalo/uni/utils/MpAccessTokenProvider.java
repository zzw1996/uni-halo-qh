package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.MpConfigDTO;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;

/**
 * 微信公众号 access_token 的唯一来源（本功能独占一份缓存）。
 *
 * <p><b>为什么必须独立一份</b>：{@code WxAccessTokenProvider} 是小程序侧的 token 缓存，
 * 一个 appId 只允许一份有效 token（微信发出新 token 时旧 token 立刻失效）。
 * 公众号是另一个 appId、另一份凭据，共用同一份缓存会把对方的 token 挤掉——
 * 这正是仓库里记录过的老坑，所以这里单独一份，互不干扰。</p>
 *
 * <p><b>用 stable_token 而不是 /cgi-bin/token</b>：前者在有效期内重复调用返回同一个 token
 * （多实例/并发安全），而后者每次调用都会让上一个 token 失效。插件与 Halo 同进程部署，
 * 但站点可能多实例，且后台可能反复点「自检」——用 stable_token 就不会互相踩。</p>
 *
 * <p><b>不要再挂 {@code @EnableScheduling}</b>：插件里只有一份（在
 * {@code WxAccessTokenProvider} 上）。本类不需要定时清理——token 到期后是「读时发现过期再重取」，
 * 不存在长期挂着失效串的问题。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MpAccessTokenProvider {

    private static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";

    /** 提前失效窗口：微信给 7200s，这里提前 5 分钟作废，避免在途请求拿到 40001 */
    private static final Duration SAFETY_WINDOW = Duration.ofMinutes(5);

    private static final WebClient WEB_CLIENT = WebClient.builder().build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;

    private volatile String cachedToken = "";
    private volatile long cachedExpireAtMillis = 0L;

    /** 在途换取任务（同一时刻最多一个），换完即清 */
    private Mono<JsonNode> inFlight;

    /** 自检用：一次换取的结果（不抛异常，保留微信原始错误与处置建议） */
    public record AccessTokenProbe(boolean ok, String maskedToken, MpErrors.MpError error) {
    }

    /**
     * 取一个可用的 access_token。
     *
     * <p>失败以 {@link IllegalStateException} 抛出（消息里带处置建议），
     * 由各调用方包装成对外的错误。</p>
     */
    public Mono<String> token() {
        var cached = cachedToken;
        if (StringUtils.hasText(cached) && System.currentTimeMillis() < cachedExpireAtMillis) {
            return Mono.just(cached);
        }
        return sharedFetch().map(JsonNode -> {
            var token = JsonNode.path("access_token").asText("");
            if (!StringUtils.hasText(token)) {
                throw new IllegalStateException("微信 access_token 为空，请检查公众号 AppID/AppSecret 配置");
            }
            return token;
        });
    }

    /** 作废当前 token（接口返回 40001/42001 时调用）；只清缓存、不阻塞 */
    public void invalidate() {
        synchronized (this) {
            cachedToken = "";
            cachedExpireAtMillis = 0L;
            inFlight = null;
        }
    }

    /**
     * 自检：真实调用一次 stable_token，把结果分类返回（不抛异常）。
     *
     * <p>40164（IP 白名单）时 {@link MpErrors.MpError#ip()} 会带上服务器真实出口 IP，
     * 管理页直接显示、复制即可粘贴到公众号后台。</p>
     */
    public Mono<AccessTokenProbe> probe() {
        return loadCredentials()
            .flatMap(credentials -> requestToken(credentials)
                .map(node -> {
                    var errcode = node.path("errcode").asInt(0);
                    if (errcode != 0) {
                        var error = MpErrors.of(errcode, node.path("errmsg").asText(""));
                        log.warn("[UniHalo][Mp] access_token 自检失败：{}", MpErrors.describe(error));
                        return new AccessTokenProbe(false, "", error);
                    }
                    var token = node.path("access_token").asText("");
                    if (!StringUtils.hasText(token)) {
                        return new AccessTokenProbe(false, "",
                            MpErrors.of(MpErrors.PLUGIN_CODE, "接口未返回 access_token"));
                    }
                    cache(token, node.path("expires_in").asLong(7200L));
                    return new AccessTokenProbe(true, mask(token), MpErrors.ok());
                })
                .onErrorResume(ex -> Mono.just(new AccessTokenProbe(false, "",
                    MpErrors.of(MpErrors.PLUGIN_CODE, StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
                        : "网络请求失败：请检查服务器出网能力")))))
            .onErrorResume(ex -> Mono.just(new AccessTokenProbe(false, "",
                MpErrors.of(MpErrors.PLUGIN_CODE, StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
                    : "读取公众号配置失败"))));
    }

    /** 凭据是否已配置（缺一个就返回错误，提示填哪一项） */
    public Mono<Credentials> loadCredentials() {
        return settingFetcher.get(MpConfigHelper.groupName())
            .defaultIfEmpty(OBJECT_MAPPER.createObjectNode())
            .map(MpConfigHelper::parse)
            .flatMap(config -> {
                if (!StringUtils.hasText(config.getAppId())
                    || !StringUtils.hasText(config.getAppSecret())) {
                    return Mono.error(new IllegalStateException(
                        "公众号 AppID/AppSecret 未配置：请在插件设置「微信公众号」里填写"));
                }
                return Mono.just(new Credentials(config.getAppId().trim(),
                    config.getAppSecret().trim()));
            });
    }

    /** 同一时刻只有一个换取任务：并发调用共享它，避免互相把 token 挤掉 */
    private synchronized Mono<JsonNode> sharedFetch() {
        if (inFlight == null) {
            inFlight = loadCredentials()
                .flatMap(this::requestToken)
                .map(node -> {
                    var errcode = node.path("errcode").asInt(0);
                    if (errcode != 0) {
                        throw new IllegalStateException("微信 access_token 获取失败："
                            + MpErrors.describe(MpErrors.of(errcode, node.path("errmsg").asText(""))));
                    }
                    var expiresIn = node.path("expires_in").asLong(7200L);
                    cache(node.path("access_token").asText(""), expiresIn);
                    return node;
                })
                .cache()
                .doFinally(signal -> {
                    synchronized (this) {
                        inFlight = null;
                    }
                });
        }
        return inFlight;
    }

    /**
     * 调用 stable_token。
     *
     * <p>{@code force_refresh=false}：普通模式在有效期内返回同一个 token，
     * 不会失效其它实例缓存的那一份。强制刷新有每日 20 次的限制，插件不用它。</p>
     */
    private Mono<JsonNode> requestToken(Credentials credentials) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("grant_type", "client_credential");
        payload.put("appid", credentials.appId());
        payload.put("secret", credentials.appSecret());
        payload.put("force_refresh", false);
        return WEB_CLIENT.post().uri(STABLE_TOKEN_URL)
            .header("Content-Type", "application/json")
            .bodyValue(payload.toString())
            .retrieve()
            .bodyToMono(String.class)
            .map(MpAccessTokenProvider::parseJson)
            .timeout(Duration.ofSeconds(15));
    }

    private void cache(String token, long expiresInSeconds) {
        var ttl = Math.max(60L,
            Duration.ofSeconds(expiresInSeconds).minus(SAFETY_WINDOW).toSeconds());
        synchronized (this) {
            cachedToken = token;
            cachedExpireAtMillis = System.currentTimeMillis() + ttl * 1000L;
        }
        log.debug("[UniHalo][Mp] 公众号 access_token 已刷新，{} 秒后失效", ttl);
    }

    private static JsonNode parseJson(String text) {
        try {
            return OBJECT_MAPPER.readTree(text == null ? "" : text);
        } catch (Exception ex) {
            throw new IllegalStateException("微信接口返回内容不是合法 JSON：" + text, ex);
        }
    }

    /** token 只回显前 8 位（自检展示用） */
    private static String mask(String token) {
        return token.length() <= 8 ? "***" : token.substring(0, 8) + "***";
    }

    /** 公众号凭据（appId + appSecret） */
    public record Credentials(String appId, String appSecret) {
    }
}
