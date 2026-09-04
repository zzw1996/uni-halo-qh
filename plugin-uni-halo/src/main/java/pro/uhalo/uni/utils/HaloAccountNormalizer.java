package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.exception.WxAuthException;

public final class HaloAccountNormalizer {

    private HaloAccountNormalizer() {
    }

    public static String normalizeBindAccount(String account) {
        if (!StringUtils.hasText(account)) {
            return "";
        }
        var trimmed = account.trim();
        if (trimmed.contains("@")) {
            return trimmed.toLowerCase();
        }
        return trimmed;
    }

    public static void validateBindCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new WxAuthException("code 不能为空");
        }
        if (!code.trim().matches("\\d{6}")) {
            throw new WxAuthException("验证码错误");
        }
    }
}
