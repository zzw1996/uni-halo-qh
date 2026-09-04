package pro.uhalo.uni.enums;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.exception.WxAuthException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContentTypeTest {

    @Test
    void parseShouldDefaultToPost() {
        assertThat(UserContentType.parse(null)).isEqualTo(UserContentType.POST);
        assertThat(UserContentType.parse("")).isEqualTo(UserContentType.POST);
        assertThat(UserContentType.parse("post")).isEqualTo(UserContentType.POST);
    }

    @Test
    void parseShouldAcceptMoment() {
        assertThat(UserContentType.parse("moment")).isEqualTo(UserContentType.MOMENT);
        assertThat(UserContentType.parse("MOMENT")).isEqualTo(UserContentType.MOMENT);
    }

    @Test
    void parseShouldRejectInvalidValue() {
        assertThatThrownBy(() -> UserContentType.parse("article"))
            .isInstanceOf(WxAuthException.class)
            .hasMessageContaining("contentType");
    }

    @Test
    void counterMeterNameShouldUseCorrectGvk() {
        assertThat(UserContentType.POST.counterMeterName("post-1"))
            .isEqualTo("posts.content.halo.run/post-1");
        assertThat(UserContentType.MOMENT.counterMeterName("moment-1"))
            .isEqualTo("moments.moment.halo.run/moment-1");
    }
}
