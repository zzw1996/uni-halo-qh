package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import run.halo.app.extension.Metadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WxUserLookupTest {

    @Test
    void resolveForRecordShouldMatchByUserPointsWhenOpenidLookupMisses() {
        var user = new WxUser();
        user.setId("wx-user-1");
        user.setOpenid("openid-real");
        user.setNickname("小莫");
        user.setHaloUserName("halo-mo");

        var account = new UserPoints();
        account.setWxOpenid("openid-stale");
        account.setWxUserId("wx-user-1");

        var lookup = WxUserLookup.from(List.of(user), List.of(account));

        var record = new PointsRecord();
        record.setWxOpenid("openid-stale");
        record.setEarnKey("openid-stale:browse:post-1:2026-07-06");

        var resolved = lookup.resolveForRecord(record);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getNickname()).isEqualTo("小莫");
        assertThat(resolved.getHaloUserName()).isEqualTo("halo-mo");
    }

    @Test
    void resolveShouldMatchByWxUserId() {
        var user = new WxUser();
        user.setId("wx-user-2");
        user.setOpenid("openid-456");
        user.setNickname("测试用户");

        var lookup = WxUserLookup.from(List.of(user), List.of());

        var record = new PointsRecord();
        record.setWxUserId("wx-user-2");

        assertThat(lookup.resolveForRecord(record).getNickname()).isEqualTo("测试用户");
    }

    @Test
    void resolveForRecordShouldPreferEarnKeyWhenWxOpenidIsWrong() {
        var user = new WxUser();
        user.setId("wx-user-3");
        user.setOpenid("openid-correct");
        user.setNickname("正确用户");
        user.setHaloUserName("bound-user");

        var lookup = WxUserLookup.from(List.of(user), List.of());

        var record = new PointsRecord();
        record.setWxOpenid("openid-wrong");
        record.setEarnKey("openid-correct:browse:post-1:2026-07-13");

        var resolved = lookup.resolveForRecord(record);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getNickname()).isEqualTo("正确用户");
        assertThat(resolved.getHaloUserName()).isEqualTo("bound-user");
    }

    @Test
    void resolveForRecordShouldUseMetadataNameWhenIdMissing() {
        var user = new WxUser();
        user.setOpenid("openid-meta");
        user.setNickname("元数据用户");
        var metadata = new Metadata();
        metadata.setName("wx-meta-1");
        user.setMetadata(metadata);

        var lookup = WxUserLookup.from(List.of(user), List.of());

        var record = new PointsRecord();
        record.setWxUserId("wx-meta-1");

        assertThat(lookup.resolveForRecord(record).getNickname()).isEqualTo("元数据用户");
    }

    @Test
    void resolveForRecordShouldUseActivityLinkAsFallback() {
        var user = new WxUser();
        user.setId("wx-user-act");
        user.setOpenid("openid-current");
        user.setNickname("行为用户");
        user.setHaloUserName("act-halo");

        var activity = new UserActivity();
        activity.setWxOpenid("openid-legacy");
        activity.setWxUserId("wx-user-act");

        var lookup = WxUserLookup.from(List.of(user), List.of(), List.of(activity));

        var record = new PointsRecord();
        record.setEarnKey("openid-legacy:comment:post-9:2026-07-13");

        var resolved = lookup.resolveForRecord(record);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getNickname()).isEqualTo("行为用户");
        assertThat(resolved.getHaloUserName()).isEqualTo("act-halo");
    }
}
