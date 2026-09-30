package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 订阅额度：免费额度、积分解锁额度、会员上限，以及「超出额度的分类被暂停」。
 *
 * <p>这里算错的后果是「用户付了积分却订不上」或「免费用户能无限订」，
 * 所以每个分支都单独钉住。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionQuotaServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENID = "openid-quota";

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private PointsPersistence pointsPersistence;

    /** 特权判定桩：默认非特权（「管理员不限量」由专门用例覆盖） */
    @Mock
    private pro.uhalo.uni.services.PrivilegeService privilegeService;

    @InjectMocks
    private SubscriptionQuotaService service;

    @BeforeEach
    void setUp() {
        stubConfig("""
            {"enabled":true,"freeQuota":5,"quotaPrice":50,"maxQuota":10,"memberMaxQuota":30}
            """);
    }

    @org.junit.jupiter.api.BeforeEach
    void stubNonPrivileged() {
        lenient().when(privilegeService.isPrivileged(any(String.class)))
            .thenReturn(Mono.just(false));
    }

    private void stubConfig(String json) {
        try {
            lenient().when(settingFetcher.get("subscriptionConfig"))
                .thenReturn(Mono.just(MAPPER.readTree(json)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void stubAccount(Integer quota, Long balance, Instant memberExpireAt) {
        var account = new UserPoints();
        account.setWxOpenid(OPENID);
        account.setSubscribeQuota(quota);
        account.setBalance(balance);
        account.setMemberExpireAt(memberExpireAt);
        lenient().when(pointsPersistence.findAccountByOpenid(OPENID)).thenReturn(Mono.just(account));
    }

    @Test
    void freeUserShouldGetTheFreeQuota() {
        stubAccount(null, 120L, null);

        StepVerifier.create(service.limitsOf(OPENID, List.of("a", "b")))
            .assertNext(limits -> {
                assertThat(limits.getLimit()).isEqualTo(5);
                assertThat(limits.getFreeQuota()).isEqualTo(5);
                assertThat(limits.getPurchasedQuota()).isZero();
                assertThat(limits.isMemberActive()).isFalse();
                assertThat(limits.isPurchaseEnabled()).isTrue();
                assertThat(limits.isCanPurchase()).isTrue();
                assertThat(limits.getPointsBalance()).isEqualTo(120L);
                assertThat(limits.getPausedCategories()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void purchasedQuotaShouldRaiseTheLimit() {
        stubAccount(3, 10L, null);

        StepVerifier.create(service.limitsOf(OPENID, List.of("a", "b", "c", "d", "e", "f")))
            .assertNext(limits -> {
                assertThat(limits.getLimit()).isEqualTo(8);
                assertThat(limits.getPurchasedQuota()).isEqualTo(3);
                assertThat(limits.getUsed()).isEqualTo(6);
                assertThat(limits.getPausedCategories()).isEmpty();
            })
            .verifyComplete();
    }

    /**
     * 会员到期后的关键行为：记录与顺序都保留，只是超出额度的部分不进生效集合。
     *
     * <p>这是「买一次会员不能等于永久权益」的落点，也是发现页要给「已暂停」标记的原因。</p>
     */
    @Test
    void expiredMemberShouldPauseCategoriesBeyondTheQuota() {
        stubAccount(null, 0L, Instant.now().minus(1, ChronoUnit.DAYS));
        var own = List.of("a", "b", "c", "d", "e", "f", "g");

        StepVerifier.create(service.limitsOf(OPENID, own))
            .assertNext(limits -> {
                assertThat(limits.isMemberActive()).isFalse();
                assertThat(limits.getLimit()).isEqualTo(5);
                // 第 6、7 个被暂停：记录还在、顺序还在，续费即恢复
                assertThat(limits.getPausedCategories()).containsExactly("f", "g");
            })
            .verifyComplete();
    }

    @Test
    void activeMemberShouldGetTheMemberLimit() {
        stubAccount(null, 0L, Instant.now().plus(10, ChronoUnit.DAYS));

        StepVerifier.create(service.limitsOf(OPENID, List.of("a", "b", "c", "d", "e", "f")))
            .assertNext(limits -> {
                assertThat(limits.isMemberActive()).isTrue();
                assertThat(limits.getLimit()).isEqualTo(30);
                assertThat(limits.getMemberExpireAt()).isNotBlank();
                assertThat(limits.getPausedCategories()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void purchaseShouldBeUnavailableWhenDisabledOrFullyUnlocked() {
        stubConfig("{\"enabled\":false,\"freeQuota\":5,\"maxQuota\":10}");
        stubAccount(10, 0L, null);

        StepVerifier.create(service.limitsOf(OPENID, List.of()))
            .assertNext(limits -> {
                assertThat(limits.isPurchaseEnabled()).isFalse();
                assertThat(limits.isCanPurchase()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void anonymousVisitorShouldGetDefaultsWithoutTouchingTheDatabase() {
        StepVerifier.create(service.limitsOf(null, List.of()))
            .assertNext(limits -> {
                assertThat(limits.getLimit()).isEqualTo(5);
                assertThat(limits.isMemberActive()).isFalse();
                assertThat(limits.isPurchaseEnabled()).isTrue();
                assertThat(limits.getPointsBalance()).isZero();
            })
            .verifyComplete();
    }

    @Test
    void storedQuotaBeyondConfigCeilingShouldBeClamped() {
        // 后台把上限从 20 调到 10 之后，历史账户可能残留更大的值：不能让它继续生效
        stubAccount(20, 0L, null);

        StepVerifier.create(service.limitOf(OPENID))
            .expectNext(15)
            .verifyComplete();
    }

    /** 推送这类「一次过所有用户」的场景：一次批量查完，逐个用户的口径与单查一致 */
    @Test
    void batchLimitsShouldMatchSingleLookup() {
        var memberOpenid = "openid-member";
        var member = new UserPoints();
        member.setWxOpenid(memberOpenid);
        member.setMemberExpireAt(Instant.now().plus(3, ChronoUnit.DAYS));
        var buyer = new UserPoints();
        buyer.setWxOpenid(OPENID);
        buyer.setSubscribeQuota(2);
        when(pointsPersistence.findAccountsByOpenidsIn(any()))
            .thenReturn(Mono.just(Map.of(memberOpenid, member, OPENID, buyer)));

        StepVerifier.create(service.limitsByOpenid(List.of(memberOpenid, OPENID, "openid-none")))
            .assertNext(limits -> {
                assertThat(limits.get(memberOpenid)).isEqualTo(30);
                assertThat(limits.get(OPENID)).isEqualTo(7);
                // 没有账户记录的用户按免费额度算，不能漏掉
                assertThat(limits).containsKey("openid-none");
                assertThat(limits.get("openid-none")).isEqualTo(5);
            })
            .verifyComplete();
    }

    @Test
    void brokenConfigShouldFallBackToDefaults() {
        // 配额读不出来时按默认（免费 5）而不是「不限」：宁可少给也不要多给
        stubAccount(null, 0L, null);
        when(settingFetcher.get(eq("subscriptionConfig")))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.limitOf(OPENID))
            .expectNext(5)
            .verifyComplete();
    }
}
