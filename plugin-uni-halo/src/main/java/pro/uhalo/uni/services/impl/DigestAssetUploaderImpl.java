package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.utils.DigestTextUtils;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * 头图上传实现。
 *
 * <p>两级策略：先让 Halo 自己去拉（upload-from-url，一条请求搞定，少一次中转），
 * 失败再自己下载后用 multipart 上传。这样既走的是官方附件接口，又不会被
 * 个别站点的防盗链/超时拖垮整批图片。</p>
 */
@Slf4j
@Service
public class DigestAssetUploaderImpl implements DigestAssetUploader {

    private static final Duration UPLOAD_FROM_URL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private static final String PAGE_USER_AGENT =
        "Mozilla/5.0 (compatible; AI-Daily-Digest/1.0; +https://github.com/)";

    private final DigestHaloClient haloClient;
    private final ReactiveSettingFetcher settingFetcher;

    private final WebClient downloadClient = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    public DigestAssetUploaderImpl(DigestHaloClient haloClient,
        ReactiveSettingFetcher settingFetcher) {
        this.haloClient = haloClient;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public Mono<String> uploadImage(String sourceUrl, String baseName, DigestConfigDTO config) {
        if (!DigestTextUtils.isUsableImageUrl(sourceUrl)) {
            return Mono.empty();
        }
        return resolveTarget(config)
            .flatMap(target -> uploadFromUrl(sourceUrl, baseName, target)
                .switchIfEmpty(Mono.defer(() -> downloadAndUpload(sourceUrl, baseName, target)))
                .doOnError(ex -> log.warn("[UniHalo][Digest] 头图上传失败 {}：{}",
                    sourceUrl, DigestHaloClient.describe(ex))));
    }

    // ── 方式一：让 Halo 从远程地址转存 ──

    private Mono<String> uploadFromUrl(String sourceUrl, String baseName, UploadTarget target) {
        var body = new LinkedHashMap<String, Object>();
        body.put("url", sourceUrl);
        body.put("policyName", target.policyName());
        body.put("groupName", target.groupName());
        body.put("filename", baseName);
        return haloClient.post(Constants.HALO_CONSOLE_ATTACHMENT_UPLOAD_FROM_URL, body,
                UPLOAD_FROM_URL_TIMEOUT)
            .map(this::extractPermalink)
            .filter(StringUtils::hasText)
            .onErrorResume(ex -> {
                log.debug("[UniHalo][Digest] upload-from-url 不可用，改走本地上传：{}",
                    DigestHaloClient.describe(ex));
                return Mono.empty();
            });
    }

    // ── 方式二：插件下载后 multipart 上传 ──

    private Mono<String> downloadAndUpload(String sourceUrl, String baseName, UploadTarget target) {
        return downloadClient.get()
            .uri(sourceUrl)
            .header(HttpHeaders.USER_AGENT, PAGE_USER_AGENT)
            .header(HttpHeaders.ACCEPT, "image/*")
            .exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.releaseBody().then(Mono.empty());
                }
                var contentType = response.headers().contentType()
                    .map(MediaType::toString).orElse("");
                if (DigestTextUtils.isTextualContentType(contentType)) {
                    // 失效图片常常返回 HTML 错误页，别把它当图片存进附件库
                    return response.releaseBody().then(Mono.empty());
                }
                return response.bodyToMono(byte[].class)
                    .filter(bytes -> bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES)
                    .map(bytes -> new DownloadedImage(bytes, contentType));
            })
            .timeout(DOWNLOAD_TIMEOUT)
            .flatMap(image -> uploadBytes(image, baseName, target))
            .onErrorResume(ex -> {
                log.debug("[UniHalo][Digest] 图片下载失败 {}：{}", sourceUrl, ex.getMessage());
                return Mono.empty();
            });
    }

    private Mono<String> uploadBytes(DownloadedImage image, String baseName, UploadTarget target) {
        var extension = DigestTextUtils.extensionFromContentType(image.contentType());
        if (!StringUtils.hasText(extension)) {
            extension = DigestTextUtils.extensionFromUrl(baseName);
        }
        var fileName = StringUtils.hasText(extension) ? baseName + "." + extension : baseName;

        var filePart = new ByteArrayResource(image.bytes()) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        var builder = new MultipartBodyBuilder();
        var mediaType = StringUtils.hasText(image.contentType())
            ? MediaType.parseMediaType(image.contentType()) : MediaType.IMAGE_JPEG;
        builder.part("file", filePart).contentType(mediaType);
        builder.part("policyName", target.policyName());
        if (StringUtils.hasText(target.groupName())) {
            builder.part("groupName", target.groupName());
        }

        return haloClient.postMultipart(Constants.HALO_CONSOLE_ATTACHMENT_UPLOAD, builder.build(),
                UPLOAD_TIMEOUT)
            .map(this::extractPermalink)
            .filter(StringUtils::hasText);
    }

    // ── 附件策略 / 分组 ──

    private Mono<UploadTarget> resolveTarget(DigestConfigDTO config) {
        var policyName = config.getAttachmentPolicyName() == null
            ? "" : config.getAttachmentPolicyName().trim();
        var groupName = config.getAttachmentGroupName() == null
            ? "" : config.getAttachmentGroupName().trim();
        if (StringUtils.hasText(policyName)) {
            return Mono.just(new UploadTarget(policyName, groupName));
        }
        // 未单独配置时复用「应用配置」里的附件策略，和二维码/头像上传保持一致
        return settingFetcher.get("appConfig")
            .map(appConfig -> appConfig.path("appInfo"))
            .map(appInfo -> new UploadTarget(
                appInfo.path("policyName").asText("").trim(),
                StringUtils.hasText(groupName)
                    ? groupName : appInfo.path("fileGroupName").asText("").trim()))
            .filter(target -> StringUtils.hasText(target.policyName()))
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "未配置附件存储策略，请在「AI 日报」或「应用配置」中填写策略名")));
    }

    private String extractPermalink(JsonNode attachment) {
        if (attachment == null) {
            return "";
        }
        var permalink = attachment.path("status").path("permalink").asText("");
        if (StringUtils.hasText(permalink)) {
            return permalink.trim();
        }
        // 少数策略下 permalink 通过 spec 下的字段返回
        var specPermalink = attachment.path("spec").path("permalink").asText("");
        return specPermalink.trim();
    }

    private record UploadTarget(String policyName, String groupName) {
    }

    private record DownloadedImage(byte[] bytes, String contentType) {
    }
}
