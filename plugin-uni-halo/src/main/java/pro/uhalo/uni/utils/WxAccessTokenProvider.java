package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;

/**
 * 微信 access_token 的唯一来源（全插件共用一份缓存）。
 *
 * <p><b>为什么要收口</b>：改造前插件里有三份互不感知的 token 缓存（小程序码与虚拟支付共用
 * {@code TokenManager} 但都不带提前刷新；内容安全自己一份带提前刷新）。它们各自用同一个
 * appid 去换 token，而微信在发出新 token 时会让旧 token 失效——于是出现
 * 「二维码刚生成过、支付查单就报 40001」这类互相挤掉的问题。订阅消息下发也要 token，
 * 再把第 4 份缓存加进来只会让这个问题更频繁。</p>
 *
 * <p>两条关键语义：</p>
 * <ul>
 *   <li><b>提前 5 分钟失效</b>：微信侧有效期 7200s，但用到最后一秒会导致在途请求拿到 40001；</li>
 *   <li><b>在途合并</b>：并发调用共享同一次换取（{@code Mono.cache()}），否则两个线程同时
 *       发现缓存空会各换一次，后换的把先换的挤掉。</li>
 * </ul>
 *
 * <p><b>调度注解为什么在这里</b>：插件里没有任何 {@code @Configuration} 类，历史上是
 * {@code TokenManager} 上的 {@code @EnableScheduling} 顺带把调度框架打开的
 * （AI 日报、周榜结算、支付兜底查单、本类的过期清理都依赖它）。那个类已被本类取代，
 * 注解随之搬过来——<b>删掉它等于静默关掉插件里所有 {@code @Scheduled}</b>。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableScheduling
public class WxAccessTokenProvider {

    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 提前失效窗口：微信给 7200s，这里提前 5 分钟作废 */
    private static final Duration SAFETY_WINDOW = Duration.ofMinutes(5);

    private static final String WX_APP_CONFIG_GROUP = "wxAppConfig";
    private static final String APP_CONFIG_GROUP = "appConfig";

    private static final WebClient WEB_CLIENT = WebClient.builder().build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;

    private volatile String cachedToken = "";
    private volatile long cachedExpireAtMillis = 0L;

    /** 在途换取任务（同一时刻最多一个），换完即清 */
    private Mono<String> inFlight;

    /**
     * 取一个可用的 access_token。
     *
     * <p>失败以 {@link IllegalStateException} 抛出，调用方按各自的对外错误类型包装
     * （虚拟支付要 {@code WxAuthException}、小程序码要 500 语义）——这样收口不会顺手
     * 改掉各接口对外的错误码。</p>
     */
    public Mono<String> token() {
        var cached = cachedToken;
        if (StringUtils.hasText(cached) && System.currentTimeMillis() < cachedExpireAtMillis) {
            return Mono.just(cached);
        }
        return sharedFetch();
    }

    /**
     * 作废当前 token（接口返回 40001/42001 时调用）。
     *
     * <p>只清缓存、不阻塞：调用方决定本次是重试一次还是如实报错
     * （小程序码选择重试一次，内容安全选择放行并告警）。</p>
     */
    public void invalidate() {
        synchronized (this) {
            cachedToken = "";
            cachedExpireAtMillis = 0L;
            inFlight = null;
        }
    }

    /** 同一时刻只有一个换取任务：并发调用共享它，避免互相把 token 挤掉 */
    private synchronized Mono<String> sharedFetch() {
        if (inFlight == null) {
            inFlight = fetch().cache()
                .doFinally(signal -> {
                    synchronized (this) {
                        inFlight = null;
                    }
                });
        }
        return inFlight;
    }

    private Mono<String> fetch() {
        return loadAppCredentials().flatMap(credentials -> {
            var url = TOKEN_URL + "?grant_type=client_credential&appid=" + credentials.appId()
                + "&secret=" + credentials.appSecret();
            // String + 手动解析，避开部分 Spring/Jackson 组合下 bodyToMono(JsonNode.class) 的
            // "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]"
            // 崩溃（虚拟支付链路早年踩过这个坑）
            return WEB_CLIENT.get().uri(url).retrieve().bodyToMono(String.class)
                .map(WxAccessTokenProvider::parseJson)
                .flatMap(body -> {
                    if (body.hasNonNull("errcode") && body.get("errcode").asInt() != 0) {
                        return Mono.error(new IllegalStateException("微信 access_token 获取失败："
                            + body.path("errmsg").asText("请检查 appId/AppSecret 配置")));
                    }
                    var token = body.path("access_token").asText("");
                    if (!StringUtils.hasText(token)) {
                        return Mono.error(new IllegalStateException(
                            "微信 access_token 为空，请检查 appId/AppSecret 配置"));
                    }
                    var expiresIn = body.path("expires_in").asLong(7200L);
                    var ttl = Math.max(60L,
                        Duration.ofSeconds(expiresIn).minus(SAFETY_WINDOW).toSeconds());
                    synchronized (this) {
                        cachedToken = token;
                        cachedExpireAtMillis = System.currentTimeMillis() + ttl * 1000L;
                    }
                    log.debug("[UniHalo][WxToken] access_token 已刷新，{} 秒后失效", ttl);
                    return Mono.just(token);
                });
        });
    }

    /**
     * appId/appSecret 的两处来源：优先「微信小程序配置」，回落「应用配置 - 应用信息」。
     *
     * <p>与各调用方改造前的取法一致——历史站点可能只填了其中一处，不能只认一处。</p>
     */
    private Mono<Credentials> loadAppCredentials() {
        return settingFetcher.get(WX_APP_CONFIG_GROUP)
            .defaultIfEmpty(OBJECT_MAPPER.createObjectNode())
            .flatMap(wxConfig -> settingFetcher.get(APP_CONFIG_GROUP)
                .defaultIfEmpty(OBJECT_MAPPER.createObjectNode())
                .map(appConfig -> {
                    var appInfo = appConfig.path("appInfo");
                    return new Credentials(
                        firstNonEmpty(wxConfig.path("appId").asText(""),
                            appInfo.path("appId").asText("")),
                        firstNonEmpty(wxConfig.path("appSecret").asText(""),
                            appInfo.path("appSecret").asText("")));
                }))
            .flatMap(credentials -> {
                if (!StringUtils.hasText(credentials.appId())
                    || !StringUtils.hasText(credentials.appSecret())) {
                    return Mono.error(new IllegalStateException(
                        "微信小程序 AppID 或 AppSecret 未配置"));
                }
                return Mono.just(credentials);
            });
    }

    /** 每分钟清理一次已过期缓存：内存里长期挂着失效 token 会造成误判 */
    @Scheduled(fixedRate = 60000)
    public void evictExpired() {
        synchronized (this) {
            if (StringUtils.hasText(cachedToken)
                && System.currentTimeMillis() >= cachedExpireAtMillis) {
                cachedToken = "";
                cachedExpireAtMillis = 0L;
            }
        }
    }

    private static JsonNode parseJson(String text) {
        try {
            return OBJECT_MAPPER.readTree(text == null ? "" : text);
        } catch (Exception ex) {
            throw new IllegalStateException("微信接口返回内容不是合法 JSON：" + text, ex);
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return StringUtils.hasText(first) ? first.trim()
            : (second == null ? "" : second.trim());
    }

    private record Credentials(String appId, String appSecret) {
    }
}
