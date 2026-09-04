package pro.uhalo.uni.exception;

/**
 * 微信登录 / 配置相关业务异常，由接口层统一转为 400 + {@code { "error": "..." }}。
 */
public class WxAuthException extends RuntimeException {

    public WxAuthException(String reason) {
        super(reason);
    }

    public String getReason() {
        return getMessage();
    }
}
