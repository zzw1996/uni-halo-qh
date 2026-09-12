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

        StepVerifier.create(pointsService.listHistory("openid-123", null, 0, 10))
            .consumeNextWith(response -> {
                assertThat(response.getTotal()).isEqualTo(1);
                assertThat(response.getItems()).hasSize(1);
                assertThat(response.getItems().get(0).getAction()).isEqualTo("browse");
                assertThat(response.getTotalPoints()).isEqualTo(1);
            })
            .verifyComplete();
    }

    /**
     * 按动作码过滤明细（抽奖消耗明细页依赖它）。
     *
     * <p>关键点是**过滤发生在分页之前**——否则会出现「筛选后首页为空、后面几页才有记录」
     * 的分页错乱；同时合计必须只统计过滤后的记录。</p>
     */
    @Test
    void listHistoryShouldFilterByActionBeforePagination() {
        var draw1 = PointsPersistence.newRecord("openid-123", "lucky_draw", -20, 980L,
            "积分抽奖 · 定制周边", "定制周边", "k1", null);
        draw1.setCreateTime(Instant.now().minusSeconds(60));
        var browse = PointsPersistence.newRecord("openid-123", "browse", 5, 1000L,
            "浏览文章", "post-1", "k2", null);
        browse.setCreateTime(Instant.now().minusSeconds(30));
        var draw2 = PointsPersistence.newRecord("openid-123", "lucky_draw", -20, 975L,
            "积分抽奖 · 再来一次", "再来一次", "k3", null);
        draw2.setCreateTime(Instant.now());
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of(draw1, browse, draw2)));

        // 第一页：过滤后只有 2 条抽奖记录，非抽奖记录不得占位
        StepVerifier.create(pointsService.listHistory("openid-123", "lucky_draw", 0, 1))
            .consumeNextWith(response -> {
                assertThat(response.getTotal()).isEqualTo(2);
                assertThat(response.getItems()).hasSize(1);
                assertThat(response.getItems().get(0).getAction()).isEqualTo("lucky_draw");
                // 合计只统计过滤后的记录（-20 + -20）
                assertThat(response.getTotalPoints()).isEqualTo(-40);
                assertThat(response.isHasNext()).isTrue();
            })
            .verifyComplete();

        // 第二页：过滤后仍有记录（若过滤发生在分页之后，这里会是空的）
        StepVerifier.create(pointsService.listHistory("openid-123", "lucky_draw", 1, 1))
            .consumeNextWith(response -> {
                assertThat(response.getItems()).hasSize(1);
                assertThat(response.getItems().get(0).getAction()).isEqualTo("lucky_draw");
                assertThat(response.isHasNext()).isFalse();
            })
            .verifyComplete();

        // 不过滤时总数与合计包含全部记录（行为与改动前一致）
        StepVerifier.create(pointsService.listHistory("openid-123", null, 0, 10))
            .consumeNextWith(response -> {
                assertThat(response.getTotal()).isEqualTo(3);
                assertThat(response.getTotalPoints()).isEqualTo(-35);
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

    // ========== 看板高级版：现金直购与积分兑换的当日开通合并计数 ==========
    //
    // 数据看板高级版有两条获取路径：积分兑换写 exchange_dashboard，
    // 虚拟支付道具直购写 purchase_dashboard。exchangeStatus 返回的 todayCount
    // 是前端判定「今日已解锁」的唯一依据（data-visual.vue 据此放开高级功能），
    // 两者必须合并计数，否则现金购买的用户看板不生效。

    /** 现金直购看板后，兑换状态应把当日已开通计为 1，前端据此显示「今日已解锁」。 */
    @Test
    void exchangeStatusShouldCountPurchasedDashboardAsUnlockedToday() throws Exception {
        stubEnabledConfig();
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of(pointsRecord("purchase_dashboard", Instant.now()))));

        StepVerifier.create(pointsService.exchangeStatus("openid-123"))
            .consumeNextWith(res -> assertThat(todayCountOf(res.getItems(), "dashboard_advanced"))
                .isEqualTo(1))
            .verifyComplete();
    }

    /** 回归保护：积分兑换路径的 exchange_dashboard 仍需计入当日开通。 */
    @Test
    void exchangeStatusShouldCountExchangedDashboardAsUnlockedToday() throws Exception {
        stubEnabledConfig();
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of(pointsRecord("exchange_dashboard", Instant.now()))));

        StepVerifier.create(pointsService.exchangeStatus("openid-123"))
            .consumeNextWith(res -> assertThat(todayCountOf(res.getItems(), "dashboard_advanced"))
                .isEqualTo(1))
            .verifyComplete();
    }

    /** 当天已现金开通（dailyMax=1）时，不应再允许消耗积分重复兑换。 */
    @Test
    void exchangeShouldRejectWhenDashboardAlreadyPurchasedToday() throws Exception {
        stubEnabledConfig();
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of(pointsRecord("purchase_dashboard", Instant.now()))));

        StepVerifier.create(pointsService.exchange("openid-123", "dashboard_advanced"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("上限"))
            .verify();

        // 未通过上限校验前不得扣分
        verify(persistence, never()).saveAccount(any());
    }

    /** 昨天的现金直购不应影响今日开通判定。 */
    @Test
    void exchangeStatusShouldNotCountYesterdayPurchase() throws Exception {
        stubEnabledConfig();
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123"))
            .thenReturn(Mono.just(List.of(
                pointsRecord("purchase_dashboard", Instant.now().minusSeconds(86_400)))));

        StepVerifier.create(pointsService.exchangeStatus("openid-123"))
            .consumeNextWith(res -> assertThat(todayCountOf(res.getItems(), "dashboard_advanced"))
                .isZero())
            .verifyComplete();
    }

    // ==================== 券类兑换：扣积分必须真正发券 ====================
    //
    // 资损背景：exchange() 原本只扣积分写流水、从不修改券字段，
    // 站长若在积分商城配置 content_ticket / publish_ticket 兑换项，
    // 用户会「积分被扣、券没到手」。以下用例锁定该防线。

    /** 兑换阅读券：扣积分的同时必须把券加到账户上。 */
    @Test
    void exchangeShouldGrantContentTicket() throws Exception {
        stubMallExchangeItems("content_ticket", "阅读券", 50, 2);
        userPoints.setBalance(100L);
        userPoints.setContentTickets(0);
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(pointsService.exchange("openid-123", "content_ticket"))
            .consumeNextWith(res -> {
                assertThat(res.getPoints()).isEqualTo(50);
                assertThat(res.getBalance()).isEqualTo(50L);
            })
            .verifyComplete();

        // 关键断言：券必须真的发放，否则就是「扣积分什么都没拿到」
        assertThat(userPoints.getContentTickets()).isEqualTo(1);
        verify(persistence).saveAccount(any());
    }

    /** 兑换发布券同样必须发券（修复前 publishTickets 只由现金直购写入，兑换是死路）。 */
    @Test
    void exchangeShouldGrantPublishTicket() throws Exception {
        stubMallExchangeItems("publish_ticket", "发布券", 50, 2);
        userPoints.setBalance(100L);
        userPoints.setPublishTickets(null);
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(pointsService.exchange("openid-123", "publish_ticket"))
            .expectNextCount(1)
            .verifyComplete();

        // 存量账户该字段为 null，必须按 0 兜底后再加，不得抛 NPE
        assertThat(userPoints.getPublishTickets()).isEqualTo(1);
    }

    /** 已有券时应在原数量上累加，而不是覆盖为 1。 */
    @Test
    void exchangeShouldAccumulateExistingTickets() throws Exception {
        stubMallExchangeItems("content_ticket", "阅读券", 50, 5);
        userPoints.setBalance(200L);
        userPoints.setContentTickets(7);
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(pointsService.exchange("openid-123", "content_ticket"))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(userPoints.getContentTickets()).isEqualTo(8);
    }

    /**
     * 幂等流水冲突时不得扣积分、也不得发券。
     *
     * <p>这是「先写流水、后改账户」顺序的价值：并发或重复提交时只有一个请求
     * 能写入流水，另一个必须原样返回且不产生任何账户变更。</p>
     */
    @Test
    void exchangeShouldNotGrantTicketOnDuplicateRecord() throws Exception {
        stubMallExchangeItems("content_ticket", "阅读券", 50, 2);
        userPoints.setBalance(100L);
        userPoints.setContentTickets(3);
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.error(
            new RuntimeException("already exists")));

        StepVerifier.create(pointsService.exchange("openid-123", "content_ticket"))
            .consumeNextWith(res -> {
                assertThat(res.getPoints()).isZero();
                assertThat(res.getBalance()).isEqualTo(100L);
                assertThat(res.getMessage()).contains("重复");
            })
            .verifyComplete();

        // 未拿到幂等位：积分与券都不能变
        assertThat(userPoints.getBalance()).isEqualTo(100L);
        assertThat(userPoints.getContentTickets()).isEqualTo(3);
        verify(persistence, never()).saveAccount(any());
    }

    /** 看板高级版靠流水判定生效，不应改动任何券字段（避免误加券）。 */
    @Test
    void exchangeShouldNotTouchTicketsForDashboard() throws Exception {
        stubEnabledConfig();
        when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        userPoints.setBalance(100L);
        userPoints.setContentTickets(5);
        userPoints.setPublishTickets(5);
        when(persistence.getOrCreateAccount("openid-123")).thenReturn(Mono.just(userPoints));
        when(persistence.listRecordsByOpenid("openid-123")).thenReturn(Mono.just(List.of()));
        when(persistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(persistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(pointsService.exchange("openid-123", "dashboard_advanced"))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(userPoints.getContentTickets()).isEqualTo(5);
        assertThat(userPoints.getPublishTickets()).isEqualTo(5);
        assertThat(userPoints.getBalance()).isEqualTo(70L);
    }

    /** 用 mallConfig.exchangeItems（当前生效分组）配置一个兑换项。 */
    private void stubMallExchangeItems(String key, String name, int points, int dailyMax)
        throws Exception {
        when(settingFetcher.get("mallConfig")).thenReturn(Mono.just(MAPPER.readTree("""
            {
              "exchangeItems": [
                {
                  "key": "%s",
                  "name": "%s",
                  "points": %d,
                  "dailyMaxCount": %d,
                  "description": "测试用兑换项"
                }
              ]
            }
            """.formatted(key, name, points, dailyMax))));
        lenient().when(settingFetcher.get("exchangeConfig")).thenReturn(Mono.empty());
        stubEnabledConfig();
    }

    private static int todayCountOf(List<pro.uhalo.uni.dto.ExchangeItemDTO> items, String key) {
        return items.stream()
            .filter(item -> key.equals(item.getKey()))
            .findFirst()
            .map(pro.uhalo.uni.dto.ExchangeItemDTO::getTodayCount)
            .orElse(-1);
    }

    private static PointsRecord pointsRecord(String action, Instant createTime) {
        var record = new PointsRecord();
        record.setWxOpenid("openid-123");
        record.setAction(action);
        record.setCreateTime(createTime);
        return record;
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
