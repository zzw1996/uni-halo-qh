package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WxAutoUsernameGeneratorTest {

    @Test
    void formatShouldProduceWxPrefixWithSixDigits() {
        assertThat(WxAutoUsernameGenerator.format(482913)).isEqualTo("wx482913");
        assertThat(WxAutoUsernameGenerator.format(0)).isEqualTo("wx000000");
        assertThat(WxAutoUsernameGenerator.format(1_000_001)).isEqualTo("wx000001");
    }

    @Test
    void deriveSeedShouldBeStableForSameOpenid() {
        assertThat(WxAutoUsernameGenerator.deriveSeed("openid-123"))
            .isEqualTo(WxAutoUsernameGenerator.deriveSeed("openid-123"));
    }

    @Test
    void candidateFirstAttemptShouldMatchFormattedSeed() {
        var openid = "openid-abc";
        assertThat(WxAutoUsernameGenerator.candidate(openid, 0))
            .isEqualTo(WxAutoUsernameGenerator.format(WxAutoUsernameGenerator.deriveSeed(openid)));
    }

    @Test
    void isAutoCreatedFormatShouldMatchNewAndLegacyUsernames() {
        assertThat(WxAutoUsernameGenerator.isAutoCreatedFormat("wx482913")).isTrue();
        assertThat(WxAutoUsernameGenerator.isAutoCreatedFormat("wx_openid-123")).isTrue();
        assertThat(WxAutoUsernameGenerator.isAutoCreatedFormat("halo-admin")).isFalse();
    }
}
