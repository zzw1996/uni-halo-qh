package pro.uhalo.uni.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 虚拟支付签名工具，实现与官方文档一致的签名算法：
 *
 * <pre>
 * pay_sig = HMAC-SHA256(key=AppKey,   msg=uri + "&" + post_body)  十六进制小写
 * signature = HMAC-SHA256(key=session_key, msg=post_body)      十六进制小写
 * </pre>
 *
 * 参考：https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/business-capabilities/virtual-payment/person
 */
public final class XPaySigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DEFAULT_URI = "requestVirtualPayment";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OUT_TRADE_NO_PREFIX = "MP";

    private XPaySigner() {
    }

    /**
     * 支付签名 pay_sig：C 端（wx.requestVirtualPayment）uri 固定为 requestVirtualPayment；
     * B 端（/xpay/* 接口）uri 为实际接口路径（如 /xpay/query_order）。
     */
    public static String calcPaySig(String appKey, String uri, String postBody) {
        return hmacSha256Hex(appKey, uri + "&" + postBody);
    }

    /** 用户登录态签名 signature：以 sessionKey 为密钥对 post_body 计算 HMAC-SHA256。 */
    public static String calcSignature(String sessionKey, String postBody) {
        return hmacSha256Hex(sessionKey, postBody);
    }

    /**
     * 生成业务单号 outTradeNo（8-32 位、不以 _ 开头、全局唯一）：
     * MP + 毫秒时间戳 + 4 位随机数，共 23 位。
     */
    public static String nextOutTradeNo() {
        var random = 1000 + RANDOM.nextInt(9000);
        return OUT_TRADE_NO_PREFIX + System.currentTimeMillis() + random;
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
