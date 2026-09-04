package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.exception.WxAuthException;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointErrorResolverTest {

    @Test
    void shouldUnwrapWxAuthExceptionFromCauseChain() {
        var wrapped = new RuntimeException(new WxAuthException("Halo 用户未绑定邮箱"));
        assertThat(EndpointErrorResolver.resolveSendHaloBindCodeError(wrapped))
            .isEqualTo("Halo 用户未绑定邮箱");
    }

    @Test
    void shouldMapWebClientForbiddenToPermissionMessage() {
        var error = WebClientResponseException.create(403, "Forbidden", null, null, null);
        assertThat(EndpointErrorResolver.resolveSendHaloBindCodeError(error))
            .contains("权限不足");
    }
}
