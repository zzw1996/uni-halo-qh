package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.utils.MpErrors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 公众号图片处理：正文图换微信图床地址、封面换永久素材 media_id。
 *
 * <p><b>为什么必须做这两件事</b>：</p>
 * <ul>
 *   <li>草稿箱会<b>直接过滤正文里的外部图片</b>（官方原文「外部图片 url 将被过滤」），
 *       必须走 {@code media/uploadimg} 换成 {@code mmbiz.qpic.cn} 的地址；
 *       该接口只支持 jpg/png 且小于 1MB，所以要下载 → 转码 → 压到 1MB 以内。</li>
 *   <li>草稿封面的 {@code thumb_media_id} <b>必须是永久素材</b>（{@code material/add_material}），
 *       临时素材会导致 40007。</li>
 * </ul>
 *
 * <p>转码用 JDK 自带的 ImageIO（无新依赖）：能读的格式转成 JPEG；读不了的
 * （webp/heic 等）如实报出原因，由调用方决定顶替方案（生成阶段会换成标题卡片）。</p>
 *
 * <p>缓存：URL → 转好的 JPEG 字节 / mmbiz 地址 / 永久素材 id，均按进程缓存。
 * 生成阶段就会把图片转一遍（预检），推送时命中缓存，不必重复下载与压缩。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MpImageService {

    /**
     * 正文图上限。
     *
     * <p>官方口径是「小于 1MB」，但贴着 1MB 传会被微信前置网关拒（线上实测过 HTTP 412）：
     * 这里留 10% 余量，按 900KB 压。</p>
     */
    public static final int CONTENT_IMAGE_MAX_BYTES = 900 * 1024;

    /** 被网关拒绝（412/413）或报「尺寸超限」时，用更小的口径重试一次 */
    static final int RETRY_IMAGE_MAX_BYTES = 620 * 1024;
    static final int RETRY_WIDTH_LIMIT = 900;

    /** 封面素材上限：官方 10MB，这里收紧到 8MB（留出边界余量） */
    static final int COVER_IMAGE_MAX_BYTES = 8 * 1024 * 1024;

    /** 单张图下载上限：超过就不处理（微信侧也放不下） */
    private static final int DOWNLOAD_MAX_BYTES = 10 * 1024 * 1024;

    /** 正文图最大宽度：再宽也没有意义，只会超 1MB */
    private static final int CONTENT_WIDTH_LIMIT = 1080;

    /** 兜底压缩的目标宽度：压到这个宽度 + 0.5 质量还超 1MB 的图基本不存在 */
    private static final int FORCED_WIDTH_LIMIT = 720;

    private static final Pattern IMG_SRC_PATTERN =
        Pattern.compile("<img[^>]*?src=\"([^\"]+)\"[^>]*>");

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private static final WebClient WEB_CLIENT = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    private static final int CACHE_LIMIT = 200;

    /** URL → 转好的 JPEG 字节（生成阶段预检时写入，推送时复用） */
    private final Map<String, byte[]> convertedCache = new ConcurrentHashMap<>();

    /** URL → mmbiz 地址 */
    private final Map<String, String> contentUrlCache = new ConcurrentHashMap<>();

    /** URL → 永久素材 media_id */
    private final Map<String, String> coverMediaIdCache = new ConcurrentHashMap<>();

    private final MpApiClient apiClient;

    /** 单张正文图的处理结果 */
    public record ImageOutcome(String url, boolean ok, String reason) {
    }

    /** 正文图改写结果 */
    public record RewriteResult(String html, int converted, int skipped, String note,
                                List<ImageOutcome> outcomes) {
    }

    /** 可转换性预检结果（生成阶段用） */
    public record ImageCheck(boolean ok, String reason) {
    }

    /**
     * 生成阶段的预检：这张图能不能变成微信要的格式与体积？
     *
     * <p>转换结果会缓存下来，推送时直接用，不再重复下载与压缩。
     * 不通过时返回具体原因（格式/体积/下载失败），调用方据此换成标题卡片。</p>
     */
    public Mono<ImageCheck> checkConvertible(String url) {
        if (!StringUtils.hasText(url)) {
            return Mono.just(new ImageCheck(false, "没有图片地址"));
        }
        if (isWechatUrl(url)) {
            return Mono.just(new ImageCheck(true, ""));
        }
        return convertedJpeg(url)
            .map(bytes -> new ImageCheck(true, ""))
            .onErrorResume(ex -> Mono.just(new ImageCheck(false, reasonOf(ex))));
    }

    /**
     * 把正文 HTML 里所有 {@code <img src>} 换成微信图床地址。
     *
     * <p>逐张处理、互不影响：某张图失败时保留原地址，并把<b>具体是哪张、为什么</b>
     * 逐条报出来（过去只有一句笼统提示，运维时无从下手）。</p>
     */
    public Mono<RewriteResult> rewriteForWechat(String html, MpConfigDTO config) {
        if (!StringUtils.hasText(html)) {
            return Mono.just(new RewriteResult("", 0, 0, "", List.of()));
        }
        var urls = new LinkedHashMap<String, Boolean>();
        var matcher = IMG_SRC_PATTERN.matcher(html);
        while (matcher.find()) {
            var url = matcher.group(1);
            if (StringUtils.hasText(url) && !isWechatUrl(url)) {
                urls.put(url, Boolean.TRUE);
            }
        }
        if (urls.isEmpty()) {
            return Mono.just(new RewriteResult(html, 0, 0, "", List.of()));
        }
        return Flux.fromIterable(urls.keySet())
            .flatMapSequential(url -> convertAndUploadContentImage(url)
                .map(mmbiz -> new ImageOutcome(url, true, ""))
                .onErrorResume(ex -> Mono.just(new ImageOutcome(url, false, reasonOf(ex)))), 2)
            .collectList()
            .map(outcomes -> {
                var result = html;
                var converted = 0;
                var failures = new ArrayList<String>();
                for (var outcome : outcomes) {
                    var mmbiz = contentUrlCache.get(outcome.url());
                    if (outcome.ok() && StringUtils.hasText(mmbiz)) {
                        result = result.replace("src=\"" + outcome.url() + "\"", "src=\"" + mmbiz + "\"");
                        converted++;
                    } else {
                        failures.add(shortUrl(outcome.url()) + "（" + outcome.reason() + "）");
                    }
                }
                var note = failures.isEmpty() ? ""
                    : failures.size() + " 张图片未换成微信图床（公众号可能不显示）："
                        + String.join("；", failures.subList(0, Math.min(3, failures.size())))
                        + (failures.size() > 3 ? " 等" : "");
                if (!note.isEmpty()) {
                    log.warn("[UniHalo][Mp] 正文图处理：{} 张成功，{} 张失败 → {}", converted,
                        failures.size(), String.join(" | ", failures));
                } else {
                    log.info("[UniHalo][Mp] 正文图处理完成：{} 张全部换成微信图床", converted);
                }
                return new RewriteResult(result, converted, failures.size(), note, outcomes);
            });
    }

    /**
     * 封面 → 永久素材 media_id（草稿箱 thumb_media_id 必须是永久素材）。
     *
     * <p>返回的 media_id 按封面 URL 缓存：同一张封面重复推送不重复上传，不消耗素材配额。</p>
     */
    public Mono<String> ensureCoverMediaId(String coverUrl, String baseName, MpConfigDTO config) {
        if (!StringUtils.hasText(coverUrl)) {
            return Mono.error(new IllegalStateException(
                "该期没有封面图：公众号草稿必须带封面，请重新生成该方向（会画一张标题卡片）"));
        }
        var cached = coverMediaIdCache.get(coverUrl);
        if (StringUtils.hasText(cached)) {
            return Mono.just(cached);
        }
        var filename = safeBaseName(baseName) + ".jpg";
        return convertedJpeg(coverUrl, COVER_IMAGE_MAX_BYTES, 1440)
            .flatMap(jpeg -> apiClient.uploadPermanentImage(jpeg, filename, "image/jpeg"))
            .map(call -> {
                if (!call.ok()) {
                    throw new IllegalStateException("封面上传失败：" + call.error().suggestion()
                        + (StringUtils.hasText(call.error().errmsg())
                        ? "（" + call.error().errmsg() + "）" : ""));
                }
                var mediaId = call.text("media_id");
                if (!StringUtils.hasText(mediaId)) {
                    throw new IllegalStateException("封面上传成功但未返回 media_id，请稍后重试");
                }
                cachePut(coverMediaIdCache, coverUrl, mediaId);
                log.info("[UniHalo][Mp] 封面已上传为永久素材：{} → media_id={}***",
                    shortUrl(coverUrl), mediaId.length() > 6 ? mediaId.substring(0, 6) : mediaId);
                return mediaId;
            });
    }

    /** 自检：上传一张真实尺寸的图片到「图文内图片」接口，验证素材上传权限并拿到可用 URL */
    public Mono<MpApiClient.MpCall> probeContentImageUpload() {
        return apiClient.uploadContentImage(sampleImage(), "uni-halo-probe.png", "image/png");
    }

    /** 自检：上传一张真实尺寸的图片为永久素材，验证封面素材权限 */
    public Mono<MpApiClient.MpCall> probePermanentImageUpload() {
        return apiClient.uploadPermanentImage(sampleImage(), "uni-halo-probe.png", "image/png");
    }

    // ── 内部 ──

    /**
     * 正文图：下载 → 转码压缩 → 上传微信图床。
     *
     * <p>被网关拒绝时（HTTP 412/413，或报图片尺寸超限）自动<b>压得更小重试一次</b>——
     * 这条路径线上真实出现过：贴着 1MB 的图会被微信前置网关回 412，
     * 而现在正文图统一按 900KB 压、重试按 620KB/900px。</p>
     */
    private Mono<String> convertAndUploadContentImage(String url) {
        var cached = contentUrlCache.get(url);
        if (StringUtils.hasText(cached)) {
            return Mono.just(cached);
        }
        var filename = safeBaseName(lastSegment(url)) + ".jpg";
        return convertedJpeg(url)
            .flatMap(jpeg -> uploadContentImage(jpeg, filename))
            .flatMap(call -> {
                if (call.ok() || !MpErrors.worthRetrySmaller(call.error())) {
                    return Mono.just(call);
                }
                log.warn("[UniHalo][Mp] 图片上传被拒（errcode={}），压得更小后重试一次：{}",
                    call.error().errcode(), shortUrl(url));
                return download(url)
                    .map(raw -> {
                        try {
                            return toJpeg(raw, RETRY_IMAGE_MAX_BYTES, RETRY_WIDTH_LIMIT);
                        } catch (Exception ex) {
                            throw new IllegalStateException(reasonOf(ex), ex);
                        }
                    })
                    .flatMap(smaller -> uploadContentImage(smaller, filename));
            })
            .map(call -> {
                if (!call.ok()) {
                    throw new IllegalStateException("微信拒绝：" + call.error().suggestion()
                        + (StringUtils.hasText(call.error().errmsg())
                        ? "（" + call.error().errmsg() + "）" : ""));
                }
                var mmbiz = call.text("url");
                if (!StringUtils.hasText(mmbiz)) {
                    throw new IllegalStateException("微信未返回图片地址");
                }
                cachePut(contentUrlCache, url, mmbiz);
                return mmbiz;
            });
    }

    private Mono<MpApiClient.MpCall> uploadContentImage(byte[] jpeg, String filename) {
        return apiClient.uploadContentImage(jpeg, filename, "image/jpeg");
    }

    /** 下载 + 转码 + 缓存（正文图口径：900KB 以内、宽 ≤1080） */
    private Mono<byte[]> convertedJpeg(String url) {
        return convertedJpeg(url, CONTENT_IMAGE_MAX_BYTES, CONTENT_WIDTH_LIMIT);
    }

    private Mono<byte[]> convertedJpeg(String url, int maxBytes, int widthLimit) {
        var cached = convertedCache.get(url);
        if (cached != null && maxBytes == CONTENT_IMAGE_MAX_BYTES) {
            return Mono.just(cached);
        }
        return download(url)
            .map(bytes -> {
                try {
                    return toJpeg(bytes, maxBytes, widthLimit);
                } catch (Exception ex) {
                    throw new IllegalStateException(reasonOf(ex), ex);
                }
            })
            .doOnNext(jpeg -> {
                if (maxBytes == CONTENT_IMAGE_MAX_BYTES) {
                    cachePut(convertedCache, url, jpeg);
                }
            });
    }

    private Mono<byte[]> download(String url) {
        return WEB_CLIENT.get().uri(url)
            // 部分图床按 UA 拦截空 UA 的请求
            .header("User-Agent", "Mozilla/5.0 (compatible; UniHalo-MpBot/1.0)")
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(DOWNLOAD_TIMEOUT)
            .map(bytes -> {
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException("下载到的内容为空");
                }
                if (bytes.length > DOWNLOAD_MAX_BYTES) {
                    throw new IllegalStateException("原图超过 " + (DOWNLOAD_MAX_BYTES / 1024 / 1024)
                        + "MB");
                }
                return bytes;
            })
            .onErrorMap(ex -> ex instanceof IllegalStateException ? ex
                : new IllegalStateException("下载失败：" + ex.getMessage(), ex));
    }

    /**
     * 转成 JPEG 并压到上限以内（三段阶梯）。
     *
     * <p>① 按宽度上限等比缩放 → 逐档降质量（0.85/0.75/0.65/0.55）；
     * ② 仍超上限 → 逐级缩尺寸（0.85/0.7/0.55）再试；
     * ③ 最后强制兜底：宽度压到 {@value #FORCED_WIDTH_LIMIT} + 质量 0.5。
     * 到这一步还超 1MB 的图实际上不存在——真出现就如实报错，由调用方换卡片。
     * 读不出来的格式（webp/heic）直接抛错，消息里写明原因。</p>
     */
    static byte[] toJpeg(byte[] source, int maxBytes, int widthLimit) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IllegalStateException("格式不受支持（JVM 无法解码，常见于 webp/heic）");
        }
        var current = image;
        if (widthLimit > 0 && current.getWidth() > widthLimit) {
            current = scale(current, (double) widthLimit / current.getWidth());
        }
        for (var quality : new float[] {0.85f, 0.75f, 0.65f, 0.55f}) {
            var encoded = encodeJpeg(current, quality);
            if (encoded.length <= maxBytes) {
                return encoded;
            }
        }
        for (var ratio : new double[] {0.85, 0.7, 0.55}) {
            current = scale(current, ratio);
            for (var quality : new float[] {0.7f, 0.55f}) {
                var encoded = encodeJpeg(current, quality);
                if (encoded.length <= maxBytes) {
                    return encoded;
                }
            }
        }
        var forcedWidth = (int) Math.round(
            (double) Math.min(FORCED_WIDTH_LIMIT, current.getWidth()));
        var forced = forcedWidth < current.getWidth()
            ? scale(current, (double) forcedWidth / current.getWidth()) : current;
        var last = encodeJpeg(forced, 0.5f);
        if (last.length <= maxBytes) {
            return last;
        }
        throw new IllegalStateException("压缩到 " + forcedWidth + "px 宽后仍有 "
            + (last.length / 1024) + "KB（上限 " + (maxBytes / 1024) + "KB）");
    }

    private static BufferedImage scale(BufferedImage source, double ratio) {
        var width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        var height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            applyHints(graphics);
            // JPEG 没有透明通道：先铺白底，避免透明区域变黑
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        var rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = rgb.createGraphics();
            try {
                applyHints(graphics);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JVM 里没有 JPEG 编码器");
        }
        var writer = writers.next();
        try {
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            var output = new ByteArrayOutputStream();
            try (var stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(rgb, null, null), params);
            }
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void applyHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);
    }

    /** 自检用图：600×400 的渐变 PNG（比 1×1 更能验证真实的编码/上传链路） */
    private static byte[] sampleImage() {
        try {
            var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x5B6EE8),
                    600, 400, new Color(0x9A4EE0)));
                graphics.fillRect(0, 0, 600, 400);
                graphics.setColor(Color.WHITE);
                graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 34));
                graphics.drawString("UniHalo self-check", 120, 215);
            } finally {
                graphics.dispose();
            }
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("生成自检图片失败：" + ex.getMessage(), ex);
        }
    }

    /** 失败原因：已经是可读消息就原样返回，否则给类名 */
    static String reasonOf(Throwable ex) {
        if (ex == null) {
            return "未知原因";
        }
        var message = ex.getMessage();
        if (StringUtils.hasText(message)) {
            return message;
        }
        return ex.getClass().getSimpleName();
    }

    private static boolean isWechatUrl(String url) {
        return url.contains("mmbiz.qpic.cn") || url.contains("mmbiz.qlogo.cn");
    }

    /** 日志与提示里只保留文件名，完整 URL 太长会把界面撑爆 */
    static String shortUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "(空)";
        }
        var clean = url.split("[?#]")[0];
        var index = clean.lastIndexOf('/');
        var name = index >= 0 ? clean.substring(index + 1) : clean;
        return StringUtils.hasText(name) ? name : clean;
    }

    private static String lastSegment(String url) {
        return shortUrl(url);
    }

    /** 文件名只保留字母数字与短横线（微信对文件名宽容，但别再引入查询串与中文） */
    private static String safeBaseName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "image";
        }
        var cleaned = raw.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\.[a-z0-9]{1,5}$", "")
            .replaceAll("[^a-z0-9\\-]", "-")
            .replaceAll("-{2,}", "-");
        return cleaned.isEmpty() ? "image" : cleaned;
    }

    private static <K, V> void cachePut(Map<K, V> cache, K key, V value) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, value);
    }
}
