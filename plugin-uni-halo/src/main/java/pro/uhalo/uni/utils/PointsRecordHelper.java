package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.enums.PointsAction;
import pro.uhalo.uni.scheme.PointsRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class PointsRecordHelper {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OFFSET_TIME_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private PointsRecordHelper() {
    }

    public static String resolveOpenid(PointsRecord record) {
        var candidates = resolveOpenidCandidates(record);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 返回去重后的 openid 候选：优先流水字段，其次从 earnKey 解析。
     * 两者不一致时都保留，便于后台关联时多路回退。
     */
    public static List<String> resolveOpenidCandidates(PointsRecord record) {
        if (record == null) {
            return List.of();
        }
        var candidates = new ArrayList<String>(2);
        addCandidate(candidates, record.getWxOpenid());
        addCandidate(candidates, parseOpenidFromEarnKey(record.getEarnKey()));
        return candidates;
    }

    public static String parseOpenidFromEarnKey(String earnKey) {
        if (!StringUtils.hasText(earnKey)) {
            return null;
        }
        var idx = earnKey.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        return earnKey.substring(0, idx).trim();
    }

    private static void addCandidate(List<String> candidates, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        var normalized = value.trim();
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    public static String resolveContentTitle(PointsRecord record) {
        if (record == null) {
            return null;
        }
        if (PointsAction.SIGN_IN.value().equals(record.getAction())) {
            return "每日签到";
        }
        var description = StringUtils.hasText(record.getDescription()) ? record.getDescription().trim() : null;
        var refName = StringUtils.hasText(record.getRefName()) ? record.getRefName().trim() : null;
        if (StringUtils.hasText(description) && !description.equals(refName)) {
            return description;
        }
        return refName;
    }

    public static String resolveDetailText(PointsRecord record) {
        if (record == null) {
            return null;
        }
        var contentTitle = resolveContentTitle(record);
        if (PointsAction.SIGN_IN.value().equals(record.getAction())) {
            return StringUtils.hasText(record.getDescription()) ? record.getDescription().trim() : null;
        }
        var refName = StringUtils.hasText(record.getRefName()) ? record.getRefName().trim() : null;
        if (StringUtils.hasText(refName) && !refName.equals(contentTitle)) {
            return refName;
        }
        return StringUtils.hasText(record.getDescription()) ? record.getDescription().trim() : null;
    }

    public static String resolveCreateTime(PointsRecord record) {
        if (record == null) {
            return null;
        }
        var instant = record.getCreateTime();
        if (instant == null && record.getMetadata() != null) {
            instant = record.getMetadata().getCreationTimestamp();
        }
        return instant == null ? null : instant.atZone(ZONE).format(OFFSET_TIME_FMT);
    }
}
