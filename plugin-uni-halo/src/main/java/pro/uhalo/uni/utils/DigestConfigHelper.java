package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestConfigDTO;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AI 日报配置解析：设置分组 aiDigestConfig → DigestConfigDTO。
 *
 * <p>与其它 ConfigHelper 保持同样的口径：任何字段缺失/类型不符都退回默认值，
 * 不让一处配置问题把整条流水线打挂。</p>
 */
public final class DigestConfigHelper {

    private static final String GROUP_NAME = "aiDigestConfig";

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 24 * 30;
    private static final int MIN_TOP_N = 1;
    private static final int MAX_TOP_N = 50;
    private static final int MIN_WORDS = 80;
    private static final int MAX_WORDS = 2000;

    private DigestConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    public static DigestConfigDTO defaults() {
        return DigestConfigDTO.builder().build();
    }

    public static DigestConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        return DigestConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            .scheduleEnabled(node.path("scheduleEnabled").asBoolean(true))
            .scheduleTimes(text(node, "scheduleTimes", "07:00"))
            .timezone(text(node, "timezone", "Asia/Shanghai"))
            .aiApiBase(text(node, "aiApiBase", "https://api.deepseek.com/v1"))
            .aiApiKey(text(node, "aiApiKey", ""))
            .aiModel(text(node, "aiModel", "deepseek-chat"))
            .fallbackApiBase(text(node, "fallbackApiBase", ""))
            .fallbackApiKey(text(node, "fallbackApiKey", ""))
            .fallbackModel(text(node, "fallbackModel", ""))
            .hours(clamp(node.path("hours").asInt(48), MIN_HOURS, MAX_HOURS))
            .topN(clamp(node.path("topN").asInt(15), MIN_TOP_N, MAX_TOP_N))
            .words(clamp(node.path("words").asInt(600), MIN_WORDS, MAX_WORDS))
            .language("en".equalsIgnoreCase(text(node, "language", "zh")) ? "en" : "zh")
            .imageEnabled(node.path("imageEnabled").asBoolean(true))
            .attachmentPolicyName(text(node, "attachmentPolicyName", ""))
            .attachmentGroupName(text(node, "attachmentGroupName", ""))
            .publishEnabled(node.path("publishEnabled").asBoolean(true))
            .publishMode(normalizePublishMode(text(node, "publishMode", "per-article")))
            .haloBaseUrl(text(node, "haloBaseUrl", ""))
            .postOwner(text(node, "postOwner", "admin"))
            .postTitleTemplate(text(node, "postTitleTemplate", "AI 日报 · {date}"))
            .postCategories(text(node, "postCategories", ""))
            .postTags(text(node, "postTags", ""))
            .mermaidEnabled(node.path("mermaidEnabled").asBoolean(true))
            .footerNote(text(node, "footerNote", ""))
            .publishWaitSeconds(clamp(node.path("publishWaitSeconds").asInt(600), 60, 3600))
            .build();
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /** 合法的发布方式，非法值一律按逐篇处理 */
    public static final String PUBLISH_MODE_PER_ARTICLE = "per-article";
    public static final String PUBLISH_MODE_DIGEST = "digest";
    public static final String PUBLISH_MODE_BOTH = "both";

    private static String normalizePublishMode(String value) {
        if (!StringUtils.hasText(value)) {
            return PUBLISH_MODE_PER_ARTICLE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "digest", "merged", "single", "combined" -> PUBLISH_MODE_DIGEST;
            case "both", "all" -> PUBLISH_MODE_BOTH;
            default -> PUBLISH_MODE_PER_ARTICLE;
        };
    }

    /** 把「逗号分隔的名称」配置解析成列表（分类、标签共用） */
    public static List<String> splitNames(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var part : value.split("[,，;；]")) {
            if (StringUtils.hasText(part)) {
                names.add(part.trim());
            }
        }
        return names;
    }

    /** 时区解析：填错时退回 Asia/Shanghai，不让一个错别字让定时器整体失效 */
    public static ZoneId zoneOf(String timezone) {        if (StringUtils.hasText(timezone)) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (Exception ignored) {
                // 落到默认时区
            }
        }
        return ZoneId.of(DEFAULT_TIMEZONE);
    }

    /**
     * 解析执行时刻列表。
     *
     * <p>支持 {@code "07:00"}、{@code "07:00,19:00"} 以及 {@code "7:00"} 这类写法，
     * 解析不出来的项直接丢掉；全都不合法时退回默认的 07:00。</p>
     */
    public static List<LocalTime> parseScheduleTimes(String scheduleTimes) {
        var result = new ArrayList<LocalTime>();
        if (StringUtils.hasText(scheduleTimes)) {
            for (var part : scheduleTimes.split("[,，;；\\s]+")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                var normalized = part.trim();
                var matcher = TIME_PATTERN.matcher(normalized);
                if (!matcher.matches()) {
                    continue;
                }
                var hour = Integer.parseInt(matcher.group(1));
                var minute = Integer.parseInt(matcher.group(2));
                if (hour > 23 || minute > 59) {
                    continue;
                }
                var time = LocalTime.of(hour, minute);
                if (!result.contains(time)) {
                    result.add(time);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(LocalTime.of(7, 0));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }
}
