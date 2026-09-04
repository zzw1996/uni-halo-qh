package pro.uhalo.uni.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import pro.uhalo.uni.dto.WxLoginRequest;
import pro.uhalo.uni.exception.WxAuthException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 解析小程序直连接口的 JSON 请求体（插件隔离上下文下避免依赖 Spring 默认反序列化）。
 */
public final class WxRequestParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private WxRequestParser() {
    }

    /**
     * 读取原始 JSON 文本。不可使用 {@code bodyToMono(String.class)}：
     * application/json 下 Spring 会按 JSON 字符串解码，无法解析 {@code {"code":"..."}} 对象。
     */
    public static Mono<String> readRawBody(ServerRequest request) {
        return request.bodyToMono(byte[].class)
            .defaultIfEmpty(new byte[0])
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public static Mono<WxLoginRequest> parseLoginRequest(String raw) {
        return parseBody(raw, WxLoginRequest.class);
    }

    public static <T> Mono<T> parseBody(String raw, Class<T> clazz) {
        return Mono.fromCallable(() -> {
            var json = normalizeJson(raw);
            return OBJECT_MAPPER.readValue(json, clazz);
        }).onErrorMap(WxRequestParser::mapParseError);
    }

    private static String normalizeJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        var trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                return OBJECT_MAPPER.readValue(trimmed, String.class);
            } catch (JsonProcessingException ignored) {
                // fall through
            }
        }
        return trimmed;
    }

    private static Throwable mapParseError(Throwable ex) {
        if (ex instanceof WxAuthException wxAuthException) {
            return wxAuthException;
        }
        if (ex instanceof JsonProcessingException) {
            return new WxAuthException("请求体格式错误");
        }
        return ex;
    }
}
