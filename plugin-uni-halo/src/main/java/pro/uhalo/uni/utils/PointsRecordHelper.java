package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.enums.PointsAction;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.WxUser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class PointsRecordHelper {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OFFSET_TIME_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter LOCAL_TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PointsRecordHelper() {
    }

    /**
     * 格式化为本地可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai），null 安全。
     */
    public static String formatInstant(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).format(LOCAL_TIME_FMT);
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

    /**
     * 解析展示用用户名：昵称 → Halo 用户名 → 「微信用户-xxxxxx」（openid 后 6 位），
     * 保证后台列表不出现无法区分的裸"微信用户"。
     */
    public static String resolveDisplayName(WxUser user, String openid) {
        if (user != null && StringUtils.hasText(user.getNickname())) {
            return user.getNickname().trim();
        }
        if (user != null && StringUtils.hasText(user.getHaloUserName())) {
            return user.getHaloUserName().trim();
        }
        if (StringUtils.hasText(openid)) {
            var normalized = openid.trim();
            return "微信用户-" + normalized.substring(Math.max(0, normalized.length() - 6));
        }
        return null;
    }

    /**
     * 关联内容兜底：文章标题/描述 → 引用名 → 动作标签（每日签到/虚拟支付充值等），不再出现空白。
     */
    public static String resolveContentTitleOrLabel(PointsRecord record) {
        var title = resolveContentTitle(record);
        if (StringUtils.hasText(title)) {
            return title;
        }
        if (record == null || !StringUtils.hasText(record.getAction())) {
            return "积分变动";
        }
        return PointsAction.labelOf(record.getAction());
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

    public static Instant resolveCreateInstant(PointsRecord record) {
        if (record == null) {
            return null;
        }
        var instant = record.getCreateTime();
        if (instant == null && record.getMetadata() != null) {
            instant = record.getMetadata().getCreationTimestamp();
        }
        return instant;
    }

    public static String resolveCreateTime(PointsRecord record) {
        var instant = resolveCreateInstant(record);
        return instant == null ? null : instant.atZone(ZONE).format(OFFSET_TIME_FMT);
    }
}
