package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.WxUser;

public final class WxUserIds {

    private WxUserIds() {
    }

    public static String resolveId(WxUser user) {
        if (user == null) {
            return null;
        }
        if (StringUtils.hasText(user.getId())) {
            return user.getId().trim();
        }
        if (user.getMetadata() != null && StringUtils.hasText(user.getMetadata().getName())) {
            return user.getMetadata().getName().trim();
        }
        return null;
    }
}
