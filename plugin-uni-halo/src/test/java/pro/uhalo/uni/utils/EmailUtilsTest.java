package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailUtilsTest {

    @Test
    void maskEmailShouldHideLocalPart() {
        assertThat(EmailUtils.maskEmail("demo@example.com")).isEqualTo("d***@example.com");
        assertThat(EmailUtils.maskEmail("a@test.com")).isEqualTo("a***@test.com");
    }

    @Test
    void isValidRegistrationEmailShouldRejectVirtualAndInvalidValues() {
        assertThat(EmailUtils.isValidRegistrationEmail("user@example.com")).isTrue();
        assertThat(EmailUtils.isValidRegistrationEmail("openid@mini.unihalo.local")).isFalse();
        assertThat(EmailUtils.isValidRegistrationEmail("")).isFalse();
        assertThat(EmailUtils.isValidRegistrationEmail("invalid")).isFalse();
    }
}
