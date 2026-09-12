package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.services.DigestAiClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容 chat/completions 客户端。
 *
 * <p>主通道默认指向 DeepSeek，兜底通道可以是任意 OpenAI 兼容服务（OpenAI、通义千问、
 * Groq、第三方中转）。两个通道共用同一份请求构造与响应解析。</p>
 */
@Slf4j
@Component
public class DigestAiClientImpl implements DigestAiClient {

    /** 长文摘要单次响应可以跑很久，给足 5 分钟 */
    private static final Duration AI_TIMEOUT = Duration.ofMinutes(5);

    /** deepseek-chat 默认 4096 输出上限，10 篇一批的 JSON 容易被截断，顶到 8K */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private static final int MAX_IN_MEMORY = 16 * 1024 * 1024;

    private static final String DEFAULT_FALLBACK_MODEL = "gpt-4o-mini";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    /** 本轮运行内是否已经降级到兜底通道 */
    private final AtomicBoolean fallbackActive = new AtomicBoolean(false);

    @Override
    public void resetForNewRun() {
        fallbackActive.set(false);
    }

    @Override
    public Mono<String> call(DigestConfigDTO config, String prompt) {
        var primaryKey = trim(config.getAiApiKey());
        var fallbackKey = trim(config.getFallbackApiKey());

        if (!StringUtils.hasText(primaryKey) && !StringUtils.hasText(fallbackKey)) {
            return Mono.error(new IllegalStateException(
                "未配置 AI API Key，请在插件设置「AI 日报」中填写主模型 Key"));
        }

        var useFallback = !StringUtils.hasText(primaryKey)
            || (fallbackActive.get() && StringUtils.hasText(fallbackKey));
        if (useFallback) {
            return complete(config, false, prompt);
        }

        return complete(config, true, prompt)
            .onErrorResume(ex -> {
                if (!StringUtils.hasText(fallbackKey)) {
                    return Mono.error(ex);
                }
                if (fallbackActive.compareAndSet(false, true)) {
                    log.warn("[UniHalo][Digest] 主模型调用失败，切换到兜底通道（base={} model={}）：{}",
                        fallbackBase(config), fallbackModel(config), ex.getMessage());
                }
                return complete(config, false, prompt);
            });
    }

    private Mono<String> complete(DigestConfigDTO config, boolean primary, String prompt) {
        var base = normalizeBase(primary ? config.getAiApiBase() : fallbackBase(config));
        var key = primary ? trim(config.getAiApiKey()) : trim(config.getFallbackApiKey());
        var model = primary ? trim(config.getAiModel()) : fallbackModel(config);
        if (!StringUtils.hasText(model)) {
            model = inferModel(base);
        }
        if (!StringUtils.hasText(base) || !StringUtils.hasText(key)) {
            return Mono.error(new IllegalStateException(
                primary ? "主模型通道配置不完整（缺少 API Base 或 Key）"
                    : "兜底通道配置不完整（缺少 API Base 或 Key）"));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", 0.3);
        body.put("top_p", 0.8);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.put("stream", false);

        var providerLabel = primary ? "主模型" : "兜底模型";
        var finalModel = model;
        return clientFor(base).post()
            .uri("/chat/completions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(AI_TIMEOUT)
            .map(this::extractContent)
            .doOnError(ex -> log.warn("[UniHalo][Digest] {}调用失败（base={} model={}）：{}",
                providerLabel, base, finalModel, ex.getMessage()));
    }

    /**
     * 从响应里取第一个 choice 的文本内容。
     *
     * <p>部分兼容服务把 content 返回成 [{type:text, text:...}] 数组，需要拼起来。</p>
     */
    private String extractContent(String raw) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("AI 响应解析失败：" + snippet(raw), ex);
        }
        var content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            var builder = new StringBuilder();
            for (var item : content) {
                if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        if (content.isMissingNode() || content.isNull()) {
            var error = root.path("error").path("message").asText("");
            throw new IllegalStateException(StringUtils.hasText(error)
                ? "AI 未返回内容：" + error
                : "AI 未返回内容：" + snippet(raw));
        }
        return content.asText("");
    }

    private WebClient clientFor(String base) {
        return clients.computeIfAbsent(base, key -> WebClient.builder()
            .baseUrl(key)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
            .build());
    }

    private static String fallbackBase(DigestConfigDTO config) {
        return StringUtils.hasText(config.getFallbackApiBase())
            ? config.getFallbackApiBase() : "https://api.openai.com/v1";
    }

    private static String fallbackModel(DigestConfigDTO config) {
        if (StringUtils.hasText(config.getFallbackModel())) {
            return config.getFallbackModel().trim();
        }
        return inferModel(fallbackBase(config));
    }

    private static String inferModel(String base) {
        return base != null && base.toLowerCase().contains("deepseek")
            ? "deepseek-chat" : DEFAULT_FALLBACK_MODEL;
    }

    private static String normalizeBase(String base) {
        if (!StringUtils.hasText(base)) {
            return "";
        }
        return base.trim().replaceAll("/+$", "");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        var normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
