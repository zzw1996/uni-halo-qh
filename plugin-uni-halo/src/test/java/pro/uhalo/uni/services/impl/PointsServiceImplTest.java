package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.DrawPrizeDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PointsEvidenceService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointsServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private PointsPersistence persistence;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private PointsEvidenceService pointsEvidenceService;

    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private PointsServiceImpl pointsService;

    private UserPoints userPoints;

    @BeforeEach
    void setUp() {
        userPoints = new UserPoints();
        userPoints.setWxUserId("wx-user-1");
        userPoints.setWxOpenid("openid-123");
        userPoints.setBalance(20L);
        userPoints.setConsecutiveDays(2);
        userPoints.setLastSignInDate(LocalDate.now(ZONE).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE));
        var metadata = new Metadata();
        metadata.setName(PointsPersistence.accountResourceName("openid-123"));
        userPoints.setMetadata(metadata);
        lenient().when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        lenient().when(pointsEvidenceService.evidenceReady(any(), any(), any())).thenReturn(Mono.just(true));
        // 积分商城 mallConfig 未配置时回落旧分组（多数用例只 stub 旧分组即可）
        lenient().when(settingFetcher.get("mallConfig")).thenReturn(Mono.empty());
    }
    @Test
    void earnShouldRejectWhenPointsDisabled() throws Exception {
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(
            MAPPER.readTree("{\"enabled\": false}")));

        StepVerifier.create(pointsService.earn("openid-123", earnRequest("browse", "post-1")))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .extracting(e -> ((WxAuthException) e).getReason())
                .isEqualTo("积分功能未开启"))
            .verify();
    }

    @Test
    void earnShouldBeIdempotentForSameArticle() throws Exception {
        stubEnabledConfig();
        var existing = new PointsRecord();
        existing.setEarnKey(PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1"));
        when(persistence.findRecordByEarnKey(existing.getEarnKey())).thenReturn(Mono.just(existing));
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of(existing)));

        StepVerifier.create(pointsService.earn("openid-123", earnRequest("browse", "post-1")))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isZero();
                assertThat(response.getBalance()).isEqualTo(20L);
                assertThat(response.getEarnedToday()).isTrue();
                assertThat(response.getCapped()).isTrue();
                assertThat(response.getMessage()).contains("已领取");
            })
            .verifyComplete();

        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void earnShouldAwardPointsAndPersistRecord() throws Exception {
        stubEnabledConfig();
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of()))
            .thenReturn(Mono.just(List.of(
                PointsPersistence.newRecord("openid-123", "browse", 1, 21L,
                    "测试文章", "post-1",
                    PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1"), "wx-user-1"))));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(pointsService.earn("openid-123", earnRequest("browse", "post-1")))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isEqualTo(1);
                assertThat(response.getBalance()).isEqualTo(21L);
                assertThat(response.getTodayEarned()).isEqualTo(1);
                assertThat(response.getCapped()).isFalse();
                assertThat(response.getEarnedToday()).isFalse();
                assertThat(response.getMessage()).isEqualTo("+1 积分");
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(PointsRecord.class);
        verify(persistence).appendRecord(captor.capture());
        assertThat(captor.getValue().getEarnKey())
            .isEqualTo(PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1"));
        assertThat(captor.getValue().getPoints()).isEqualTo(1);
    }

    @Test
    void signInShouldApplyConsecutiveBonus() throws Exception {
        stubEnabledConfig();
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(pointsService.signIn("openid-123"))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isEqualTo(15);
                assertThat(response.getBalance()).isEqualTo(35L);
                assertThat(response.getSignInStatus().getConsecutiveDays()).isEqualTo(3);
                assertThat(response.getSignInStatus().isSignedToday()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void getOverviewShouldRestorePersistedBalanceByOpenid() {
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of(
            signInRecord("2026-07-05"),
            signInRecord(LocalDate.now(ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE)))));

        StepVerifier.create(pointsService.getOverview("openid-123"))
            .consumeNextWith(response -> {
                assertThat(response.getBalance()).isEqualTo(20L);
                assertThat(response.getSignInStatus().getConsecutiveDays()).isEqualTo(2);
            })
            .verifyComplete();
    }

    @Test
    void listHistoryShouldRestoreRecordsByOpenidWithoutWxUser() {
        var record = PointsPersistence.newRecord("openid-123", "browse", 1, 21L,
            "文章", "post-1", PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1"), null);
        record.setCreateTime(Instant.now());
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of(record)));

        StepVerifier.create(pointsService.listHistory("openid-123", 0, 10))
            .consumeNextWith(response -> {
                assertThat(response.getTotal()).isEqualTo(1);
                assertThat(response.getItems()).hasSize(1);
                assertThat(response.getItems().get(0).getAction()).isEqualTo("browse");
            })
            .verifyComplete();
    }

    @Test
    void listAllRecordsForAdminShouldResolveNicknameAndHaloUser() {
        var wxUser = new WxUser();
        wxUser.setId("wx-user-1");
        wxUser.setOpenid("openid-123");
        wxUser.setNickname("小莫");
        wxUser.setHaloUserName("halo-mo");

        var record = new PointsRecord();
        record.setEarnKey(PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1"));
        record.setAction("browse");
        record.setPoints(1);
        record.setBalance(21L);
        record.setDescription("测试文章");
        record.setRefName("post-1");
        record.setCreateTime(Instant.now());

        when(persistence.listAllRecords()).thenReturn(Mono.just(List.of(record)));
        when(wxUserService.loadUserLookup())
            .thenReturn(Mono.just(pro.uhalo.uni.utils.WxUserLookup.from(List.of(wxUser), List.of())));

        StepVerifier.create(pointsService.listAllRecordsForAdmin(null, null, 0, 20))
            .consumeNextWith(response -> {
                assertThat(response.getTotal()).isEqualTo(1);
                assertThat(response.getItems()).hasSize(1);
                var item = response.getItems().get(0);
                assertThat(item.getNickname()).isEqualTo("小莫");
                assertThat(item.getHaloUserName()).isEqualTo("halo-mo");
                assertThat(item.getWxOpenid()).isEqualTo("openid-123");
                assertThat(item.getContentTitle()).isEqualTo("测试文章");
            })
            .verifyComplete();
    }

    @Test
    void getEarnStatusShouldReturnEarnedTodayWhenAlreadyClaimed() throws Exception {
        stubEnabledConfig();
        var client = org.mockito.Mockito.mock(ReactiveExtensionClient.class);
        var wxUserService = org.mockito.Mockito.mock(WxUserService.class);
        var persistence = new PointsPersistence(client, wxUserService);
        var service = new PointsServiceImpl(persistence, settingFetcher, wxUserService,
            new PointsEvidenceServiceImpl(client), new RateLimiter());

        var earnKey = PointsServiceImpl.buildDailyEarnKey("openid-123", "browse", "post-1");
        var existing = new PointsRecord();
        existing.setEarnKey(earnKey);
        when(client.listAll(eq(PointsRecord.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.just(existing));

        StepVerifier.create(service.getEarnStatus("openid-123", "browse", "post-1"))
            .consumeNextWith(response -> {
                assertThat(response.isEarnedToday()).isTrue();
                assertThat(response.getEarned()).isZero();
                assertThat(response.getCapped()).isTrue();
                assertThat(response.getMessage()).isEqualTo("该文章今日已领取浏览积分");
            })
            .verifyComplete();
    }

    @Test
    void getEarnStatusShouldReturnAvailablePoints() throws Exception {
        stubEnabledConfig();
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(pointsService.getEarnStatus("openid-123", "browse", "post-1"))
            .consumeNextWith(response -> {
                assertThat(response.isEarnedToday()).isFalse();
                assertThat(response.getEarned()).isEqualTo(1);
                assertThat(response.getCapped()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void buildWeekRecordsShouldReturnSevenDaysEndingToday() {
        var today = LocalDate.now(ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE);
        var yesterday = LocalDate.now(ZONE).minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        var records = PointsServiceImpl.buildWeekRecords(Set.of(yesterday, today), today);
        assertThat(records).hasSize(7);
        assertThat(records.get(6)).isTrue();
    }

    @Test
    void drawShouldDeductCostAndCreditPrize() throws Exception {
        stubDrawConfig(10);
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenReturn(Mono.just(userPoints));

        StepVerifier.create(pointsService.draw("openid-123"))
            .consumeNextWith(res -> {
                assertThat(res.getEarned()).isEqualTo(50);
                assertThat(res.getCost()).isEqualTo(10);
                assertThat(res.getBalance()).isEqualTo(60L);
                assertThat(res.isAlreadyDrawn()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void drawShouldRejectWhenBalanceInsufficient() throws Exception {
        stubDrawConfig(30);
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));

        StepVerifier.create(pointsService.draw("openid-123"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("积分不足"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void newUserGiftShouldBeIdempotent() throws Exception {
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(MAPPER.readTree("""
            {"enabled":true,"rules":{"newUserGift":{"points":100}}}
            """)));
        var existing = new PointsRecord();
        existing.setPoints(100);
        existing.setBalance(120L);
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.just(existing));

        StepVerifier.create(pointsService.claimNewUserGift("openid-123"))
            .consumeNextWith(res -> {
                assertThat(res.getEarned()).isZero();
                assertThat(res.getBalance()).isEqualTo(120L);
                assertThat(res.getMessage()).contains("已领取");
            })
            .verifyComplete();
    }

    @Test
    void pickPrizeShouldOnlyReturnConfiguredPoints() {
        var prizes = List.of(
            DrawPrizeDTO.builder().points(2).weight(1).build(),
            DrawPrizeDTO.builder().points(100).weight(1).build());
        for (int i = 0; i < 200; i++) {
            assertThat(PointsServiceImpl.pickPrize(prizes)).isIn(2, 100);
        }
        assertThat(PointsServiceImpl.pickPrize(List.of())).isZero();
        assertThat(PointsServiceImpl.pickPrize(null)).isZero();
    }

    @Test
    void exchangeShouldDeductPointsForDashboard() throws Exception {
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.just(MAPPER.readTree("{}")));
        userPoints.setBalance(100L);
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"enabled\":true,\"rules\":{\"exchange\":{\"dashboardAdvanced\":"
                + "{\"points\":30,\"dailyMaxCount\":1,\"freeCount\":0}}}}")));
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenReturn(Mono.just(userPoints));

        StepVerifier.create(pointsService.exchange("openid-123", "dashboard_advanced"))
            .consumeNextWith(res -> {
                assertThat(res.getPoints()).isEqualTo(30);
                assertThat(res.getBalance()).isEqualTo(70L);
            })
            .verifyComplete();
    }

    @Test
    void exchangeShouldRejectOfflinePublishTicket() throws Exception {
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.just(MAPPER.readTree("{}")));
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"enabled\":true,\"rules\":{\"exchange\":{\"publishTicket\":"
                + "{\"points\":50,\"dailyMaxCount\":5,\"freeCount\":3}}}}")));

        StepVerifier.create(pointsService.exchange("openid-123", "publish_ticket"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("已下线"))
            .verify();
    }

    @Test
    void exchangeShouldRejectWhenInsufficientPoints() throws Exception {
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.just(MAPPER.readTree("{}")));
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"enabled\":true,\"rules\":{\"exchange\":{\"dashboardAdvanced\":"
                + "{\"points\":50,\"dailyMaxCount\":1,\"freeCount\":0}}}}")));
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(pointsService.exchange("openid-123", "dashboard_advanced"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("积分不足"))
            .verify();
    }

    @Test
    void voteShouldAwardTenPointsWithDefaultRule() throws Exception {
        stubEnabledConfig();
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of()))
            .thenReturn(Mono.just(List.of(
                PointsPersistence.newRecord("openid-123", "vote", 10, 30L,
                    "投票测试", "vote-1",
                    PointsServiceImpl.buildDailyEarnKey("openid-123", "vote", "vote-1"),
                    "wx-user-1"))));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(pointsService.earn("openid-123", earnRequest("vote", "vote-1")))
            .consumeNextWith(response -> {
                assertThat(response.getEarned()).isEqualTo(10);
                assertThat(response.getBalance()).isEqualTo(30L);
                assertThat(response.getTodayEarned()).isEqualTo(10);
                assertThat(response.getMessage()).isEqualTo("+10 积分");
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(PointsRecord.class);
        verify(persistence).appendRecord(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("vote");
        assertThat(captor.getValue().getEarnKey())
            .isEqualTo(PointsServiceImpl.buildDailyEarnKey("openid-123", "vote", "vote-1"));
    }

    private void stubDrawConfig(int cost) throws Exception {
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"enabled\":true,\"rules\":{\"draw\":{\"cost\":" + cost
                + ",\"prizes\":[{\"points\":50,\"weight\":1}]}}}")));
    }

    private PointsRecord signInRecord(String date) {
        var record = new PointsRecord();
        record.setAction("sign_in");
        record.setEarnKey("openid-123:sign_in:" + date);
        return record;
    }

    private void stubEnabledConfig() throws Exception {
        when(settingFetcher.get("pointsConfig")).thenReturn(Mono.just(
            MAPPER.readTree("""
                {
                  "enabled": true,
                  "rules": {
                    "browse": { "points": 1, "dailyMaxCount": 10, "dailyMaxPoints": 10 },
                    "signIn": {
                      "points": 10,
                      "consecutiveBonus": [ { "days": 3, "bonus": 5 } ]
                    }
                  }
                }
                """)));
    }

    private static PointsEarnRequest earnRequest(String action, String articleName) {
        var request = new PointsEarnRequest();
        request.setAction(action);
        request.setArticleName(articleName);
        request.setTitle("测试文章");
        return request;
    }
}
