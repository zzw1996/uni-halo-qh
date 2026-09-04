package pro.uhalo.uni.infra;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.exception.WxAuthException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBindCodeBackendTest {

    private final InMemoryBindCodeBackend backend = new InMemoryBindCodeBackend();

    @Test
    void shouldReturnNullWhenCodeNeverSent() {
        assertThat(backend.getCode("openid", "lxfzzw")).isNull();
    }

    @Test
    void shouldRejectWrongCodeWithoutDeleting() {
        backend.saveCode("openid", "lxfzzw", "123456", "{\"haloUserName\":\"lxfzzw\"}");
        assertThat(backend.getCode("openid", "lxfzzw")).startsWith("123456|");
        assertThatThrownBy(() -> {
            var raw = backend.getCode("openid", "lxfzzw");
            if (!raw.startsWith("000000|")) {
                throw new WxAuthException("验证码错误");
            }
        }).isInstanceOf(WxAuthException.class)
            .extracting(ex -> ((WxAuthException) ex).getReason())
            .isEqualTo("验证码错误");
        assertThat(backend.getCode("openid", "lxfzzw")).startsWith("123456|");
    }

    @Test
    void shouldDeleteCodeAfterSuccessfulConsume() {
        backend.saveCode("openid", "lxfzzw", "123456", "{\"haloUserName\":\"lxfzzw\"}");
        var raw = backend.getCode("openid", "lxfzzw");
        backend.deleteCode("openid", "lxfzzw");
        assertThat(raw).startsWith("123456|");
        assertThat(backend.getCode("openid", "lxfzzw")).isNull();
    }
}
