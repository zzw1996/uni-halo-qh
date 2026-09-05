package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 与官方文档签名示例核对一致的已知答案测试：
 * pay_sig = HMAC-SHA256(AppKey, uri + "&" + post_body)（hex）
 * signature = HMAC-SHA256(sessionKey, post_body)（hex）
 */
class XPaySignerTest {

    private static final String SIGN_DATA = "{\"offerId\":\"123\",\"buyQuantity\":1,\"env\":0,"
        + "\"currencyType\":\"CNY\",\"productId\":\"testproductId\",\"goodsPrice\":10,"
        + "\"outTradeNo\":\"MP17569728000001234\",\"attach\":\"MP17569728000001234\"}";

    @Test
    void paySigShouldMatchOfficialAlgorithm() {
        // 官方：HMAC-SHA256(key=appkey, msg=uri + "&" + post_body).hexdigest()
        var paySig = XPaySigner.calcPaySig("12345", "requestVirtualPayment", SIGN_DATA);
        assertThat(paySig).isEqualTo("1bfe8b0bf1082e4110d82d821ce744482538465ad629e16e77b1f1d82cf3e3de");
    }

    @Test
    void signatureShouldMatchOfficialAlgorithm() {
        // 官方：HMAC-SHA256(key=session_key, msg=post_body).hexdigest()
        var signature = XPaySigner.calcSignature("session_key_example", SIGN_DATA);
        assertThat(signature).isEqualTo("0fd7e7e1ee953c797a0220cc573ae1c0afa326a7e28aa75fca11300cfaac6375");
    }

    @Test
    void paySigShouldUseGivenUriForServerApi() {
        var paySig = XPaySigner.calcPaySig("12345", "/xpay/query_order", SIGN_DATA);
        assertThat(paySig).hasSize(64).isNotEqualTo(XPaySigner.calcPaySig("12345", "requestVirtualPayment", SIGN_DATA));
    }

    @Test
    void nextOutTradeNoShouldFollowWechatFormat() {
        var outTradeNo = XPaySigner.nextOutTradeNo();
        assertThat(outTradeNo).startsWith("MP").hasSize(19).doesNotStartWith("_");
        var another = XPaySigner.nextOutTradeNo();
        assertThat(another).isNotEqualTo(outTradeNo);
    }
}
