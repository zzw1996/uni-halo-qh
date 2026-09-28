package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxAccessTokenProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 微信订阅消息下发（{@code /cgi-bin/message/subscribe/send}）。
 *
 * <p><b>字段硬约束</b>：模板里的 {@code thing} 类型最多 20 个字，超了微信直接报 47003
 * 且整条消息发不出去。所以所有文本都必须过 {@link #clip}：按 code point 截断
 * （中文一个字算一个、emoji 也算一个）、折叠换行与多余空白。</p>
 *
 * <p><b>错误码要分类</b>，否则运营层面看不懂发生了什么：</p>
 * <ul>
 *   <li>{@code 43101} 用户拒收或没有额度——这是<b>正常业务状态</b>（一次性订阅用完了），
 *       调用方应清零额度、静默跳过，不要打 error 刷屏；</li>
 *   <li>{@code 43102} 模板类目不符——站长的模板没绑定服务类目，属于配置问题；</li>
 *   <li>{@code 40037} 模板 ID 无效、{@code 41030} page 路径不存在——也是配置问题；</li>
 *   <li>{@code 47003} 参数不符合要求——多半是字段超长或日期格式不对；</li>
 *   <li>{@code 40001 / 42001} token 失效——清缓存后重试一次（收口到共享 provider 后，
 *       这种失败主要是「别处刚换过 token」造成的）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WxSubscribeMessageService {

    private static final String SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    /** thing 类型字段的官方上限：20 个字符 */
    static final int MAX_THING_LENGTH = 20;

    /** 下发限速：给整站一个安全阀，避免误配后高频打微信接口 */
    private static final String RATE_KEY = "wx:subscribe:send";
    private static final int RATE_LIMIT = 600;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    private static final WebClient WEB_CLIENT = WebClient.builder().build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WxAccessTokenProvider tokenProvider;
    private final RateLimiter rateLimiter;

    /**
     * 一条待下发的消息内容（对应模板里的五个字段）。
     *
     * <p>用语义化名字而不是模板键：模板键（{@code thing4} 之类）属于「站长在小程序后台
     * 选的那个模板」，换模板时只需要改本类里的一处映射。</p>
     */
    public record Message(String title, String summary, Instant time, String source, String tip) {

        public static Message of(String title, String summary, Instant time, String source,
            String tip) {
            return new Message(title, summary, time, source, tip);
        }
    }

    /**
     * 下发结果。
     *
     * @param quotaExhausted 用户拒收或没有额度（43101）：调用方应清零该模板额度并静默跳过
     * @param configProblem  配置类问题（43102/40037/41030/47003）：需要站长去改配置
     */
    public record SendResult(boolean ok, int errorCode, String message, boolean quotaExhausted,
                            boolean configProblem) {

        static SendResult success() {
            return new SendResult(true, 0, "", false, false);
        }

        static SendResult failure(int errorCode, String message, boolean quotaExhausted,
            boolean configProblem) {
            return new SendResult(false, errorCode, message, quotaExhausted, configProblem);
        }
    }

    /**
     * 发送一条订阅消息。
     *
     * @param openid     接收者
     * @param templateId 模板 ID（空则直接返回配置问题，不去撞微信）
     * @param page       点击跳转的小程序页面（不含前导斜杠）
     * @param state      跳转版本：formal / trial / developer
     */
    public Mono<SendResult> send(String openid, String templateId, Message message, String page,
        String state) {
        if (!StringUtils.hasText(openid) || !StringUtils.hasText(templateId)) {
            return Mono.just(SendResult.failure(0,
                StringUtils.hasText(openid) ? "模板未配置" : "缺少 openid", false, true));
        }
        if (!rateLimiter.tryAcquire(RATE_KEY, RATE_LIMIT, RATE_WINDOW)) {
            log.warn("[UniHalo][Push] 下发频率超限，本轮跳过（保护微信接口）：openid={}",
                mask(openid));
            return Mono.just(SendResult.failure(0, "发送频率超限，稍后重试", false, false));
        }
        var payload = buildPayload(openid, templateId, message, page, state);
        return tokenProvider.token()
            .flatMap(token -> post(token, payload, openid, false))
            .onErrorResume(ex -> {
                // 取 token 失败 / 网络异常：如实回报，由调用方决定是否重试
                log.warn("[UniHalo][Push] 下发失败（{}）：openid={}", ex.getMessage(), mask(openid));
                return Mono.just(SendResult.failure(0, "下发异常：" + ex.getMessage(), false, false));
            });
    }

    /** 一次下发请求；token 失效（40001/42001）时由 {@link #handleResponse} 清缓存重试一次 */
    private Mono<SendResult> post(String token, ObjectNode payload, String openid,
        boolean retried) {
        return WEB_CLIENT.post().uri(SEND_URL + "?access_token=" + token)
            .header("Content-Type", "application/json")
            .bodyValue(payload.toString())
            .retrieve()
            .bodyToMono(String.class)
            .map(WxSubscribeMessageService::parseJson)
            .flatMap(node -> handleResponse(openid, payload, node, retried));
    }

    private Mono<SendResult> handleResponse(String openid, ObjectNode payload, JsonNode node,
        boolean retried) {
        var errcode = node.path("errcode").asInt(0);
        var errmsg = node.path("errmsg").asText("");
        if (errcode == 0) {
            return Mono.just(SendResult.success());
        }
        if ((errcode == 40001 || errcode == 42001) && !retried) {
            log.info("[UniHalo][Push] access_token 失效，清缓存后重试一次：errcode={}", errcode);
            tokenProvider.invalidate();
            return tokenProvider.token().flatMap(fresh -> post(fresh, payload, openid, true));
        }
        var result = classify(errcode, errmsg);
        if (result.quotaExhausted()) {
            log.info("[UniHalo][Push] 用户拒收或无订阅额度，跳过：openid={} errmsg={}",
                mask(openid), errmsg);
        } else if (result.configProblem()) {
            log.warn("[UniHalo][Push] 模板/参数配置有问题（errcode={} errmsg={}），"
                + "请检查：模板是否已启用并绑定服务类目、字段是否超 20 字、page 是否为有效页面",
                errcode, errmsg);
        } else {
            log.warn("[UniHalo][Push] 下发失败：errcode={} errmsg={} openid={}", errcode, errmsg,
                mask(openid));
        }
        return Mono.just(result);
    }

    /**
     * 错误码分类（抽出来是为了能单测——这些语义决定了要不要清额度、要不要让站长改配置）。
     *
     * <p>{@code 43101} 是「用户拒收/额度用完」，属于正常业务状态：调用方应清额度并静默跳过；
     * {@code 43102}（类目不符）、{@code 40037}（模板无效）、{@code 41030}（page 不存在）、
     * {@code 47003}（参数不符，多半是字段超长或日期格式）都是需要站长处理的配置问题。</p>
     */
    static SendResult classify(int errcode, String errmsg) {
        var quotaExhausted = errcode == 43101;
        var configProblem = errcode == 43102 || errcode == 40037 || errcode == 41030
            || errcode == 47003;
        return SendResult.failure(errcode, errmsg == null ? "" : errmsg, quotaExhausted,
            configProblem);
    }

    /** 按模板字段拼装请求体（字段名与站长在 MP 后台选的模板一一对应） */
    private ObjectNode buildPayload(String openid, String templateId, Message message, String page,
        String state) {
        var payload = OBJECT_MAPPER.createObjectNode();
        payload.put("touser", openid);
        payload.put("template_id", templateId);
        if (StringUtils.hasText(page)) {
            // 官方要求：page 不带前导斜杠，且必须是本小程序内已存在的页面
            payload.put("page", page.startsWith("/") ? page.substring(1) : page);
        }
        payload.put("miniprogram_state", StringUtils.hasText(state) ? state : "formal");
        payload.put("lang", "zh_CN");

        var data = payload.putObject("data");
        data.set("thing4", value(clip(message.title())));
        data.set("thing6", value(clip(message.summary())));
        data.set("time3", value(formatTime(message.time())));
        data.set("thing7", value(clip(message.source())));
        data.set("thing5", value(clip(message.tip())));
        return payload;
    }

    private static ObjectNode value(String text) {
        var node = OBJECT_MAPPER.createObjectNode();
        node.put("value", text == null ? "" : text);
        return node;
    }

    /**
     * thing 字段清洗：折叠空白 + 按 code point 截断到 20 字。
     *
     * <p>不加省略号——省略号本身也占一个字，不如把位置留给内容。</p>
     */
    static String clip(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        var collapsed = raw.replaceAll("\\s+", " ").trim();
        var points = collapsed.codePointCount(0, collapsed.length());
        if (points <= MAX_THING_LENGTH) {
            return collapsed;
        }
        var end = collapsed.offsetByCodePoints(0, MAX_THING_LENGTH);
        return collapsed.substring(0, end);
    }

    /** time 类型按官方示例的「年月日 时:分」格式，24 小时制 */
    static String formatTime(Instant time) {
        var at = time == null ? Instant.now() : time;
        return at.atZone(ZONE).format(TIME_FMT);
    }

    private static JsonNode parseJson(String text) {
        try {
            return OBJECT_MAPPER.readTree(text == null ? "" : text);
        } catch (Exception ex) {
            throw new IllegalStateException("微信订阅消息接口返回内容不是合法 JSON：" + text, ex);
        }
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未知)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }
}
