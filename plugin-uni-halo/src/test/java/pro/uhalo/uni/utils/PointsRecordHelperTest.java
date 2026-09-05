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

    @Test
    void displayNameShouldPreferNicknameThenHaloUserThenOpenidSuffix() {
        var user = new WxUser();
        user.setHaloUserName("halo-mo");
        user.setOpenid("oAbCdEf123456789");
        assertThat(PointsRecordHelper.resolveDisplayName(null, "oAbCdEf123456789"))
            .isEqualTo("微信用户-456789");
        assertThat(PointsRecordHelper.resolveDisplayName(user, "oAbCdEf123456789"))
            .isEqualTo("halo-mo");
        user.setNickname("小莫");
        assertThat(PointsRecordHelper.resolveDisplayName(user, "oAbCdEf123456789"))
            .isEqualTo("小莫");
    }

    @Test
    void contentTitleShouldFallbackToActionLabel() {
        var record = new PointsRecord();
        record.setAction("sign_in");
        assertThat(PointsRecordHelper.resolveContentTitleOrLabel(record)).isEqualTo("每日签到");
        record.setAction("recharge");
        record.setDescription("积分道具 x1");
        assertThat(PointsRecordHelper.resolveContentTitleOrLabel(record)).isEqualTo("积分道具 x1");
        var empty = new PointsRecord();
        empty.setAction("unknown_action");
        assertThat(PointsRecordHelper.resolveContentTitleOrLabel(empty)).isEqualTo("积分变动");
    }

    @Test
    void formatInstantShouldReturnReadableText() {
        var instant = Instant.parse("2026-09-04T05:06:07Z");
        assertThat(PointsRecordHelper.formatInstant(instant)).isEqualTo("2026-09-04 13:06:07");
        assertThat(PointsRecordHelper.formatInstant(null)).isNull();
    }
}
