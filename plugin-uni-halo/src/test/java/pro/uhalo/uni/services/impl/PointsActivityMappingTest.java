package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行为记录 → 领取积分数量映射测试（同日精确匹配 + 最近一次回退）。
 */
class PointsActivityMappingTest {

    @Test
    void shouldMatchSameDayEarnRecordExactly() {
        var today = Instant.now();
        var activity = activity("openid-1", "browse", "post-1", today);
        var record = record("openid-1", "browse", "post-1", 1, today);

        assertThat(UserActivityServiceImpl.resolveActivityPoints(activity, List.of(record)))
            .isEqualTo(1);
    }

    @Test
    void shouldFallbackToLatestRecordWhenDayDiffers() {
        var today = Instant.now();
        var activity = activity("openid-1", "browse", "post-1", today);
        // 前天与昨天领取的记录（行为时间刷新/跨天领取，无法按同日匹配）
        var old = record("openid-1", "browse", "post-1", 2, today.minusSeconds(172800));
        var latest = record("openid-1", "browse", "post-1", 3, today.minusSeconds(86400));

        assertThat(UserActivityServiceImpl.resolveActivityPoints(activity, List.of(old, latest)))
            .isEqualTo(3);
    }

    @Test
    void shouldReturnNullWhenNeverEarned() {
        var today = Instant.now();
        var activity = activity("openid-1", "upvote", "post-9", today);
        var other = record("openid-1", "upvote", "post-8", 1, today);
        assertThat(UserActivityServiceImpl.resolveActivityPoints(activity, List.of(other))).isNull();
    }

    @Test
    void shouldIgnoreNotPointsableActions() {
        var today = Instant.now();
        var activity = activity("openid-1", "comment", "post-1", today);
        var signIn = new PointsRecord();
        signIn.setWxOpenid("openid-1");
        signIn.setAction("sign_in");
        signIn.setPoints(10);
        signIn.setCreateTime(today);
        signIn.setEarnKey("openid-1:sign_in:" + PointsServiceImpl.recordDate(today));
        assertThat(UserActivityServiceImpl.resolveActivityPoints(activity, List.of(signIn))).isNull();
    }

    private static UserActivity activity(String openid, String type, String articleName, Instant time) {
        var activity = new UserActivity();
        activity.setWxOpenid(openid);
        activity.setType(type);
        activity.setArticleName(articleName);
        activity.setRecordTime(time);
        return activity;
    }

    private static PointsRecord record(String openid, String action, String refName, int points,
        Instant createTime) {
        var record = new PointsRecord();
        record.setEarnKey(PointsServiceImpl.buildDailyEarnKey(
            openid, action, refName, PointsServiceImpl.recordDate(createTime)));
        record.setWxOpenid(openid);
        record.setAction(action);
        record.setRefName(refName);
        record.setPoints(points);
        record.setCreateTime(createTime);
        return record;
    }
}
