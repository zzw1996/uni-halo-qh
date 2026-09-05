package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PointsEvidenceServiceImplTest {

    @Test
    void buildActivityKeyShouldMatchUserActivityFormat() {
        assertThat(PointsEvidenceServiceImpl.buildActivityKey("openid1", "browse", "post-1"))
            .isEqualTo("openid1\u0001browse\u0001post-1");
        assertThat(PointsEvidenceServiceImpl.buildActivityKey("openid1", "upvote", "post-2"))
            .isNotEqualTo("openid1\u0001browse\u0001post-2");
    }

    @Test
    void isAfterStartOfTodayShouldRejectYesterday() {
        assertThat(PointsEvidenceServiceImpl.isAfterStartOfToday(Instant.now())).isTrue();
        assertThat(PointsEvidenceServiceImpl.isAfterStartOfToday(
            Instant.now().minusSeconds(86400))).isFalse();
        assertThat(PointsEvidenceServiceImpl.isAfterStartOfToday(null)).isFalse();
    }
}
