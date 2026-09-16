package pro.uhalo.uni.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.utils.DigestTextUtils;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * 头图上传实现。
 *
 * <p><b>走 Halo 暴露给插件的官方附件服务（{@link AttachmentService}），不走自建 HTTP 请求。</b>
 * 这一点是本实现被重写的原因：早前这里用「个人令牌 + 调 /apis/api.console... 」的方式上传，
 * 是全插件唯一一条绕开官方 bean 的上传路径，也是唯一会踩下面这个坑的：</p>
 *
 * <p>官方接口返回的 permalink 在本地存储策略下是<b>相对路径</b>（形如 {@code /upload/2026/09/x.jpg}），
 * 而后续「这篇有没有封面」的判定要求绝对地址（{@code http(s)://}）。
 * 结果是图片明明上传成功，却被判成「没有封面」，整轮日报因此不发布，
 * 报错还提示「检查附件存储策略」——把排查引向完全错误的方向。
 * 现在统一经 {@link ExternalLinkProcessor#processLink(String)} 补成绝对地址，
 * 判据两端就不会再打架。</p>
 *
 * <p>仍然保留两级策略：先让 Halo 自己去拉（{@code uploadFromUrl}，一条调用搞定），
 * 失败再自己下载后上传。后者用于个别站点对服务端抓取不友好（防盗链、超时）的情况。</p>
 */
@Slf4j
@Service
public class DigestAssetUploaderImpl implements DigestAssetUploader {

    /**
     * 未配置存储策略时的兜底策略名。
     *
     * <p>与小程序头像上传保持一致：那里也是拿不到配置就回落它。
     * 直接报错是更差的选择——「应用配置」里的策略字段被「动态小程序码」开关包着，
     * 没开那个功能的站点根本不会存这个值，于是日报会因为一个与它无关的开关而彻底发不出去。</p>
     */
    private static final String DEFAULT_POLICY_NAME = "default-policy";

    /** 附件归属用户：与插件其它上传路径一致 */
    private static final String UPLOADER_USERNAME = "admin";

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private static final String PAGE_USER_AGENT =
        "Mozilla/5.0 (compatible; AI-Daily-Digest/1.0; +https://github.com/)";

    private final AttachmentService attachmentService;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ReactiveSettingFetcher settingFetcher;

    private final WebClient downloadClient = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    public DigestAssetUploaderImpl(AttachmentService attachmentService,
        ExternalLinkProcessor externalLinkProcessor,
        ReactiveSettingFetcher settingFetcher) {
        this.attachmentService = attachmentService;
        this.externalLinkProcessor = externalLinkProcessor;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public Mono<String> uploadImage(String sourceUrl, String baseName, DigestConfigDTO config) {
        if (!DigestTextUtils.isUsableImageUrl(sourceUrl)) {
            return Mono.empty();
        }
        // URL 解析放在最前面统一做一次，两个阶段都复用。
        // 「前缀是 https:// 但根本解析不出来」的地址是存在的（RSS 里的畸形标记就会产生），
        // 若不在这里拦下，它会在下载阶段以同步异常的形式炸出来。
        return Mono.defer(() -> {
            URL url;
            try {
                url = new URL(sourceUrl);
            } catch (Exception ex) {
                log.warn("[UniHalo][Digest] 头图地址不合法，跳过：{}", sourceUrl);
                return Mono.<String>empty();
            }
            // java.net.URL 相当宽松：`https://` 这种没有主机名的地址它照样接受，
            // 所以必须再查一次 host，否则会在下载阶段才炸出来
            if (!StringUtils.hasText(url.getHost())) {
                log.warn("[UniHalo][Digest] 头图地址缺少主机名，跳过：{}", sourceUrl);
                return Mono.<String>empty();
            }
            return resolveTarget(config)
                .flatMap(target -> uploadFromUrl(url, sourceUrl, baseName, target)
                    .switchIfEmpty(Mono.defer(
                        () -> downloadAndUpload(url, sourceUrl, baseName, target)))
                    .doOnError(ex -> log.warn("[UniHalo][Digest] 头图上传失败 {}：{}",
                        sourceUrl, describe(ex))));
        });
    }

    // ── 方式一：交给 Halo 从远程地址转存 ──

    private Mono<String> uploadFromUrl(URL url, String sourceUrl, String baseName,
        UploadTarget target) {
        return attachmentService
            .uploadFromUrl(url, target.policyName(), target.groupName(), baseName)
            .flatMap(this::toAbsolutePermalink)
            .filter(StringUtils::hasText)
            .onErrorResume(ex -> {
                // 升到 warn：原先这里是 debug，生产环境的 INFO 日志下完全看不到原因，
                // 等于把「为什么这批图都没传上去」的排查路堵死了
                log.warn("[UniHalo][Digest] Halo 转存头图失败，改由插件下载后上传：{} → {}",
                    sourceUrl, describe(ex));
                return Mono.empty();
            });
    }

    // ── 方式二：插件下载字节流后走官方上传接口 ──

    private Mono<String> downloadAndUpload(URL url, String sourceUrl, String baseName,
        UploadTarget target) {
        return downloadClient.get()
            .uri(url.toString())
            .header(HttpHeaders.USER_AGENT, PAGE_USER_AGENT)
            .header(HttpHeaders.ACCEPT, "image/*")
            .exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) {
                    log.warn("[UniHalo][Digest] 头图下载被拒绝，跳过：{} → HTTP {}",
                        sourceUrl, response.statusCode().value());
                    return response.releaseBody().then(Mono.empty());
                }
                var contentType = response.headers().contentType()
                    .map(MediaType::toString).orElse("");
                if (DigestTextUtils.isTextualContentType(contentType)) {
                    // 失效图片常常返回 HTML 错误页，别把它当图片存进附件库
                    log.warn("[UniHalo][Digest] 头图地址返回的不是图片，跳过：{} → {}",
                        sourceUrl, contentType);
                    return response.releaseBody().then(Mono.empty());
                }
                return response.bodyToMono(byte[].class)
                    .filter(bytes -> bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES)
                    .map(bytes -> new DownloadedImage(bytes, contentType));
            })
            .timeout(DOWNLOAD_TIMEOUT)
            .flatMap(image -> uploadBytes(image, baseName, target))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 头图下载失败，跳过：{} → {}", sourceUrl, describe(ex));
                return Mono.empty();
            });
    }

    private Mono<String> uploadBytes(DownloadedImage image, String baseName, UploadTarget target) {
        var extension = DigestTextUtils.extensionFromContentType(image.contentType());
        if (!StringUtils.hasText(extension)) {
            extension = DigestTextUtils.extensionFromUrl(baseName);
        }
        var fileName = StringUtils.hasText(extension) ? baseName + "." + extension : baseName;
        var mediaType = StringUtils.hasText(image.contentType())
            ? MediaType.parseMediaType(image.contentType()) : MediaType.IMAGE_JPEG;

        var filePart = new SimpleFilePart(fileName, byteArrayToFlux(image.bytes()), mediaType);
        return attachmentService
            .upload(UPLOADER_USERNAME, target.policyName(), target.groupName(), filePart, null)
            .flatMap(this::toAbsolutePermalink)
            .filter(StringUtils::hasText);
    }

    // ── 地址绝对化 ──

    /**
     * 附件 → 可直接使用的绝对地址。
     *
     * <p>{@code getPermalink} 拿不到时退回 {@code status.permalink}，两条路都要过
     * {@link ExternalLinkProcessor}：Halo 的附件地址默认是相对路径，
     * 而「有没有封面」的判定要求绝对地址，不补全就会出现「上传成功却被判成没封面」的误判。</p>
     */
    private Mono<String> toAbsolutePermalink(Attachment attachment) {
        if (attachment == null) {
            return Mono.empty();
        }
        return attachmentService.getPermalink(attachment)
            .map(uri -> externalLinkProcessor.processLink(uri.toString()))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .switchIfEmpty(Mono.fromSupplier(() -> {
                var status = attachment.getStatus();
                var raw = status == null ? null : status.getPermalink();
                return StringUtils.hasText(raw) ? externalLinkProcessor.processLink(raw).trim() : "";
            }).filter(StringUtils::hasText));
    }

    // ── 附件策略 / 分组 ──

    private Mono<UploadTarget> resolveTarget(DigestConfigDTO config) {
        var policyName = trim(config.getAttachmentPolicyName());
        var groupName = trim(config.getAttachmentGroupName());
        if (StringUtils.hasText(policyName)) {
            return Mono.just(new UploadTarget(policyName, groupName));
        }
        // 未单独配置时复用「应用配置」里的附件策略，与二维码/头像上传保持一致；
        // 连那里也没有就回落默认策略，绝不因为策略配置缺失而让整轮日报发不出去
        return settingFetcher.get("appConfig")
            .map(appConfig -> appConfig.path("appInfo"))
            .map(appInfo -> new UploadTarget(
                orDefault(trim(appInfo.path("policyName").asText("")), DEFAULT_POLICY_NAME),
                StringUtils.hasText(groupName)
                    ? groupName : trim(appInfo.path("fileGroupName").asText(""))))
            .defaultIfEmpty(new UploadTarget(DEFAULT_POLICY_NAME, groupName))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取附件配置失败，使用默认策略 {}：{}",
                    DEFAULT_POLICY_NAME, ex.getMessage());
                return Mono.just(new UploadTarget(DEFAULT_POLICY_NAME, groupName));
            });
    }

    // ── 工具 ──

    private static Flux<DataBuffer> byteArrayToFlux(byte[] bytes) {
        var bufferFactory = new DefaultDataBufferFactory();
        return Flux.just(bytes).map(data -> bufferFactory.wrap(ByteBuffer.wrap(data)));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /** 把异常压成一行可读原因（日志与报错文案都用它）。 */
    private static String describe(Throwable ex) {
        if (ex == null) {
            return "未知原因";
        }
        var message = ex.getMessage();
        return StringUtils.hasText(message)
            ? ex.getClass().getSimpleName() + ": " + message
            : ex.getClass().getSimpleName();
    }

    private record UploadTarget(String policyName, String groupName) {
    }

    private record DownloadedImage(byte[] bytes, String contentType) {
    }
}
