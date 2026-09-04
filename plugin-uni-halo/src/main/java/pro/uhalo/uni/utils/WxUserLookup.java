package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WxUserLookup {

    private final Map<String, WxUser> byOpenid;
    private final Map<String, WxUser> byId;
    private final Map<String, String> openidToUserId;

    private WxUserLookup(Map<String, WxUser> byOpenid, Map<String, WxUser> byId,
        Map<String, String> openidToUserId) {
        this.byOpenid = byOpenid;
        this.byId = byId;
        this.openidToUserId = openidToUserId;
    }

    public static WxUserLookup from(List<WxUser> users, List<UserPoints> accounts) {
        return from(users, accounts, List.of());
    }

    public static WxUserLookup from(List<WxUser> users, List<UserPoints> accounts,
        List<UserActivity> activities) {
        var byOpenid = new HashMap<String, WxUser>();
        var byId = new HashMap<String, WxUser>();
        var openidToUserId = new HashMap<String, String>();

        for (var user : users) {
            indexUser(user, byOpenid, byId, openidToUserId);
        }
        if (accounts != null) {
            for (var account : accounts) {
                rememberOpenidUserId(openidToUserId, account.getWxOpenid(), account.getWxUserId());
            }
        }
        if (activities != null) {
            for (var activity : activities) {
                rememberOpenidUserId(openidToUserId, activity.getWxOpenid(), activity.getWxUserId());
            }
        }
        return new WxUserLookup(byOpenid, byId, openidToUserId);
    }

    public WxUser resolve(String openid, String wxUserId) {
        if (StringUtils.hasText(wxUserId)) {
            var user = byId.get(normalize(wxUserId));
            if (user != null) {
                return user;
            }
        }
        if (StringUtils.hasText(openid)) {
            var user = byOpenid.get(normalize(openid));
            if (user != null) {
                return user;
            }
            var mappedUserId = openidToUserId.get(normalize(openid));
            if (StringUtils.hasText(mappedUserId)) {
                return byId.get(normalize(mappedUserId));
            }
        }
        return null;
    }

    public WxUser resolveForRecord(PointsRecord record) {
        if (record == null) {
            return null;
        }
        if (StringUtils.hasText(record.getWxUserId())) {
            var byRecordId = byId.get(normalize(record.getWxUserId()));
            if (byRecordId != null) {
                return byRecordId;
            }
        }
        for (var openid : PointsRecordHelper.resolveOpenidCandidates(record)) {
            var mappedUserId = openidToUserId.get(normalize(openid));
            var user = resolve(openid, mappedUserId);
            if (user != null) {
                return user;
            }
        }
        return null;
    }

    private static void indexUser(WxUser user, Map<String, WxUser> byOpenid, Map<String, WxUser> byId,
        Map<String, String> openidToUserId) {
        if (user == null) {
            return;
        }
        var userId = WxUserIds.resolveId(user);
        if (StringUtils.hasText(userId)) {
            byId.putIfAbsent(normalize(userId), user);
        }
        if (StringUtils.hasText(user.getOpenid())) {
            byOpenid.putIfAbsent(normalize(user.getOpenid()), user);
            rememberOpenidUserId(openidToUserId, user.getOpenid(), userId);
        }
    }

    private static void rememberOpenidUserId(Map<String, String> openidToUserId, String openid,
        String wxUserId) {
        if (StringUtils.hasText(openid) && StringUtils.hasText(wxUserId)) {
            openidToUserId.putIfAbsent(normalize(openid), normalize(wxUserId));
        }
    }

    private static String normalize(String value) {
        return value.trim();
    }
}
