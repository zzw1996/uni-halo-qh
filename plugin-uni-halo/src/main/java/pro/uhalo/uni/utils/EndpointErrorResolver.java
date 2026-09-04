package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.exception.WxAuthException;
import run.halo.app.extension.exception.ExtensionException;

public final class EndpointErrorResolver {

    private EndpointErrorResolver() {
    }

    public static String resolveBindHaloAccountByCodeError(Throwable error) {
        var wxReason = findWxAuthReason(error);
        if (StringUtils.hasText(wxReason)) {
            return wxReason;
        }
        if (error instanceof WebClientResponseException webClientError) {
            return mapWebClientError(webClientError);
        }
        if (error instanceof ExtensionException extensionException) {
            return mapExtensionError(extensionException);
        }
        var message = StringUtils.hasText(error.getMessage())
            ? error.getMessage()
            : error.getClass().getSimpleName();
        return "绑定 Halo 账号失败：" + message;
    }

    public static String resolveUnbindHaloAccountError(Throwable error) {
        var wxReason = findWxAuthReason(error);
        if (StringUtils.hasText(wxReason)) {
            return wxReason;
        }
        if (error instanceof ExtensionException extensionException) {
            return mapUnbindExtensionError(extensionException);
        }
        var message = StringUtils.hasText(error.getMessage())
            ? error.getMessage()
            : error.getClass().getSimpleName();
        return "解除 Halo 账号关联失败：" + message;
    }

    public static String resolveSendHaloBindCodeError(Throwable error) {
        var wxReason = findWxAuthReason(error);
        if (StringUtils.hasText(wxReason)) {
            return wxReason;
        }
        if (error instanceof WebClientResponseException webClientError) {
            return mapWebClientError(webClientError);
        }
        if (error instanceof IllegalArgumentException illegalArgumentException
            && StringUtils.hasText(illegalArgumentException.getMessage())) {
            return illegalArgumentException.getMessage();
        }
        var message = StringUtils.hasText(error.getMessage())
            ? error.getMessage()
            : error.getClass().getSimpleName();
        return "发送验证码失败：" + message;
    }

    public static String findWxAuthReason(Throwable error) {
        var current = error;
        while (current != null) {
            if (current instanceof WxAuthException wxAuthException) {
                return wxAuthException.getReason();
            }
            current = current.getCause();
        }
        return null;
    }

    private static String mapWebClientError(WebClientResponseException error) {
        return switch (error.getStatusCode().value()) {
            case 401 -> "Halo API 鉴权失败，请检查插件 personalToken 是否有效";
            case 403 -> "Halo API 权限不足，请为 personalToken 授予用户读取权限";
            case 404 -> "Halo 账号不存在";
            default -> "Halo API 请求失败：HTTP " + error.getStatusCode().value();
        };
    }

    private static String mapExtensionError(ExtensionException error) {
        var reason = error.getReason();
        if (StringUtils.hasText(reason)) {
            if (reason.contains("404") || reason.toLowerCase().contains("not found")) {
                return "Halo 账号不存在";
            }
            return "绑定关系写入失败：" + reason;
        }
        return "绑定关系写入失败，请稍后重试";
    }

    private static String mapUnbindExtensionError(ExtensionException error) {
        var reason = error.getReason();
        if (StringUtils.hasText(reason)) {
            if (reason.contains("404") || reason.toLowerCase().contains("not found")) {
                return "Halo 账号不存在";
            }
            return "解绑关系写入失败：" + reason;
        }
        return "解绑关系写入失败，请稍后重试";
    }
}
