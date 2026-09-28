package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 日报流水线用到的文本/URL 处理，口径与原始 ai-daily-digest 脚本保持一致。
 */
public final class DigestTextUtils {

    /** 站点图标、头像、占位图之类的「伪头图」，命中就跳过 */
    private static final Pattern IMAGE_SKIP_PATTERN = Pattern.compile(
        "(favicon|logo|icon|avatar|gravatar|sprite|placeholder|spacer|pixel\\.|blank\\.|badge|button|/ads?/|shields\\.io)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
        "<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern SLUG_INVALID_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern IMAGE_EXT_PATTERN = Pattern.compile(
        "\\.(jpe?g|png|webp|gif|avif|svg)(?:[?#]|$)", Pattern.CASE_INSENSITIVE);

    private DigestTextUtils() {
    }

    /**
     * 去掉 HTML 标签并还原常见实体。
     *
     * <p>RSS 的 description 常常是转义后的 HTML 片段，直接喂给 AI 会浪费 token，
     * 也会让摘要里混进标签。</p>
     */
    public static String stripHtml(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        var text = TAG_PATTERN.matcher(html).replaceAll("");
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ");
        var matcher = NUMERIC_ENTITY_PATTERN.matcher(text);
        var builder = new StringBuilder();
        while (matcher.find()) {
            var code = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(builder, java.util.regex.Matcher.quoteReplacement(
                String.valueOf((char) code)));
        }
        matcher.appendTail(builder);
        return builder.toString().trim();
    }

    /**
     * 把相对地址补全为绝对地址。
     *
     * <p>空值必须挡在最前面：{@code new URI("")} 会解析成 base 本身，
     * 会把文章页地址当成图片地址存下来。</p>
     */
    public static String absolutizeUrl(String url, String baseUrl) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        var trimmed = url.trim();
        try {
            if (!StringUtils.hasText(baseUrl)) {
                return URI.create(trimmed).toString();
            }
            return URI.create(baseUrl.trim()).resolve(trimmed).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    public static boolean isUsableImageUrl(String url) {
        return StringUtils.hasText(url)
            && (url.startsWith("http://") || url.startsWith("https://"))
            && !IMAGE_SKIP_PATTERN.matcher(url).find();
    }

    /** 从 HTML 片段里挑第一张可用图片（跳过伪头图） */
    public static String extractInlineImage(String rawHtml, String baseUrl) {
        if (!StringUtils.hasText(rawHtml)) {
            return "";
        }
        var matcher = IMG_SRC_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            var candidate = absolutizeUrl(matcher.group(1), baseUrl);
            if (isUsableImageUrl(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    public static String slugify(String value) {
        if (!StringUtils.hasText(value)) {
            return "image";
        }
        var slug = SLUG_INVALID_PATTERN.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        slug = slug.replaceAll("-+$", "");
        return StringUtils.hasText(slug) ? slug : "image";
    }

    /**
     * 从地址推断图片扩展名；推断不出来时返回空串，由调用方按响应类型再判断。
     */
    public static String extensionFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        var matcher = IMAGE_EXT_PATTERN.matcher(url);
        if (!matcher.find()) {
            return "";
        }
        var ext = matcher.group(1).toLowerCase(Locale.ROOT);
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    /** 按响应 Content-Type 推断扩展名，非图片返回空串 */
    public static String extensionFromContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        var normalized = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            case "image/svg+xml" -> "svg";
            default -> "";
        };
    }

    /** 响应体是不是文本/JSON（失效图片常常返回 HTML 错误页，不能当图片存下来） */
    public static boolean isTextualContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        var normalized = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml");
    }

    // ── 去重 ──

    /**
     * 去掉链接里的「不影响内容身份」的部分：协议、www、末尾斜杠、锚点、跟踪参数。
     *
     * <p>同一篇文章被多个源转载时，链接常常只差这些：{@code https://www.x.com/a/}、
     * {@code http://x.com/a?utm_source=rss}、{@code .../a#comments}。不归一化就等于没去重。</p>
     *
     * <p>只丢跟踪参数、保留其它查询参数：像 {@code ?p=123} 这种博客文章 ID 是身份的一部分，
     * 一刀切丢掉查询串会把不同文章误判成同一篇。</p>
     */
    public static String normalizeLink(String link) {
        if (!StringUtils.hasText(link)) {
            return "";
        }
        var value = link.trim();
        try {
            var uri = URI.create(value);
            var host = uri.getHost() == null ? ""
                : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (!StringUtils.hasText(host)) {
                return value.toLowerCase(Locale.ROOT);
            }
            var path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            var query = stripTrackingParams(uri.getRawQuery());
            return host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (Exception ex) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    /** 去重键：有链接用归一化链接，没有链接退到标题（RSS 里两种情况都存在） */
    public static String dedupKey(String link, String title) {
        var normalized = normalizeLink(link);
        if (StringUtils.hasText(normalized)) {
            return "link:" + normalized;
        }
        var cleanTitle = isBlank(title) ? "" : title.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return cleanTitle.isEmpty() ? "" : "title:" + cleanTitle;
    }

    /** 跟踪参数白名单：命中就丢（其余参数原样保留） */
    private static final Pattern TRACKING_PARAM_PATTERN = Pattern.compile(
        "^(utm_.*|ref|ref_src|referrer|source|from|from_source|fbclid|gclid|dclid|msclkid|"
            + "yclid|igshid|spm|scm|mc_cid|mc_eid|_hsenc|_hsmi|vero_id|wt_mc|cmpid|campaign|"
            + "share_token|share_source)$",
        Pattern.CASE_INSENSITIVE);

    private static String stripTrackingParams(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        var kept = new java.util.ArrayList<String>();
        for (var part : rawQuery.split("&")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            var name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            if (TRACKING_PARAM_PATTERN.matcher(name.trim()).matches()) {
                continue;
            }
            kept.add(part);
        }
        return String.join("&", kept);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
