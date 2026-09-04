package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.exception.WxAuthException;
import reactor.test.StepVerifier;

class WxRequestParserTest {

    @Test
    void parseLoginRequestShouldReadFullBody() {
        StepVerifier.create(WxRequestParser.parseLoginRequest(
                "{\"code\":\"081abcXYZ\",\"nickname\":\"小明\",\"avatarUrl\":\"https://example.com/a.png\"}"))
            .expectNextMatches(req -> "081abcXYZ".equals(req.getCode())
                && "小明".equals(req.getNickname())
                && "https://example.com/a.png".equals(req.getAvatarUrl()))
            .verifyComplete();
    }

    @Test
    void parseLoginRequestShouldAllowMissingAvatarUrl() {
        StepVerifier.create(WxRequestParser.parseLoginRequest(
                "{\"code\":\"081abcXYZ\",\"nickname\":\"小明\"}"))
            .expectNextMatches(req -> "081abcXYZ".equals(req.getCode())
                && "小明".equals(req.getNickname())
                && req.getAvatarUrl() == null)
            .verifyComplete();
    }

    @Test
    void parseLoginRequestShouldFailWhenJsonInvalid() {
        StepVerifier.create(WxRequestParser.parseLoginRequest("not-json"))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "请求体格式错误".equals(((WxAuthException) ex).getReason()))
            .verify();
    }
}
