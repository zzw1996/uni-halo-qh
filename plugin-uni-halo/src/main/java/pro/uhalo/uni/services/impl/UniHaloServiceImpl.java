package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.scheme.QRCodeInfo;
import pro.uhalo.uni.services.QRCodeInfoService;
import pro.uhalo.uni.services.UniHaloService;
import pro.uhalo.uni.utils.TokenManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务实现
 *
 * @author 小莫唐尼
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UniHaloServiceImpl implements UniHaloService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final WebClient WEB_CLIENT = WebClient.builder().build();
    private static final SecureRandom SCENE_KEY_RANDOM = new SecureRandom();
    private static final String SCENE_KEY_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final QRCodeInfoService qrCodeInfoService;
    private final ReactiveSettingFetcher settingFetcher;
    private final TokenManager tokenManager;
    private final AttachmentService attachmentService;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ConcurrentHashMap<String, Mono<String>> qrCodeGenerating = new ConcurrentHashMap<>();

    public static String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    public static String GET_WXA_CODE_URL = "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=";

    @Override
    public Mono<Map<String, JsonNode>> getAppConfigs() {
        return this.settingFetcher.getValues();
    }

    @Override
    public Mono<JsonNode> getAppConfigsByGroupName(String groupName) {
        return this.settingFetcher.get(groupName);
    }

    @Override
    public Mono<String> resolveQRCodeImageUrl(String postId) {
        Mono<String> generating = qrCodeGenerating.computeIfAbsent(postId, id ->
            doResolveQRCodeImageUrl(postId)
                .cache()
                .doFinally(signal -> qrCodeGenerating.remove(id))
        );
        return generating;
    }

    private Mono<String> doResolveQRCodeImageUrl(String postId) {
        return qrCodeInfoService.fetchByPostId(postId)
            .flatMap(existing -> {
                if (!StringUtils.hasText(existing.getImageUrl())) {
                    return generateAndPersistQRCode(postId, existing);
                }
                return Mono.defer(() -> Mono.just(
                    externalLinkProcessor.processLink(existing.getImageUrl())));
            })
            .switchIfEmpty(Mono.defer(() -> {
                QRCodeInfo qrCodeInfo = new QRCodeInfo();
                qrCodeInfo.setPostId(postId);
                return qrCodeInfoService.save(qrCodeInfo)
                    .switchIfEmpty(Mono.error(new IllegalStateException("二维码记录创建失败，请稍后重试")))
                    .flatMap(info -> generateAndPersistQRCode(postId, info));
            }));
    }

    private Mono<String> generateAndPersistQRCode(String postId, QRCodeInfo info) {
        return ensureSceneKey(info, postId)
            .flatMap(readyInfo -> ensureAccessToken()
                .flatMap(token -> requestWxacode(token, readyInfo.getKey()))
                .flatMap(bytes -> getMediaUrl(postId, bytes))
                .flatMap(url -> {
                    readyInfo.setImageUrl(url);
                    return qrCodeInfoService.update(readyInfo)
                        .onErrorResume(e -> qrCodeInfoService.fetchByPostId(postId))
                        .map(updatedInfo -> {
                            String urlToUse = updatedInfo != null && StringUtils.hasText(updatedInfo.getImageUrl())
                                ? updatedInfo.getImageUrl()
                                : url;
                            return externalLinkProcessor.processLink(urlToUse);
                        });
                }));
    }

    private Mono<QRCodeInfo> ensureSceneKey(QRCodeInfo info, String postId) {
        if (StringUtils.hasText(info.getKey())) {
            return Mono.just(info);
        }
        String key = info.getMetadata() != null && StringUtils.hasText(info.getMetadata().getName())
            ? info.getMetadata().getName()
            : generateSceneKey();
        info.setKey(key);
        if (info.getMetadata() == null) {
            Metadata metadata = new Metadata();
            metadata.setName(key);
            metadata.setCreationTimestamp(Instant.now());
            info.setMetadata(metadata);
        } else if (!StringUtils.hasText(info.getMetadata().getName())) {
            info.getMetadata().setName(key);
        }
        return qrCodeInfoService.update(info)
            .onErrorResume(e -> Mono.just(info));
    }

    private String generateSceneKey() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(SCENE_KEY_CHARS.charAt(SCENE_KEY_RANDOM.nextInt(SCENE_KEY_CHARS.length())));
        }
        return sb.toString();
    }

    private Mono<String> ensureAccessToken() {
        String token = tokenManager.getToken();
        if (StringUtils.hasText(token)) {
            return Mono.just(token);
        }
        return getAccessToken().flatMap(accessTokenJson -> {
            if (accessTokenJson.has("errcode") && accessTokenJson.get("errcode").asInt() != 0) {
                return Mono.error(new IllegalStateException(
                    "微信 access_token 获取失败：" + accessTokenJson.path("errmsg").asText("请检查 appId/appSecret 配置")));
            }
            JsonNode accessTokenNode = accessTokenJson.get("access_token");
            if (accessTokenNode == null || accessTokenNode.isNull() || !StringUtils.hasText(accessTokenNode.asText())) {
                return Mono.error(new IllegalStateException("微信 access_token 为空，请检查小程序 appId/appSecret 配置"));
            }
            String accessToken = accessTokenNode.asText();
            int expiresIn = accessTokenJson.path("expires_in").asInt(7200);
            tokenManager.setToken(accessToken, expiresIn);
            return Mono.just(accessToken);
        });
    }

    private Mono<byte[]> requestWxacode(String token, String sceneKey) {
        return requestWxacode(token, sceneKey, false);
    }

    private Mono<byte[]> requestWxacode(String token, String sceneKey, boolean retried) {
        if (!StringUtils.hasText(sceneKey)) {
            return Mono.error(new IllegalStateException("二维码 scene 为空，无法生成小程序码"));
        }
        Map<String, Object> map = new HashMap<>();
        map.put("scene", sceneKey);
        map.put("page", "pages/index/index");
        map.put("width", 280);
        map.put("auto_color", true);
        map.put("is_hyaline", false);
        map.put("check_path", false);
        return WEB_CLIENT.post().uri(GET_WXA_CODE_URL + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Mono.just(JSONObject.toJSONString(map)), String.class)
            .retrieve()
            .bodyToMono(byte[].class)
            .flatMap(bytes -> parseWxacodeResponse(bytes, sceneKey, retried))
            .onErrorMap(this::mapWxApiError);
    }

    private Mono<byte[]> parseWxacodeResponse(byte[] bytes, String sceneKey, boolean retried) {
        if (bytes == null || bytes.length == 0) {
            return Mono.error(new IllegalStateException("微信小程序码接口返回空数据"));
        }
        if (bytes[0] != '{') {
            return Mono.just(bytes);
        }
        try {
            JsonNode err = OBJECT_MAPPER.readTree(bytes);
            if (!err.has("errcode") || err.get("errcode").isNull()) {
                return Mono.error(new IllegalStateException("微信小程序码响应解析失败"));
            }
            int errcode = err.get("errcode").asInt();
            if (errcode == 0) {
                return Mono.error(new IllegalStateException("微信小程序码响应格式异常"));
            }
            String errmsg = err.path("errmsg").asText("未知错误");
            if ((errcode == 40001 || errcode == 42001) && !retried) {
                tokenManager.clearToken();
                return ensureAccessToken()
                    .flatMap(newToken -> requestWxacode(newToken, sceneKey, true));
            }
            return Mono.error(new IllegalStateException("微信小程序码生成失败(" + errcode + ")：" + errmsg));
        } catch (IllegalStateException e) {
            return Mono.error(e);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("微信小程序码响应解析失败"));
        }
    }

    @Override
    public Mono<ServerResponse> getQRCodeInfo(ServerRequest serverRequest) {
        String key = serverRequest.pathVariable("key");
        return qrCodeInfoService.fetchByKey(key).flatMap(info -> {
            if (Objects.isNull(info)) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok().body(Mono.just(info), QRCodeInfo.class);
        });
    }

    private Mono<JsonNode> getAccessToken() {
        return this.getAppConfigsByGroupName("appConfig")
            .switchIfEmpty(Mono.error(new IllegalStateException("未找到 appConfig 配置，请先在插件设置中保存应用配置")))
            .flatMap(config -> {
            JsonNode appInfo = config.get("appInfo");
            if (appInfo == null || appInfo.isNull()) {
                return Mono.error(new IllegalStateException("未找到 appConfig.appInfo 配置"));
            }
            String appId = appInfo.path("appId").asText("");
            String appSecret = appInfo.path("appSecret").asText("");
            if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
                return Mono.error(new IllegalStateException("请先在插件配置中填写小程序 appId 和 appSecret"));
            }
            String url = TOKEN_URL + "?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;
            return WEB_CLIENT.get().uri(url).retrieve().bodyToMono(JsonNode.class);
        }).onErrorMap(this::mapWxApiError);
    }

    private Mono<Attachment> uploadMedia(String postId, byte[] mediaData) {
        String safeName = postId.replaceAll("[^a-zA-Z0-9_-]", "_");
        FilePart filePart = new SimpleFilePart(
            safeName + ".png", byteArrayToFlux(mediaData), MediaType.IMAGE_PNG);

        return this.getAppConfigsByGroupName("appConfig")
            .switchIfEmpty(Mono.error(new IllegalStateException("未找到 appConfig 配置，请先在插件设置中保存应用配置")))
            .flatMap(config -> {
            JsonNode appInfo = config.get("appInfo");
            if (appInfo == null || appInfo.isNull()) {
                return Mono.error(new IllegalStateException("未找到 appConfig.appInfo 配置"));
            }
            String policyName = appInfo.path("policyName").asText("");
            if (!StringUtils.hasText(policyName)) {
                return Mono.error(new IllegalStateException("未配置附件存储策略 policyName"));
            }
            String fileGroupName = appInfo.path("fileGroupName").asText("");
            return attachmentService.upload("admin", policyName, fileGroupName, filePart, null);
        }).onErrorMap(ex -> ex instanceof IllegalStateException
            ? ex
            : new IllegalStateException("二维码图片上传失败：" + ex.getMessage(), ex));
    }

    private Flux<DataBuffer> byteArrayToFlux(byte[] bytes) {
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        return Flux.just(bytes)
            .map(data -> bufferFactory.wrap(ByteBuffer.wrap(data)));
    }

    private Mono<String> getMediaUrl(String postId, byte[] mediaData) {
        return uploadMedia(postId, mediaData)
            .flatMap(this::getPermalink)
            .switchIfEmpty(Mono.error(new IllegalStateException("二维码图片上传成功但未获取到访问地址")));
    }

    private Mono<String> getPermalink(Attachment attachment) {
        return attachmentService.getPermalink(attachment)
            .flatMap(uri -> Mono.just(uri.toString()))
            .switchIfEmpty(Mono.error(new IllegalStateException("二维码图片访问地址生成失败")));
    }

    private Throwable mapWxApiError(Throwable ex) {
        if (ex instanceof IllegalStateException) {
            return ex;
        }
        if (ex instanceof run.halo.app.extension.exception.ExtensionException extensionException) {
            String reason = extensionException.getReason();
            return new IllegalStateException(StringUtils.hasText(reason)
                ? reason
                : "二维码生成失败，请稍后重试");
        }
        if (ex instanceof WebClientResponseException responseException) {
            return new IllegalStateException(
                "微信接口请求失败(" + responseException.getStatusCode().value() + ")：" + responseException.getMessage());
        }
        if (ex instanceof WebClientRequestException) {
            return new IllegalStateException("微信接口网络异常，请稍后重试");
        }
        return new IllegalStateException(
            StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "二维码生成失败，请稍后重试", ex);
    }
}
