package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import run.halo.app.extension.Metadata;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PointsRecordHelperTest {

    @Test
    void resolveOpenidShouldParseFromEarnKeyWhenFieldMissing() {
        var record = new PointsRecord();
        record.setEarnKey("openid-abc:browse:post-1:2026-07-06");

        assertThat(PointsRecordHelper.resolveOpenid(record)).isEqualTo("openid-abc");
    }

    @Test
    void resolveOpenidCandidatesShouldKeepBothWhenInconsistent() {
        var record = new PointsRecord();
        record.setWxOpenid("openid-field");
        record.setEarnKey("openid-earn:browse:post-1:2026-07-13");

        assertThat(PointsRecordHelper.resolveOpenidCandidates(record))
            .containsExactly("openid-field", "openid-earn");
    }

    @Test
    void resolveContentTitleShouldPreferArticleTitle() {
        var record = new PointsRecord();
        record.setAction("browse");
        record.setDescription("Spring Boot 入门");
        record.setRefName("post-1");

        assertThat(PointsRecordHelper.resolveContentTitle(record)).isEqualTo("Spring Boot 入门");
        assertThat(PointsRecordHelper.resolveDetailText(record)).isEqualTo("post-1");
    }

    @Test
    void resolveCreateTimeShouldFallbackToMetadataTimestamp() {
        var record = new PointsRecord();
        var metadata = new Metadata();
        metadata.setCreationTimestamp(Instant.parse("2026-07-06T06:30:00Z"));
        record.setMetadata(metadata);

        assertThat(PointsRecordHelper.resolveCreateTime(record)).contains("2026-07-06");
    }
}
