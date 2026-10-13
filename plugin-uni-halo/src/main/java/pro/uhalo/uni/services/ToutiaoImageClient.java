package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.utils.DigestAiErrorExplainer;
import pro.uhalo.uni.utils.ToutiaoImageHelper;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 兼容的文生图客户端（{@code POST {apiBase}/images/generations}）。
 *
 * <p>只服务今日头条号的配图：与对话客户端（{@link DigestAiClient}）分开，因为
 * <b>能写文章的通道不一定能生图</b>（DeepSeek 就没有图片接口），站长需要单独指一条
 * 支持图片的通道与模型名。</p>
 *
 * <p>响应两种形态都要认：{@code data[0].url}（多数中转站/官方默认）与
 * {@code data[0].b64_json}（部分供应商只回 base64）。前者交给
 * {@code DigestAssetUploader.uploadImage} 走 Halo 官方转存，后者解出字节后走
 * multipart 上传——两条路最终都落到站点附件库，得到一个可长期引用的站内地址。</p>
 */
@Slf4j
@Component
public class ToutiaoImageClient {

    /** 生图比对话慢：给足 3 分钟 */
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(180);

    /** 图片经 base64 塞在响应里时体积更大 */
    private static final int MAX_IN_MEMORY = 32 * 1024 * 1024;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    /**
     * 生成一张图。
     *
     * @param channel 通道（apiBase / apiKey 来自 AI 日报的通道清单）
     * @param model   图片模型名（如 {@code dall-e-3}、{@code wanx2.1-t2i-turbo}、{@code flux.1-schnell}）
     * @param size    尺寸（如 {@code 1024x1024}，按模型支持的档位填）
     * @param prompt  画面描述
     * @return 图片地址（url 形态）或图片字节（b64 形态），两者必居其一
     */
    public Mono<GeneratedImage> generate(DigestAiProviderDTO channel, String model, String size,
        String prompt) {
        if (channel == null || !StringUtils.hasText(channel.apiBase())) {
            return Mono.error(new IllegalStateException("图片通道未配置端点"));
        }
        if (!StringUtils.hasText(model)) {
            return Mono.error(new IllegalStateException("图片模型名为空"));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model.trim());
        body.put("prompt", prompt);
        // 逐张调用（n=1）：DALL·E 3 这类模型只支持 n=1，逐张调用还能让每张的提示词不同
        body.put("n", 1);
        if (StringUtils.hasText(size)) {
            body.put("size", size.trim());
        }

        return clientFor(channel.apiBase()).post()
            .uri("/images/generations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            // 与对话客户端同一取舍：用 exchangeToMono 保留 4xx/5xx 的正文，
            // 错误翻译（401/404/模型不存在…）才有材料
            .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(text -> new ImageAnswer(response.statusCode().value(), text)))
            .timeout(IMAGE_TIMEOUT)
            .flatMap(answer -> parse(channel, model, answer));
    }

    private Mono<GeneratedImage> parse(DigestAiProviderDTO channel, String model,
        ImageAnswer answer) {
        if (answer.status() < 200 || answer.status() >= 300) {
            return Mono.error(new IllegalStateException(DigestAiErrorExplainer.explain(
                answer.status(), answer.body(), channel.apiBase(), model)));
        }
        try {
            var root = OBJECT_MAPPER.readTree(answer.body());
            var item = firstImageNode(root);
            if (item == null) {
                return Mono.error(new IllegalStateException(
                    "图片接口没有返回图片：" + DigestAiErrorExplainer.snippet(answer.body())));
            }
            var url = item.path("url").asText("").trim();
            if (StringUtils.hasText(url)) {
                return Mono.just(new GeneratedImage(null, url, ""));
            }
            var b64 = item.path("b64_json").asText("").trim();
            if (StringUtils.hasText(b64)) {
                var bytes = decodeBase64(b64);
                return Mono.just(new GeneratedImage(bytes, "",
                    ToutiaoImageHelper.mediaTypeOf(bytes)));
            }
            return Mono.error(new IllegalStateException(
                "图片接口返回里既没有 url 也没有 b64_json："
                    + DigestAiErrorExplainer.snippet(answer.body())));
        } catch (Exception ex) {
            return Mono.error(new IllegalStateException(
                "图片接口返回解析失败：" + DigestAiErrorExplainer.snippet(ex.getMessage()), ex));
        }
    }

    /** {@code data[0]}，兼容个别中转站直接回 {@code data: {…}} 的写法 */
    private static JsonNode firstImageNode(JsonNode root) {
        var data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            return data.get(0);
        }
        if (data.isObject()) {
            return data;
        }
        return null;
    }

    /** data URL 前缀（{@code data:image/png;base64,}）也要能解 */
    private static byte[] decodeBase64(String value) {
        var payload = value;
        var comma = value.indexOf(',');
        if (value.startsWith("data:") && comma > 0) {
            payload = value.substring(comma + 1);
        }
        return Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
    }

    private WebClient clientFor(String base) {
        return clients.computeIfAbsent(base, key -> WebClient.builder()
            .baseUrl(key)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
            .build());
    }

    /** 一次 HTTP 往返的原始结果（错误翻译与解析共用） */
    private record ImageAnswer(int status, String body) {
    }

    /**
     * 生成结果：{@code url} 与 {@code bytes} 二者必居其一。
     *
     * <p>URL 形态优先——交给 Halo 官方「从 URL 转存」可以省掉我们自己的下载与格式判断。</p>
     */
    public record GeneratedImage(byte[] bytes, String url, String mediaType) {
    }
}
