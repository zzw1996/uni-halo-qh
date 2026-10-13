package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.impl.AiContentPreferenceService;
import pro.uhalo.uni.services.impl.DigestPublicService;
import pro.uhalo.uni.services.impl.AiContentFeedService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 兑换余额的读取（供前端「点击兑换时先判断够不够」）。
 *
 * <p>这条链路的契约很简单但重要：<b>读不到余额要返回空</b>，而不是返回 0——
 * 前端把「空」当成「未知、不做本地判断」，把「0」当成「确实没积分」。
 * 两者混淆会让已有积分的用户被告知「你的积分不足」，比不做本地判断更糟。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentBalanceLoadTest {

    /** 生成记录：本组用例不关心计数，按空仓库 mock */
    @org.mockito.Mock
    private pro.uhalo.uni.services.impl.AiContentGenerationLogService logService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private PointsPersistence pointsPersistence;
    @Mock
    private ReactiveSettingFetcher settingFetcher;
    @Mock
    private MembershipService membershipService;
    @Mock
    private pro.uhalo.uni.utils.RateLimiter rateLimiter;

    private AiContentEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new AiContentEntitlementService(pointsPersistence, settingFetcher,
            membershipService, rateLimiter, privilegeStub(), logService);
        lenient().when(membershipService.loadMemberExpireAt(anyString())).thenReturn(Mono.empty());
        // 生成次数：本组用例按「0 次剩余、今日 0 次」即可（额度分发另有用例）
        lenient().when(logService.countToday(anyString())).thenReturn(Mono.just(0));
        // 限流器是 mock：默认返回 false 会让兑换在限流那一步就被拦下
        lenient().when(rateLimiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any())).thenReturn(true);
        try {
            lenient().when(settingFetcher.get(AiContentConfigHelper.groupName()))
                .thenReturn(Mono.just(MAPPER.readTree("{\"enabled\":true,\"pointsPrice\":800}")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static UserPoints account(Long balance) {
        var points = new UserPoints();
        points.setWxOpenid("openid-1");
        points.setBalance(balance);
        return points;
    }

    @Test
    void shouldReturnBalanceWhenPresent() {
        when(pointsPersistence.getOrCreateAccount("openid-1"))
            .thenReturn(Mono.just(account(1200L)));

        StepVerifier.create(service.loadPointsBalance("openid-1"))
            .expectNext(1200L)
            .verifyComplete();
    }

    /** 从未赚过积分的新用户：余额字段为 null，按 0 返回（前端会显示「还差 800」） */
    @Test
    void nullBalanceShouldBeZero() {
        when(pointsPersistence.getOrCreateAccount("openid-1"))
            .thenReturn(Mono.just(account(null)));

        StepVerifier.create(service.loadPointsBalance("openid-1"))
            .expectNext(0L)
            .verifyComplete();
    }

    /** 未登录：返回空（前端据此不做本地判断） */
    @Test
    void shouldReturnEmptyWhenNotLoggedIn() {
        StepVerifier.create(service.loadPointsBalance(null)).verifyComplete();
        StepVerifier.create(service.loadPointsBalance("  ")).verifyComplete();
    }

    /** 读取失败：返回空而不是 0——「未知」与「没积分」必须是两种状态 */
    @Test
    void readFailureShouldReturnEmptyNotZero() {
        when(pointsPersistence.getOrCreateAccount(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(service.loadPointsBalance("openid-1")).verifyComplete();
    }

    /** 余额不足时服务端仍要拦（前端判断只是体验优化，权威校验在这里） */
    @Test
    void redeemShouldRejectWithShortfallMessage() {
        when(pointsPersistence.getOrCreateAccount("openid-1"))
            .thenReturn(Mono.just(account(300L)));
        lenient().when(pointsPersistence.appendRecord(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(new pro.uhalo.uni.scheme.PointsRecord()));

        StepVerifier.create(service.redeemWithPoints("openid-1"))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage())
                .contains("积分不足").contains("需要 800").contains("当前 300").contains("还差 500"))
            .verify();
    }
    /** 特权判定桩：默认非特权；豁免行为由 PrivilegeServiceImplTest 单独覆盖 */
    private static pro.uhalo.uni.services.PrivilegeService privilegeStub() {
        return new pro.uhalo.uni.services.PrivilegeService() {
            @Override
            public Mono<Boolean> isPrivileged(String openid) {
                return Mono.just(false);
            }

            @Override
            public Mono<Boolean> isPrivileged(pro.uhalo.uni.scheme.WxUser user,
                java.util.Set<String> roles) {
                return Mono.just(false);
            }

            @Override
            public Mono<java.util.Set<String>> rolesOf(String haloUserName) {
                return Mono.just(java.util.Set.of());
            }

            @Override
            public void evict(String openid) {
            }
        };
    }

}
