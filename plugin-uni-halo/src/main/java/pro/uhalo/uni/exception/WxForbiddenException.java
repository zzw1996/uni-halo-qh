package pro.uhalo.uni.exception;

/**
 * 无权操作资源时抛出，由接口层统一转为 403 + {@code { "error": "..." }}。
 */
public class WxForbiddenException extends RuntimeException {

    public WxForbiddenException(String reason) {
        super(reason);
    }

    public String getReason() {
        return getMessage();
    }
}
