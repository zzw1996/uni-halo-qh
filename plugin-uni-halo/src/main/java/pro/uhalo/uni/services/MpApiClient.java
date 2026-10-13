package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.utils.MpAccessTokenProvider;
import pro.uhalo.uni.utils.MpErrors;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 微信公众号服务端接口客户端（草稿箱 / 发布 / 群发 / 素材 / 图文内图片）。
 *
 * <p>调用口径与仓库里的小程序侧微信客户端保持一致：{@code String + ObjectMapper.readTree}
 * （避开 {@code bodyToMono(JsonNode.class)} 的 Jackson 泛型崩溃坑）、
 * {@code 40001/42001} 清 token 缓存后重试一次、微信侧错误一律分类成
 * {@link MpErrors.MpError} 而不是抛异常——「IP 没加白」「没有留言权限」这类问题
 * 需要原样展示给站长，而不是变成一句 500。</p>
 *
 * <p>本类只做「一次接口调用」，不关心重试策略、记录与幂等（那是
 * {@code MpPushService} 的职责）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MpApiClient {

    /** 接口地址常量（包内可见：单测会逐个校验拼接后的 URL 合法性） */
    static final String UPLOAD_CONTENT_IMAGE_URL =
        "https://api.weixin.qq.com/cgi-bin/media/uploadimg";
    /**
     * 新增永久素材。{@code type=image} 必须<b>作为 query 参数</b>出现
     * （线上踩过：早期把它拼成 {@code .../add_material&type=image}，
     * 微信直接回 {@code 40066 invalid url}，素材上传从第一版起就没成功过）。
     */
    static final String ADD_MATERIAL_URL =
        "https://api.weixin.qq.com/cgi-bin/material/add_material?type=image";
    static final String DRAFT_ADD_URL =
        "https://api.weixin.qq.com/cgi-bin/draft/add";
    static final String DRAFT_DELETE_URL =
        "https://api.weixin.qq.com/cgi-bin/draft/delete";
    static final String FREE_PUBLISH_SUBMIT_URL =
        "https://api.weixin.qq.com/cgi-bin/freepublish/submit";
    static final String FREE_PUBLISH_GET_URL =
        "https://api.weixin.qq.com/cgi-bin/freepublish/get";
    static final String MASS_SENDALL_URL =
        "https://api.weixin.qq.com/cgi-bin/message/mass/sendall";

    /** 待校验的接口地址清单（单测用：任何一个拼错都会让相应功能整体不可用） */
    static java.util.List<String> allApiUrls() {
        return java.util.List.of(UPLOAD_CONTENT_IMAGE_URL, ADD_MATERIAL_URL, DRAFT_ADD_URL,
            DRAFT_DELETE_URL, FREE_PUBLISH_SUBMIT_URL, FREE_PUBLISH_GET_URL, MASS_SENDALL_URL);
    }

    /**
     * 给接口地址带上 access_token。
     *
     * <p>地址里已经有 query（如 {@code add_material?type=image}）时用 {@code &} 续接——
     * 一律用 {@code ?} 会把参数吞掉，微信会回 {@code 40066 invalid url}。</p>
     */
    static String appendToken(String url, String token) {
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "access_token=" + token;
    }

    /** 上传类请求给足时间（图片最大 10MB） */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private static final WebClient WEB_CLIENT = WebClient.builder()
        // 默认 256KB 的内存缓冲装不下一张封面（最大 10MB 的永久素材上传）
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MpAccessTokenProvider tokenProvider;

    /**
     * 一篇待进草稿箱的图文。
     *
     * <p>字段与官方 draft/add 的 articles[] 一一对应；{@code digest} 永远显式给值——
     * 留空时微信会抓正文前 54 个字当摘要，抓出来通常很难看。</p>
     */
    public record MpArticle(String title, String author, String digest, String content,
                            String contentSourceUrl, String thumbMediaId,
                            boolean needOpenComment, boolean onlyFansCanComment) {
    }

    /** 一次接口调用的结果（ok=false 时 error 分类可读） */
    public record MpCall(String api, boolean ok, JsonNode body, MpErrors.MpError error) {

        /** 从响应里取一个字符串字段（null 安全） */
        public String text(String field) {
            return body == null ? "" : body.path(field).asText("");
        }

        public int intValue(String field) {
            return body == null ? 0 : body.path(field).asInt(0);
        }

        static MpCall success(String api, JsonNode body) {
            return new MpCall(api, true, body, MpErrors.ok());
        }

        static MpCall failure(String api, MpErrors.MpError error) {
            return new MpCall(api, false, null, error);
        }
    }

    // ── 草稿箱 ──

    /** 新增草稿（articles 为 1 篇时是单图文；多篇会成为一个多图文草稿，最多 8 篇） */
    public Mono<MpCall> addDraft(List<MpArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return Mono.just(MpCall.failure("draft/add", MpErrors.of(MpErrors.PLUGIN_CODE, "没有可推送的文章")));
        }
        ArrayNode list = OBJECT_MAPPER.createArrayNode();
        for (var article : articles) {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("article_type", "news");
            node.put("title", article.title());
            if (StringUtils.hasText(article.author())) {
                node.put("author", article.author());
            }
            // 摘要必须显式给：留空时微信自动抓正文前 54 字，通常抓出排版符号
            node.put("digest", StringUtils.hasText(article.digest()) ? article.digest() : "");
            node.put("content", article.content());
            if (StringUtils.hasText(article.contentSourceUrl())) {
                node.put("content_source_url", article.contentSourceUrl());
            }
            node.put("thumb_media_id", article.thumbMediaId());
            node.put("need_open_comment", article.needOpenComment() ? 1 : 0);
            node.put("only_fans_can_comment", article.onlyFansCanComment() ? 1 : 0);
            list.add(node);
        }
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.set("articles", list);
        return call("draft/add", DRAFT_ADD_URL, payload, DEFAULT_TIMEOUT);
    }

    /** 删除草稿（自检探针用它清掉测试草稿，避免在站长草稿箱里留垃圾） */
    public Mono<MpCall> deleteDraft(String mediaId) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("media_id", mediaId);
        return call("draft/delete", DRAFT_DELETE_URL, payload, DEFAULT_TIMEOUT);
    }

    // ── 素材与图文内图片 ──

    /**
     * 上传「图文消息内的图片」：返回的是一段 {@code mmbiz.qpic.cn} 的 URL（不是 media_id）。
     *
     * <p>只支持 jpg/png 且小于 1MB；不占用永久素材库数量。正文里的外链图片会被微信过滤，
     * 必须先用它换成微信自己的地址。</p>
     */
    public Mono<MpCall> uploadContentImage(byte[] bytes, String filename) {
        return uploadContentImage(bytes, filename, "image/jpeg");
    }

    public Mono<MpCall> uploadContentImage(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return Mono.just(MpCall.failure("media/uploadimg", MpErrors.of(MpErrors.PLUGIN_CODE, "图片内容为空")));
        }
        return upload("media/uploadimg", UPLOAD_CONTENT_IMAGE_URL, bytes, filename, contentType,
            UPLOAD_TIMEOUT, false);
    }

    /**
     * 上传永久图片素材：返回 media_id（草稿封面 thumb_media_id 必须是永久素材）。
     */
    public Mono<MpCall> uploadPermanentImage(byte[] bytes, String filename) {
        return uploadPermanentImage(bytes, filename, "image/jpeg");
    }

    public Mono<MpCall> uploadPermanentImage(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return Mono.just(MpCall.failure("material/add_material", MpErrors.of(MpErrors.PLUGIN_CODE, "图片内容为空")));
        }
        return upload("material/add_material", ADD_MATERIAL_URL, bytes, filename,
            contentType, UPLOAD_TIMEOUT, false);
    }

    // ── 发布与群发 ──

    /** 提交发布（异步）：errcode=0 只代表任务已提交，需再用 queryPublish 查结果 */
    public Mono<MpCall> submitPublish(String mediaId) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("media_id", mediaId);
        return call("freepublish/submit", FREE_PUBLISH_SUBMIT_URL, payload, DEFAULT_TIMEOUT);
    }

    /** 查询发布状态（publish_status：0 成功 / 1 发布中 / 2 原创声明失败 / 3 失败 / 4 审核不通过） */
    public Mono<MpCall> queryPublish(String publishId) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("publish_id", publishId);
        return call("freepublish/get", FREE_PUBLISH_GET_URL, payload, DEFAULT_TIMEOUT);
    }

    /** 群发（推送给全部粉丝）：会占用群发次数，媒体来自草稿箱的 media_id */
    public Mono<MpCall> massSendAll(String mediaId) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("msgtype", "mpnews");
        var filter = payload.putObject("filter");
        filter.put("is_to_all", true);
        var mpnews = payload.putObject("mpnews");
        mpnews.put("media_id", mediaId);
        // 被判定为转载时是否继续群发：0 = 停止（默认），保持官方默认语义
        payload.put("send_ignore_reprint", 0);
        return call("message/mass/sendall", MASS_SENDALL_URL, payload, DEFAULT_TIMEOUT);
    }

    // ── 内部 ──

    private Mono<MpCall> upload(String api, String url, byte[] bytes, String filename,
        String contentType) {
        return upload(api, url, bytes, filename, contentType, UPLOAD_TIMEOUT, false);
    }

    private Mono<MpCall> upload(String api, String url, byte[] bytes, String filename,
        String contentType, Duration timeout, boolean retried) {
        // 手写 multipart 而不是用 MultipartBodyBuilder：微信前置网关会因为「请求没有
        // Content-Length」（WebFlux 的 multipart 编码器默认走 chunked）直接回 412，
        // 而自己拼字节数组 + 显式 Content-Length 就能稳定通过。
        var boundary = "----UniHaloMp" + java.util.UUID.randomUUID().toString()
            .replace("-", "");
        var body = multipartBody(boundary, "media", filename, contentType, bytes);
        return withToken(api, token -> WEB_CLIENT.post()
            .uri(appendToken(url, token))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Content-Length", String.valueOf(body.length))
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(MpApiClient::parseJson)
            .timeout(timeout)
            .flatMap(node -> classify(api, node, retried,
                () -> upload(api, url, bytes, filename, contentType, timeout, true))));
    }

    /**
     * 组装 multipart 请求体（单个文件字段）。
     *
     * <p>逐字节可控：boundary、字段名、filename、part 的 Content-Type 都显式给出——
     * 微信要求 media 字段带 filename 与 content-type，缺一个都可能被网关拦掉。</p>
     */
    static byte[] multipartBody(String boundary, String fieldName, String filename,
        String contentType, byte[] data) {
        var head = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
            + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tail = ("\r\n--" + boundary + "--\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var output = new java.io.ByteArrayOutputStream(head.length + data.length + tail.length);
        output.writeBytes(head);
        output.writeBytes(data);
        output.writeBytes(tail);
        return output.toByteArray();
    }

    private Mono<MpCall> call(String api, String url, ObjectNode payload, Duration timeout) {
        return call(api, url, payload, timeout, false);
    }

    private Mono<MpCall> call(String api, String url, ObjectNode payload, Duration timeout,
        boolean retried) {
        return withToken(api, token -> WEB_CLIENT.post()
            .uri(appendToken(url, token))
            .header("Content-Type", "application/json")
            .bodyValue(payload.toString())
            .retrieve()
            .bodyToMono(String.class)
            .map(MpApiClient::parseJson)
            .timeout(timeout)
            .flatMap(node -> classify(api, node, retried,
                () -> call(api, url, payload, timeout, true))));
    }

    /** 取 token（失败即返回分类错误，不抛异常）；网络异常同样分类返回 */
    private Mono<MpCall> withToken(String api, java.util.function.Function<String, Mono<MpCall>> action) {
        return tokenProvider.token()
            .flatMap(action)
            .onErrorResume(ex -> {
                var error = transportError(ex);
                log.warn("[UniHalo][Mp] {} 调用失败：{}", api, MpErrors.describe(error));
                return Mono.just(MpCall.failure(api, error));
            });
    }

    /**
     * 把异常翻译成可读错误。
     *
     * <p>HTTP 层失败（微信网关拒绝）不会带 errcode JSON，过去只会留下一句
     * 「412 Precondition Failed from POST ...」——这里把状态码与响应体都捞出来，
     * 交给 {@link MpErrors} 给出处置建议。</p>
     */
    static MpErrors.MpError transportError(Throwable ex) {
        if (ex instanceof WebClientResponseException responseException) {
            var status = responseException.getStatusCode().value();
            var body = responseException.getResponseBodyAsString();
            var errcode = 0;
            var errmsg = "";
            if (StringUtils.hasText(body) && body.trim().startsWith("{")) {
                try {
                    var node = OBJECT_MAPPER.readTree(body);
                    errcode = node.path("errcode").asInt(0);
                    errmsg = node.path("errmsg").asText("");
                } catch (Exception ignored) {
                    // 网关返回的不是 JSON：下面按状态码分类
                }
            }
            if (errcode != 0) {
                return MpErrors.of(errcode, errmsg);
            }
            return MpErrors.of(-status, StringUtils.hasText(body) ? body : "");
        }
        var message = ex == null ? null : ex.getMessage();
        if (StringUtils.hasText(message) && message.contains("Timeout")) {
            return MpErrors.of(MpErrors.PLUGIN_CODE, "请求超时（" + message + "）");
        }
        return MpErrors.of(MpErrors.PLUGIN_CODE, StringUtils.hasText(message) ? message : "网络请求失败");
    }

    /**
     * 把响应分类；token 失效（40001/42001）时清缓存重试一次（只重试一次，
     * 否则持续 40001 时会无限递归）。
     *
     * <p>重试用 {@code retry} 回调重新走一遍完整调用（含重新取 token），
     * 与小程序订阅消息的处置一致。</p>
     */
    private Mono<MpCall> classify(String api, JsonNode node, boolean retried,
        java.util.function.Supplier<Mono<MpCall>> retry) {
        var errcode = node.path("errcode").asInt(0);
        if (errcode == 0) {
            return Mono.just(MpCall.success(api, node));
        }
        var error = MpErrors.of(errcode, node.path("errmsg").asText(""));
        if (error.tokenInvalid() && !retried) {
            log.info("[UniHalo][Mp] {} 报 token 失效（errcode={}），清缓存后重试一次", api, errcode);
            tokenProvider.invalidate();
            return retry.get();
        }
        log.warn("[UniHalo][Mp] {} 失败：{}", api, MpErrors.describe(error));
        return Mono.just(MpCall.failure(api, error));
    }

    private static JsonNode parseJson(String text) {
        try {
            return OBJECT_MAPPER.readTree(text == null ? "" : text);
        } catch (Exception ex) {
            throw new IllegalStateException("微信接口返回内容不是合法 JSON：" + text, ex);
        }
    }
}
