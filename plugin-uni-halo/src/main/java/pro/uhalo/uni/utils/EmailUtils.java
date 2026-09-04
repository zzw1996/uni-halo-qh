package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;

public final class EmailUtils {

    private EmailUtils() {
    }

    public static boolean isValidRegistrationEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return false;
        }
        var normalized = email.trim();
        if (normalized.endsWith(Constants.MINI_COMMENT_EMAIL_SUFFIX)) {
            return false;
        }
        var atIndex = normalized.indexOf('@');
        return atIndex > 0 && atIndex < normalized.length() - 1;
    }

    public static String maskEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return "";
        }
        var normalized = email.trim();
        var atIndex = normalized.indexOf('@');
        if (atIndex <= 0) {
            return normalized;
        }
        var localPart = normalized.substring(0, atIndex);
        var domainPart = normalized.substring(atIndex);
        if (localPart.length() == 1) {
            return localPart + "***" + domainPart;
        }
        return localPart.charAt(0) + "***" + domainPart;
    }
}
