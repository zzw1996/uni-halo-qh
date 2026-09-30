package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentRedeemResultDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 付费「AI 定制内容」的权益判定。
 *
 * <p>与内容拦截链路（fail-open 放行）刻意相反，这里<b>fail-closed</b>：
 * 放行的是一篇文章，误放的是一次 AI 调用成本，代价不对称。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentEntitlementServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private PointsPersistence pointsPersistence;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private MembershipService membershipService;

    @Mock
    private RateLimiter rateLimiter;

    private AiContentEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new AiContentEntitlementService(pointsPersistence, settingFetcher,
            membershipService, rateLimiter, privilegeStub());
        stubConfig("{\"enabled\":true}");
        // 会员判定默认「非会员」：会员豁免单独用例里再打开
        lenient().when(membershipService.loadMemberExpireAt(anyString()))
            .thenReturn(Mono.empty());
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class)))
            .thenReturn(true);
    }

    private void stubConfig(String json) {
        try {
            lenient().when(settingFetcher.get(AiContentConfigHelper.groupName()))
                .thenReturn(Mono.just(MAPPER.readTree(json)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void stubExpireAt(Instant expireAt) {
        var account = new UserPoints();
        account.setWxOpenid("openid-1");
        account.setAiContentExpireAt(expireAt);
        account.setBalance(1000L);
        lenient().when(pointsPersistence.getOrCreateAccount(anyString()))
            .thenReturn(Mono.just(account));
        lenient().when(pointsPersistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(pointsPersistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void disabledFeatureShouldReportDisabledAndInactive() {
        stubConfig("{\"enabled\":false}");
        stubExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isEnabled()).isFalse();
                assertThat(status.isActive()).isFalse();
            })
            .verifyComplete();

        // 功能没开时即便账上有权益也不算有效（否则关掉开关也拦不住 AI 调用）
        StepVerifier.create(service.isActive("openid-1"))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void notLoggedInShouldReportInactiveWithoutError() {
        StepVerifier.create(service.getStatus(null))
            .assertNext(status -> {
                assertThat(status.isEnabled()).isTrue();
                assertThat(status.isActive()).isFalse();
                assertThat(status.isExpired()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void neverPurchasedShouldNotLookExpired() {
        stubExpireAt(null);

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isActive()).isFalse();
                // 「从未购买」与「已过期」的引导文案不同，必须分得开
                assertThat(status.isExpired()).isFalse();
                assertThat(status.getExpireAt()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void expiredPurchaseShouldBeMarkedExpired() {
        stubExpireAt(Instant.now().minus(1, ChronoUnit.DAYS));

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isActive()).isFalse();
                assertThat(status.isExpired()).isTrue();
                assertThat(status.getDaysRemaining()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void activePurchaseShouldReportRemainingDays() {
        stubExpireAt(Instant.now().plus(10, ChronoUnit.DAYS));

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isActive()).isTrue();
                assertThat(status.isPermanent()).isFalse();
                assertThat(status.getDaysRemaining()).isBetween(9, 11);
                assertThat(status.getExpireAt()).isNotBlank();
            })
            .verifyComplete();
    }

    @Test
    void permanentPurchaseShouldBeActiveForever() {
        stubExpireAt(AiContentConfigHelper.PERMANENT_EXPIRE_AT);

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isActive()).isTrue();
                assertThat(status.isPermanent()).isTrue();
                assertThat(status.getExpireAt()).isEqualTo("永久");
            })
            .verifyComplete();
    }

    @Test
    void readFailureShouldFailClosed() {
        when(pointsPersistence.getOrCreateAccount(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(service.isActive("openid-1"))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void configReadFailureShouldFailClosed() {
        when(settingFetcher.get(AiContentConfigHelper.groupName()))
            .thenReturn(Mono.error(new IllegalStateException("setting unavailable")));
        stubExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        StepVerifier.create(service.isActive("openid-1"))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void memberShouldBeExemptWhenMemberFreeEnabled() {
        stubExpireAt(null);
        when(membershipService.loadMemberExpireAt("openid-1"))
            .thenReturn(Mono.just(Instant.now().plus(20, ChronoUnit.DAYS)));

        StepVerifier.create(service.isActive("openid-1"))
            .expectNext(true)
            .verifyComplete();

        StepVerifier.create(service.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isActive()).isTrue();
                assertThat(status.isViaMember()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void memberExemptionShouldBeDisabledWhenMemberFreeOff() {
        stubConfig("{\"enabled\":true,\"memberFree\":false}");
        stubExpireAt(null);
        when(membershipService.loadMemberExpireAt("openid-1"))
            .thenReturn(Mono.just(Instant.now().plus(20, ChronoUnit.DAYS)));

        StepVerifier.create(service.isActive("openid-1"))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void redeemShouldDeductPointsAndExtendExpiry() {
        stubConfig("{\"enabled\":true,\"pointsPrice\":200,\"pointsDays\":30}");
        stubExpireAt(Instant.now().plus(10, ChronoUnit.DAYS));

        StepVerifier.create(service.redeemWithPoints("openid-1"))
            .assertNext(result -> {
                assertThat(result.getBalance()).isEqualTo(800L);
                assertThat(result.getDays()).isEqualTo(30);
                assertThat(result.getMessage()).contains("200");
            })
            .verifyComplete();
    }

    @Test
    void redeemShouldRejectWhenInsufficientPoints() {
        stubConfig("{\"enabled\":true,\"pointsPrice\":5000,\"pointsDays\":30}");
        stubExpireAt(null);

        StepVerifier.create(service.redeemWithPoints("openid-1"))
            .expectError(WxAuthException.class)
            .verify();
    }

    @Test
    void redeemShouldRejectWhenChannelClosed() {
        stubConfig("{\"enabled\":true,\"pointsPrice\":0}");
        stubExpireAt(null);

        StepVerifier.create(service.redeemWithPoints("openid-1"))
            .expectError(WxAuthException.class)
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
