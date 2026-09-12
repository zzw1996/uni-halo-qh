package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.PointsEarnResponse;
import pro.uhalo.uni.dto.UserActivityFeedItemDTO;
import pro.uhalo.uni.dto.UserRankItemDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 榜单口径：积分榜按累计获得积分、活跃榜按窗口内获得积分、邀请榜按邀请人数；
 * 动态墙过滤与去重；周榜奖励按上周窗口结算且幂等。
 */
@ExtendWith(MockitoExtension.class)
class RankServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String LEVEL_CONFIG = """
        {"enabled":true,
         "levels":[
           {"level":1,"minPoints":0,"title":"新手上路"},
           {"level":2,"minPoints":100,"title":"初露锋芒"},
           {"level":3,"minPoints":300,"title":"小有名气"}
         ]}
        """;

    private static final String RANK_CONFIG = """
        {"enabled":true,"showScore":true,"enableInviteBoard":true,
         "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":false,
         "weeklyRewardEnabled":true,"weeklyRewardTopN":2,
         "weeklyRewardPoints":60,"weeklyRewardStep":20}
        """;

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private PointsPersistence persistence;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private PointsService pointsService;

    @InjectMocks
    private RankServiceImpl rankService;

    @BeforeEach
    void setUp() {
        lenient().when(pointsService.grantRankReward(anyString(), anyInt(), anyString(), anyString()))
            .thenReturn(Mono.just(PointsEarnResponse.builder()
                .earned(1)
                .balance(1)
                .message("ok")
                .build()));
    }

    private void stubConfigs() throws Exception {
        stubConfigs(LEVEL_CONFIG, RANK_CONFIG);
    }

    private void stubConfigs(String levelJson, String rankJson) throws Exception {
        // 部分用例（如关闭榜单、关闭周榜奖励）不会走到等级配置，故用 lenient 避免严格存根校验报未使用
        lenient().when(settingFetcher.get("levelConfig"))
            .thenReturn(Mono.just(MAPPER.readTree(levelJson)));
        lenient().when(settingFetcher.get("rankConfig")).thenReturn(rankJson == null
            ? Mono.empty()
            : Mono.just(MAPPER.readTree(rankJson)));
    }

    private void stubData(List<WxUser> users, List<PointsRecord> records) {
        stubData(users, records, List.of());
    }

    private void stubData(List<WxUser> users, List<PointsRecord> records, List<UserPoints> accounts) {
        lenient().when(wxUserService.listAllUsers()).thenReturn(Mono.just(users));
        lenient().when(persistence.listAllRecords()).thenReturn(Mono.just(records));
        lenient().when(persistence.listAllAccounts()).thenReturn(Mono.just(accounts));
    }

    private static WxUser wxUser(String openid, String nickname, String inviterOpenid) {
        var user = new WxUser();
        user.setOpenid(openid);
        user.setNickname(nickname);
        user.setAvatarUrl("https://cdn.example.com/" + openid + ".png");
        user.setInviterOpenid(inviterOpenid);
        return user;
    }

    private static PointsRecord record(String openid, int points, Instant createTime) {
        var record = PointsPersistence.newRecord(openid, "browse", points, 1000L, "t", "post-1",
            "earn-" + openid + "-" + points + "-" + createTime.toEpochMilli(), "wx-" + openid);
        record.setCreateTime(createTime);
        return record;
    }

    private static UserActivity activity(String openid, String type, String title, Instant time,
        String excerpt) {
        var activity = new UserActivity();
        activity.setWxOpenid(openid);
        activity.setWxUserId("wx-" + openid);
        activity.setType(type);
        activity.setContentType("post");
        activity.setArticleName("post-" + title);
        activity.setTitle(title);
        activity.setExcerpt(excerpt);
        activity.setRecordTime(time);
        return activity;
    }

    @Test
    void pointsBoardRanksByTotalEarnedAndSkipsZeroScoreUsers() throws Exception {
        stubConfigs();
        stubData(List.of(
                wxUser("u1", "张三", null),
                wxUser("u2", "李四", null),
                wxUser("u3", "王五", null)),
            List.of(
                record("u1", 100, Instant.now()),
                record("u1", 50, Instant.now()),
                record("u2", 300, Instant.now())));

        StepVerifier.create(rankService.topUsers("points", "all", 20, null))
            .assertNext(response -> {
                assertThat(response.getType()).isEqualTo("points");
                assertThat(response.getRange()).isEqualTo("all");
                // 王五没有积分流水，不上榜
                assertThat(response.getTotal()).isEqualTo(2);
                assertThat(response.getItems())
                    .extracting(UserRankItemDTO::getNickname)
                    .containsExactly("李四", "张三");
                assertThat(response.getItems())
                    .extracting(UserRankItemDTO::getRank)
                    .containsExactly(1, 2);
                assertThat(response.getItems())
                    .extracting(UserRankItemDTO::getValue)
                    .containsExactly(300L, 150L);
                // 等级按累计获得积分解出，与身份中心一致
                assertThat(response.getItems().get(0).getLevel()).isEqualTo(3);
                assertThat(response.getItems().get(0).getLevelTitle()).isEqualTo("小有名气");
                assertThat(response.getItems().get(1).getLevel()).isEqualTo(2);
                assertThat(response.getMe()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void activeBoardOnlyCountsPointsEarnedWithinLastSevenDays() throws Exception {
        stubConfigs();
        var now = Instant.now();
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", null)),
            List.of(
                record("u1", 100, now.minus(1, ChronoUnit.DAYS)),
                record("u2", 500, now.minus(10, ChronoUnit.DAYS))));

        StepVerifier.create(rankService.topUsers("active", "week", 20, null))
            .assertNext(response -> {
                assertThat(response.getRange()).isEqualTo("week");
                // 十天前那 500 分不在窗口内，李四本期不上榜
                assertThat(response.getTotal()).isEqualTo(1);
                assertThat(response.getItems()).extracting(UserRankItemDTO::getNickname)
                    .containsExactly("张三");
                assertThat(response.getItems().get(0).getValue()).isEqualTo(100L);
            })
            .verifyComplete();

        // 活跃榜即使被要求 range=all 也应按近 7 日口径返回，不能退化成空榜
        StepVerifier.create(rankService.topUsers("active", "all", 20, null))
            .assertNext(response -> {
                assertThat(response.getRange()).isEqualTo("week");
                assertThat(response.getTotal()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void inviteBoardCountsInviteesAndIgnoresRange() throws Exception {
        stubConfigs();
        stubData(List.of(
            wxUser("u1", "张三", null),
            wxUser("u2", "李四", "u1"),
            wxUser("u3", "王五", "u1")), List.of());

        StepVerifier.create(rankService.topUsers("invite", "week", 20, null))
            .assertNext(response -> {
                assertThat(response.getType()).isEqualTo("invite");
                // 邀请榜是累计关系，不区分区间
                assertThat(response.getRange()).isEqualTo("all");
                assertThat(response.getTotal()).isEqualTo(1);
                assertThat(response.getItems().get(0).getNickname()).isEqualTo("张三");
                assertThat(response.getItems().get(0).getValue()).isEqualTo(2L);
            })
            .verifyComplete();
    }

    @Test
    void meIsReturnedOnlyForLoggedInUser() throws Exception {
        stubConfigs();
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", null)),
            List.of(record("u1", 120, Instant.now())));

        StepVerifier.create(rankService.topUsers("points", "all", 20, "u1"))
            .assertNext(response -> {
                assertThat(response.getMe()).isNotNull();
                assertThat(response.getMe().isJoined()).isTrue();
                assertThat(response.getMe().getRank()).isEqualTo(1);
                assertThat(response.getMe().getValue()).isEqualTo(120L);
            })
            .verifyComplete();

        // 榜单命中缓存，仍能定位未上榜用户的排名状态
        StepVerifier.create(rankService.topUsers("points", "all", 20, "u2"))
            .assertNext(response -> {
                assertThat(response.getMe()).isNotNull();
                assertThat(response.getMe().isJoined()).isFalse();
                assertThat(response.getMe().getRank()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void boardResultIsCachedWithinTtl() throws Exception {
        stubConfigs();
        stubData(List.of(wxUser("u1", "张三", null)), List.of(record("u1", 10, Instant.now())));

        StepVerifier.create(rankService.topUsers("points", "all", 20, null))
            .expectNextCount(1)
            .verifyComplete();
        StepVerifier.create(rankService.topUsers("points", "all", 20, null))
            .expectNextCount(1)
            .verifyComplete();

        // 60 秒内复用同一份聚合结果，不重复全量扫描积分流水
        verify(persistence, times(1)).listAllRecords();
    }

    @Test
    void showScoreDisabledHidesValuesButKeepsRanks() throws Exception {
        stubConfigs(LEVEL_CONFIG, """
            {"enabled":true,"showScore":false,"enableInviteBoard":true,
             "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":false,
             "weeklyRewardEnabled":false}
            """);
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", null)),
            List.of(record("u1", 120, Instant.now()), record("u2", 60, Instant.now())));

        StepVerifier.create(rankService.topUsers("points", "all", 20, "u1"))
            .assertNext(response -> {
                assertThat(response.getItems()).extracting(UserRankItemDTO::getRank)
                    .containsExactly(1, 2);
                assertThat(response.getItems()).extracting(UserRankItemDTO::getValue)
                    .containsExactly(0L, 0L);
                assertThat(response.getMe().isJoined()).isTrue();
                assertThat(response.getMe().getValue()).isZero();
                // 等级与称号不受分值开关影响
                assertThat(response.getItems().get(0).getLevel()).isEqualTo(2);
            })
            .verifyComplete();
    }

    @Test
    void disabledRankConfigReturnsEmptyBoardAndWall() throws Exception {
        stubConfigs(LEVEL_CONFIG, """
            {"enabled":false,"showScore":true,"enableInviteBoard":true,
             "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":false,
             "weeklyRewardEnabled":false}
            """);
        stubData(List.of(wxUser("u1", "张三", null)), List.of(record("u1", 120, Instant.now())));

        StepVerifier.create(rankService.topUsers("points", "all", 20, "u1"))
            .assertNext(response -> {
                assertThat(response.getItems()).isEmpty();
                assertThat(response.getTotal()).isZero();
                assertThat(response.getMe()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void disabledInviteBoardReturnsEmptyBoard() throws Exception {
        stubConfigs(LEVEL_CONFIG, """
            {"enabled":true,"showScore":true,"enableInviteBoard":false,
             "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":false,
             "weeklyRewardEnabled":false}
            """);
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", "u1")), List.of());

        StepVerifier.create(rankService.topUsers("invite", "all", 20, null))
            .assertNext(response -> assertThat(response.getItems()).isEmpty())
            .verifyComplete();
    }

    @Test
    void activityWallFiltersBrowseDedupesSameUserAndSortsByTime() throws Exception {
        stubConfigs();
        var now = Instant.now();
        var activities = List.of(
            activity("u1", "comment", "文章A", now.minus(1, ChronoUnit.HOURS), "写得真好"),
            activity("u2", "upvote", "文章B", now.minus(2, ChronoUnit.HOURS), null),
            activity("u1", "browse", "文章C", now.minus(30, ChronoUnit.MINUTES), null),
            activity("u1", "comment", "文章D", now.minus(5, ChronoUnit.MINUTES), "继续加油"));
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", null)), List.of());
        when(client.listAll(eq(UserActivity.class), any(), any()))
            .thenReturn(Flux.fromIterable(activities));

        StepVerifier.create(rankService.recentActivities(10))
            .assertNext(items -> {
                // 浏览不上墙；同一用户同一动作只保留最近一条
                assertThat(items).extracting(UserActivityFeedItemDTO::getTitle)
                    .containsExactly("文章D");
                assertThat(items.get(0).getActionText()).isEqualTo("评论了");
                assertThat(items.get(0).getNickname()).isEqualTo("张三");
                assertThat(items.get(0).getExcerpt()).isEqualTo("继续加油");
                assertThat(items.get(0).getTargetName()).isEqualTo("post-文章D");
            })
            .verifyComplete();
    }

    @Test
    void activityWallIncludesUpvoteOnlyWhenConfigured() throws Exception {
        stubConfigs(LEVEL_CONFIG, """
            {"enabled":true,"showScore":true,"enableInviteBoard":true,
             "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":true,
             "weeklyRewardEnabled":false}
            """);
        var now = Instant.now();
        stubData(List.of(wxUser("u1", "张三", null), wxUser("u2", "李四", null)), List.of());
        when(client.listAll(eq(UserActivity.class), any(), any()))
            .thenReturn(Flux.fromIterable(List.of(
                activity("u2", "upvote", "文章B", now.minus(2, ChronoUnit.HOURS), null),
                activity("u1", "vote", "投票1", now.minus(3, ChronoUnit.HOURS), null))));

        StepVerifier.create(rankService.recentActivities(10))
            .assertNext(items -> {
                assertThat(items).extracting(UserActivityFeedItemDTO::getActionText)
                    .containsExactly("赞了", "投票了");
            })
            .verifyComplete();
    }

    @Test
    void weeklyRewardSettlesPreviousWeekTopNOnce() throws Exception {
        stubConfigs();
        var lastWeekMid = lastWeekMiddle();
        stubData(List.of(
                wxUser("u1", "张三", null),
                wxUser("u2", "李四", null),
                wxUser("u3", "王五", null),
                wxUser("u4", "赵六", null)),
            List.of(
                record("u1", 150, lastWeekMid),
                record("u2", 100, lastWeekMid),
                record("u3", 50, lastWeekMid),
                record("u4", 900, Instant.now())));

        var expectedWeekKey = expectedPreviousWeekKey();
        StepVerifier.create(rankService.settleWeeklyRewardOnce())
            .assertNext(summary -> assertThat(summary).contains(expectedWeekKey))
            .verifyComplete();

        // 只发前 N 名，第 1 名 60 分、第 2 名递减 20 分
        verify(pointsService).grantRankReward(eq("u1"), eq(60), eq(expectedWeekKey), anyString());
        verify(pointsService).grantRankReward(eq("u2"), eq(40), eq(expectedWeekKey), anyString());
        verify(pointsService, never()).grantRankReward(eq("u3"), anyInt(), anyString(), anyString());
        // 本周获得的积分不计入上周榜
        verify(pointsService, never()).grantRankReward(eq("u4"), anyInt(), anyString(), anyString());

        // 同一 JVM 内重复轮询不重复发放
        StepVerifier.create(rankService.settleWeeklyRewardOnce()).verifyComplete();
        verify(pointsService, times(2)).grantRankReward(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void weeklyRewardSkippedWhenDisabled() throws Exception {
        stubConfigs(LEVEL_CONFIG, """
            {"enabled":true,"showScore":true,"enableInviteBoard":true,
             "wallEnabled":true,"wallLimit":10,"wallIncludeUpvote":false,
             "weeklyRewardEnabled":false}
            """);
        stubData(List.of(wxUser("u1", "张三", null)),
            List.of(record("u1", 150, lastWeekMiddle())));

        StepVerifier.create(rankService.settleWeeklyRewardOnce()).verifyComplete();
        verifyNoInteractions(pointsService);
    }

    /** 上周三 12:00（Asia/Shanghai），保证落在「上一自然周」窗口内 */
    private static Instant lastWeekMiddle() {
        var thisMonday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return thisMonday.minusWeeks(1).plusDays(3).atStartOfDay(ZONE).toInstant();
    }

    /** 独立于实现计算上一自然周的 ISO 周标识，用于校验发放时的幂等键 */
    private static String expectedPreviousWeekKey() {
        var thisMonday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var lastMonday = thisMonday.minusWeeks(1);
        return "%d-W%02d".formatted(lastMonday.get(IsoFields.WEEK_BASED_YEAR),
            lastMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
