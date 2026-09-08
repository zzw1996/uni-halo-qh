package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyTaskServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENID = "openid-123";

    @Mock
    private PointsPersistence persistence;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private DailyTaskServiceImpl dailyTaskService;

    private UserPoints userPoints;

    @BeforeEach
    void setUp() {
        userPoints = new UserPoints();
        userPoints.setWxUserId("wx-user-1");
        userPoints.setWxOpenid(OPENID);
        userPoints.setBalance(20L);
        userPoints.setConsecutiveDays(0);
        lenient().when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    private void stubDefaultConfig(String json) throws Exception {
        when(settingFetcher.get("dailyTaskConfig"))
            .thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private static PointsRecord todayRecord(String action, String refName, int points) {
        var record = PointsPersistence.newRecord(OPENID, action, points, 20L,
            "测试", refName, PointsServiceImpl.buildDailyEarnKey(OPENID, action, refName), "wx-user-1");
        record.setCreateTime(Instant.now());
        return record;
    }

    private static PointsRecord yesterdayRecord(String action, String refName, int points) {
        var record = todayRecord(action, refName, points);
        record.setCreateTime(Instant.now().minusSeconds(86400));
        return record;
    }

    @Test
    void getDailyTasksShouldCountOnlyTodayRecordsAndMarkClaimed() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":3,"points":10,"enabled":true},
              {"type":"vote","target":1,"points":10,"enabled":true}
            ]}
            """);
        var browseToday = todayRecord("browse", "post-1", 1);
        var browseToday2 = todayRecord("browse", "post-2", 1);
        var browseYesterday = yesterdayRecord("browse", "post-3", 1);
        var claimed = PointsPersistence.newRecord(OPENID, "task", 10, 30L,
            "每日任务·浏览文章", "browse",
            PointsServiceImpl.buildDailyEarnKey(OPENID, "task", "browse"), "wx-user-1");
        claimed.setCreateTime(Instant.now());
        when(persistence.listRecordsByOpenid(OPENID))
            .thenReturn(Mono.just(List.of(browseToday, browseToday2, browseYesterday, claimed)));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(userPoints));

        StepVerifier.create(dailyTaskService.getDailyTasks(OPENID))
            .consumeNextWith(response -> {
                assertThat(response.getDate()).isEqualTo(
                    LocalDate.now(ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE));
                assertThat(response.getBalance()).isEqualTo(20L);
                // 只统计今日 2 条浏览积分 + 今日已领任务奖励 10 分,昨日浏览不计
                assertThat(response.getTodayEarned()).isEqualTo(12L);
                assertThat(response.getItems()).hasSize(2);
                var browse = response.getItems().get(0);
                assertThat(browse.getType()).isEqualTo("browse");
                assertThat(browse.getProgress()).isEqualTo(2);
                assertThat(browse.getTarget()).isEqualTo(3);
                assertThat(browse.isDone()).isFalse();
                assertThat(browse.isClaimed()).isTrue();
                var vote = response.getItems().get(1);
                assertThat(vote.getProgress()).isZero();
                assertThat(vote.isClaimed()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void getDailyTasksShouldFallbackToSeedsWhenConfigMissing() {
        when(settingFetcher.get("dailyTaskConfig")).thenReturn(Mono.empty());
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(List.of()));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(userPoints));

        StepVerifier.create(dailyTaskService.getDailyTasks(OPENID))
            .consumeNextWith(response -> {
                assertThat(response.getItems()).hasSize(5);
                assertThat(response.getItems().get(0).getType()).isEqualTo("sign_in");
                assertThat(response.getItems().get(4).getType()).isEqualTo("vote");
            })
            .verifyComplete();
    }

    @Test
    void claimShouldAwardAndPersistTaskRecordWhenProgressMet() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"vote","target":1,"points":10,"enabled":true}
            ]}
            """);
        var voteRecord = todayRecord("vote", "vote-1", 10);
        when(persistence.findRecordByEarnKey(
            PointsServiceImpl.buildDailyEarnKey(OPENID, "task", "vote")))
            .thenReturn(Mono.empty());
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(List.of(voteRecord)));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(userPoints));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(dailyTaskService.claim(OPENID, "vote"))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isEqualTo(10);
                assertThat(response.getBalance()).isEqualTo(30L);
                assertThat(response.getMessage()).isEqualTo("+10 积分");
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(PointsRecord.class);
        verify(persistence).appendRecord(captor.capture());
        assertThat(captor.getValue().getEarnKey())
            .isEqualTo(PointsServiceImpl.buildDailyEarnKey(OPENID, "task", "vote"));
        assertThat(captor.getValue().getAction()).isEqualTo("task");
        assertThat(captor.getValue().getRefName()).isEqualTo("vote");
        assertThat(captor.getValue().getPoints()).isEqualTo(10);
        assertThat(userPoints.getBalance()).isEqualTo(30L);
    }

    @Test
    void claimShouldRejectWhenProgressNotMet() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":3,"points":10,"enabled":true}
            ]}
            """);
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.listRecordsByOpenid(OPENID))
            .thenReturn(Mono.just(List.of(todayRecord("browse", "post-1", 1))));

        StepVerifier.create(dailyTaskService.claim(OPENID, "browse"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("尚未完成"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void claimShouldBeIdempotentWhenAlreadyClaimed() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":1,"points":10,"enabled":true}
            ]}
            """);
        var existing = PointsPersistence.newRecord(OPENID, "task", 10, 30L,
            "每日任务·浏览文章", "browse",
            PointsServiceImpl.buildDailyEarnKey(OPENID, "task", "browse"), "wx-user-1");
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.just(existing));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(userPoints));

        StepVerifier.create(dailyTaskService.claim(OPENID, "browse"))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isZero();
                assertThat(response.getBalance()).isEqualTo(20L);
                assertThat(response.getMessage()).contains("已领取");
            })
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void claimShouldRejectUnknownType() {
        StepVerifier.create(dailyTaskService.claim(OPENID, "not-a-type"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("不支持的任务类型"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void claimShouldRejectWhenTaskDisabled() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":1,"points":10,"enabled":false}
            ]}
            """);

        StepVerifier.create(dailyTaskService.claim(OPENID, "browse"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("任务不存在或未启用"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void claimShouldSkipDisabledTasksInDailyList() throws Exception {
        stubDefaultConfig("""
            {"enabled":true,"tasks":[
              {"type":"browse","target":1,"points":10,"enabled":true},
              {"type":"comment","target":1,"points":10,"enabled":false}
            ]}
            """);
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(List.of()));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(userPoints));

        StepVerifier.create(dailyTaskService.getDailyTasks(OPENID))
            .consumeNextWith(response -> {
                assertThat(response.getItems()).hasSize(1);
                assertThat(response.getItems().get(0).getType()).isEqualTo("browse");
            })
            .verifyComplete();
    }
}
