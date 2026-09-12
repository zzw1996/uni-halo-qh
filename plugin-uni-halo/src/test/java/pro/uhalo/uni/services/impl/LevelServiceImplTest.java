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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LevelServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENID = "openid-123";

    @Mock
    private PointsPersistence persistence;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private LevelServiceImpl levelService;

    private UserPoints account;

    @BeforeEach
    void setUp() {
        account = new UserPoints();
        account.setWxUserId("wx-user-1");
        account.setWxOpenid(OPENID);
        account.setBalance(1000L);
        lenient().when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    private void stubConfig(String json) throws Exception {
        when(settingFetcher.get("levelConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private static final String DEFAULT_CONFIG = """
        {"enabled":true,
         "levels":[
           {"level":1,"minPoints":0,"title":"新手上路"},
           {"level":2,"minPoints":100,"title":"初露锋芒"},
           {"level":3,"minPoints":300,"title":"小有名气"}
         ],
         "titles":[
           {"id":"read_100","name":"百读不厌","type":"total_browse","target":100,"desc":""},
           {"id":"invite_3","name":"星火燎原","type":"invite","target":3,"desc":""}
         ],
         "outfits":[
           {"key":"avatar_gold","name":"金色头像框","type":"avatar_frame","points":200,"durationDays":0,"style":"gold"}
         ]}
        """;

    private static PointsRecord record(String action, int points, String ref) {
        var r = PointsPersistence.newRecord(OPENID, action, points, 1000L, "t", ref,
            PointsServiceImpl.buildDailyEarnKey(OPENID, action, ref), "wx-user-1");
        r.setCreateTime(Instant.now());
        return r;
    }

    @Test
    void getLevelStatusShouldResolveLevelByTotalEarned() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        // 浏览 2 篇(每篇 50 分) + 昨天 1 篇:只算 points 正的累加 100
        var records = List.of(
            record("browse", 50, "post-1"),
            record("browse", 50, "post-2"),
            record("sign_in", 10, "2026-09-06"));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(records));
        when(wxUserService.countByInviterOpenid(OPENID)).thenReturn(Mono.just(3L));

        StepVerifier.create(levelService.getLevelStatus(OPENID))
            .consumeNextWith(status -> {
                assertThat(status.getLevel()).isEqualTo(2);
                assertThat(status.getLevelTitle()).isEqualTo("初露锋芒");
                assertThat(status.getTotalEarned()).isEqualTo(110);
                assertThat(status.getNextMin()).isEqualTo(300);
                assertThat(status.getNextPoints()).isEqualTo(190);
                var read = status.getUnlockedTitles().stream()
                    .filter(t -> t.getId().equals("read_100")).findFirst().orElseThrow();
                assertThat(read.getProgress()).isEqualTo(2);
                assertThat(read.isUnlocked()).isFalse();
                var invite = status.getUnlockedTitles().stream()
                    .filter(t -> t.getId().equals("invite_3")).findFirst().orElseThrow();
                assertThat(invite.isUnlocked()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void wearTitleShouldRejectWhenNotUnlocked() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(List.of()));
        when(wxUserService.countByInviterOpenid(OPENID)).thenReturn(Mono.just(0L));

        StepVerifier.create(levelService.wearTitle(OPENID, "invite_3"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("尚未解锁"))
            .verify();
        verify(persistence, never()).saveAccount(any());
    }

    @Test
    void wearTitleShouldPersistWhenUnlocked() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        var records = List.of(record("browse", 10, "post-1"));
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.listRecordsByOpenid(OPENID)).thenReturn(Mono.just(records));
        when(wxUserService.countByInviterOpenid(OPENID)).thenReturn(Mono.just(3L));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.wearTitle(OPENID, "invite_3"))
            .consumeNextWith(status -> {
                assertThat(account.getWornTitleId()).isEqualTo("invite_3");
                assertThat(status.getWornTitleId()).isEqualTo("invite_3");
            })
            .verifyComplete();
        verify(persistence).saveAccount(any(UserPoints.class));
    }

    @Test
    void buyOutfitShouldDeductPointsAndPersistOwned() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.buyOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getBalance()).isEqualTo(800L);
                assertThat(response.getOwned()).contains("avatar_gold");
                assertThat(response.getMessage()).contains("已获得");
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(PointsRecord.class);
        verify(persistence).appendRecord(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("outfit_avatar_gold");
        assertThat(captor.getValue().getPoints()).isEqualTo(-200);
        assertThat(account.getBalance()).isEqualTo(800L);
    }

    @Test
    void buyOutfitShouldRejectWhenInsufficient() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        account.setBalance(50L);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.buyOutfit(OPENID, "avatar_gold"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("积分不足"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void buyOutfitShouldReturnOwnedWithoutCharge() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        account.setOutfitOwned("[\"avatar_gold\"]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.buyOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getBalance()).isEqualTo(1000L);
                assertThat(response.getMessage()).contains("已拥有");
            })
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void buyOutfitShouldAllowRepurchaseWhenExpired() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        var now = java.time.Instant.now().getEpochSecond();
        account.setBalance(1000L);
        account.setOutfitOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":"
            + (now - 100000) + ",\"expireAt\":" + (now - 100) + "}]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.buyOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getBalance()).isEqualTo(800L);
                assertThat(response.getMessage()).contains("已获得");
            })
            .verifyComplete();
        verify(persistence).appendRecord(any(PointsRecord.class));
    }

    @Test
    void equipOutfitShouldFallbackWhenNotOwned() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.equipOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getAvatarFrame()).isNull();
                assertThat(response.getMessage()).contains("过期");
            })
            .verifyComplete();
        verify(persistence).saveAccount(any(UserPoints.class));
    }

    @Test
    void equipOutfitShouldFallbackWhenExpired() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        // 已拥有但已过期：佩戴应回退脱装扮（而非提示"请先购买"）。
        var now = java.time.Instant.now().getEpochSecond();
        account.setOutfitOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":"
            + (now - 100000) + ",\"expireAt\":" + (now - 100) + "}]");
        account.setActiveAvatarFrame("gold");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.equipOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getAvatarFrame()).isNull();
                assertThat(account.getActiveAvatarFrame()).isNull();
                assertThat(response.getMessage()).contains("过期");
            })
            .verifyComplete();
        verify(persistence).saveAccount(any(UserPoints.class));
    }

    @Test
    void equipOutfitShouldPersistActiveByType() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        account.setOutfitOwned("[\"avatar_gold\"]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(levelService.equipOutfit(OPENID, "avatar_gold"))
            .consumeNextWith(response -> {
                assertThat(response.getAvatarFrame()).isEqualTo("gold");
                assertThat(account.getActiveAvatarFrame()).isEqualTo("gold");
            })
            .verifyComplete();
        verify(persistence).saveAccount(any(UserPoints.class));
    }

    // ==================== 装扮状态下发：到期明细与已下架装扮 ====================
    //
    // 两个都必须下发给前端：限时装扮不显示剩余天数，用户会在到期后以为权益凭空消失；
    // 已下架装扮不单独列出，用户会「买过的东西在列表里消失」，同样只能来投诉。

    @Test
    void getOutfitStatusShouldHideExpiredOutfitFromOwned() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        var now = Instant.now().getEpochSecond();
        account.setOutfitOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":"
            + (now - 100000) + ",\"expireAt\":" + (now - 100) + "}]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.getOutfitStatus(OPENID))
            .consumeNextWith(status -> {
                // 过期项不下发为「已拥有」，用户可重新购买
                assertThat(status.getOwned()).isEmpty();
                assertThat(status.getOwnedDetails()).isEmpty();
                assertThat(status.getItems()).hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void getOutfitStatusShouldExposeExpiryDetailForLimitedOutfit() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        var expireAt = Instant.now().getEpochSecond() + 3 * 86400L;
        account.setOutfitOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":1,\"expireAt\":"
            + expireAt + "}]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.getOutfitStatus(OPENID))
            .consumeNextWith(status -> {
                assertThat(status.getOwned()).containsExactly("avatar_gold");
                assertThat(status.getOwnedDetails()).hasSize(1);
                var detail = status.getOwnedDetails().get(0);
                assertThat(detail.getKey()).isEqualTo("avatar_gold");
                assertThat(detail.isPermanent()).isFalse();
                assertThat(detail.getExpireAt()).isEqualTo(expireAt);
                assertThat(status.getOrphanOwned()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void getOutfitStatusShouldListOrphanOwnedOutfits() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        // DEFAULT_CONFIG 只登记了 avatar_gold；avatar_removed 是站长后来删掉的装扮
        account.setOutfitOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":1,\"expireAt\":null},"
            + "{\"key\":\"avatar_removed\",\"boughtAt\":1,\"expireAt\":null}]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.getOutfitStatus(OPENID))
            .consumeNextWith(status -> {
                assertThat(status.getOwned()).containsExactly("avatar_gold", "avatar_removed");
                assertThat(status.getOrphanOwned()).containsExactly("avatar_removed");
            })
            .verifyComplete();
    }

    @Test
    void getOutfitStatusShouldTreatLegacyKeyArrayAsPermanent() throws Exception {
        stubConfig(DEFAULT_CONFIG);
        // v1.0.96 及之前的旧格式（纯 key 数组）：按永久处理，惰性迁移不主动改写存量数据
        account.setOutfitOwned("[\"avatar_gold\"]");
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(levelService.getOutfitStatus(OPENID))
            .consumeNextWith(status -> {
                assertThat(status.getOwned()).containsExactly("avatar_gold");
                assertThat(status.getOwnedDetails()).hasSize(1);
                assertThat(status.getOwnedDetails().get(0).isPermanent()).isTrue();
                assertThat(status.getOwnedDetails().get(0).getExpireAt()).isNull();
            })
            .verifyComplete();
    }
}
