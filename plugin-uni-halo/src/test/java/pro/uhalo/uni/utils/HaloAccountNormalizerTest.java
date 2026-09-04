package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.exception.WxAuthException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HaloAccountNormalizerTest {

    @Test
    void shouldNormalizeEmailToLowerCase() {
        assertThat(HaloAccountNormalizer.normalizeBindAccount("  User@Example.COM "))
            .isEqualTo("user@example.com");
    }

    @Test
    void shouldValidateSixDigitCode() {
        assertThatThrownBy(() -> HaloAccountNormalizer.validateBindCode("12345"))
            .isInstanceOf(WxAuthException.class)
            .extracting(ex -> ((WxAuthException) ex).getReason())
            .isEqualTo("验证码错误");
    }
}
