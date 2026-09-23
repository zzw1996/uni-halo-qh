package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.WxContentSecurityService;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;

/**
 * {@link WxContentSecurityService} 实现：微信 msgSecCheck。
 *
 * <p>access_token 带缓存（微信侧有效期 7200s，这里提前 5 分钟失效重取），
 * 避免每次投稿都换一次令牌——微信对 token 获取有频次限制，频繁换还会把
 * 其它用到 token 的功能（如二维码）挤掉。</p>
 */
@Service
@Slf4j
public class WxContentSecurityServiceImpl implements WxContentSecurityService {

    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String MSG_SEC_CHECK_URL =
        "https://api.weixin.qq.com/wxa/msg_sec_check";
    private static final String APP_CONFIG_GROUP = "appConfig";
    /** 接口单次上限 2500 字，超出截断检测（长文按首段判定，够拦住明显违规） */
    private static final int MAX_CHECK_LENGTH = 2500;
    /** token 提前失效窗口 */
    private static final Duration TOKEN_SAFETY_WINDOW = Duration.ofMinutes(5);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final WebClient WEB_CLIENT = WebClient.builder().build();

    private final ReactiveSettingFetcher settingFetcher;

    private volatile String cachedToken = "";
    private volatile long cachedTokenExpireAt = 0L;

    public WxContentSecurityServiceImpl(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    @Override
    public Mono<Void> checkText(String openid, String content) {
        var text = content == null ? "" : content.trim();
        if (!StringUtils.hasText(text)) {
            return Mono.empty();
        }
        if (text.length() > MAX_CHECK_LENGTH) {
            text = text.substring(0, MAX_CHECK_LENGTH);
        }
        var payload = new java.util.HashMap<String, Object>();
        payload.put("content", text);
        payload.put("version", 2);
        payload.put("scene", 2);
        if (StringUtils.hasText(openid)) {
            payload.put("openid", openid.trim());
        }
        var body = text;
        return loadAccessToken()
            .flatMap(token -> WEB_CLIENT.post()
                .uri(MSG_SEC_CHECK_URL + "?access_token=" + token)
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseJson))
            .flatMap(node -> {
                var errcode = node.path("errcode").asInt(0);
                var suggest = node.path("result").path("suggest").asText("");
                var label = node.path("result").path("label").asInt(0);
                if (errcode == 87014 || "risky".equalsIgnoreCase(suggest)) {
                    log.info("[UniHalo][ContentSecurity] 内容未通过检测：errcode={} suggest={} label={}",
                        errcode, suggest, label);
                    return Mono.error(new WxAuthException(
                        "内容包含违规信息，请修改后再发布（如有误判请联系站长）"));
                }
                if (errcode != 0) {
                    // 例如 40001（token 失效）之类：清掉缓存下次重取，但本次不拦用户
                    log.warn("[UniHalo][ContentSecurity] 检测接口返回异常：errcode={} errmsg={} 本次放行",
                        errcode, node.path("errmsg").asText(""));
                    invalidateToken();
                    return Mono.empty();
                }
                if ("review".equalsIgnoreCase(suggest)) {
                    // review = 疑似，交给链路后面的审核环节（角色策略里的 draft）
                    log.info("[UniHalo][ContentSecurity] 内容疑似违规，交由审核：label={}", label);
                }
                return Mono.empty();
            })
            .onErrorResume(WxAuthException.class, Mono::error)
            .onErrorResume(ex -> {
                // 微信侧不可用不阻塞发布：告警后放行，由「待审」环节兜底
                log.warn("[UniHalo][ContentSecurity] 检测失败（本次放行）：{}", ex.getMessage());
                return Mono.empty();
            })
            .then();
    }

    private Mono<String> loadAccessToken() {
        var token = cachedToken;
        if (StringUtils.hasText(token) && System.currentTimeMillis() < cachedTokenExpireAt) {
            return Mono.just(token);
        }
        return settingFetcher.get(APP_CONFIG_GROUP)
            .map(config -> {
                var appInfo = config.path("appInfo");
                var appId = appInfo.path("appId").asText("");
                var appSecret = appInfo.path("appSecret").asText("");
                if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
                    throw new WxAuthException("未配置小程序 appId/appSecret，无法进行内容安全检测");
                }
                return TOKEN_URL + "?grant_type=client_credential&appid=" + appId
                    + "&secret=" + appSecret;
            })
            .flatMap(url -> WEB_CLIENT.get().uri(url).retrieve().bodyToMono(String.class)
                .map(this::parseJson))
            .map(node -> {
                var fresh = node.path("access_token").asText("");
                if (!StringUtils.hasText(fresh)) {
                    throw new WxAuthException("微信 access_token 获取失败："
                        + node.path("errmsg").asText("请检查 appId/appSecret"));
                }
                var expiresIn = node.path("expires_in").asLong(7200L);
                cachedToken = fresh;
                cachedTokenExpireAt = System.currentTimeMillis()
                    + Duration.ofSeconds(expiresIn).minus(TOKEN_SAFETY_WINDOW).toMillis();
                return fresh;
            });
    }

    private void invalidateToken() {
        cachedToken = "";
        cachedTokenExpireAt = 0L;
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String raw) {
        try {
            return OBJECT_MAPPER.readTree(raw == null ? "{}" : raw);
        } catch (Exception ex) {
            throw new WxAuthException("微信接口响应解析失败");
        }
    }
}
