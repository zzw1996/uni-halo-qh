package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.LuckyPrizeDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LuckyDrawConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LuckyDrawServiceImplTest {

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

    @Mock
    private ReactiveExtensionClient client;

    @InjectMocks
    private LuckyDrawServiceImpl luckyDrawService;

    private UserPoints account;

    @BeforeEach
    void setUp() {
        account = new UserPoints();
        account.setWxUserId("wx-user-1");
        account.setWxOpenid(OPENID);
        account.setBalance(100L);
        lenient().when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        // 积分商城 mallConfig 未配置时回落旧 luckyDrawConfig 分组
        lenient().when(settingFetcher.get("mallConfig")).thenReturn(Mono.empty());
    }

    private void stubConfig(String json) throws Exception {
        when(settingFetcher.get("luckyDrawConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    @Test
    void drawShouldDeductCostAndReturnPrize() throws Exception {
        stubConfig("""
            {"enabled":true,"costPoints":20,"prizes":[
              {"name":"定制周边小礼","emoji":"🎀","weight":100}
            ]}
            """);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(luckyDrawService.draw(OPENID))
            .consumeNextWith(response -> {
                assertThat(response.isWon()).isTrue();
                assertThat(response.getName()).isEqualTo("定制周边小礼");
                assertThat(response.getEmoji()).isEqualTo("🎀");
                assertThat(response.getBalance()).isEqualTo(80L);
            })
            .verifyComplete();

        assertThat(account.getBalance()).isEqualTo(80L);
    }

    @Test
    void drawShouldRejectWhenInsufficient() throws Exception {
        stubConfig("""
            {"enabled":true,"costPoints":20,"prizes":[
              {"name":"定制周边小礼","emoji":"🎀","weight":100}
            ]}
            """);
        account.setBalance(10L);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));

        StepVerifier.create(luckyDrawService.draw(OPENID))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("积分不足"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void drawShouldRejectWhenDisabled() throws Exception {
        stubConfig("{\"enabled\":false,\"costPoints\":20}");

        StepVerifier.create(luckyDrawService.draw(OPENID))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("未开启"))
            .verify();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void drawShouldReturnThanksForConsolationPrize() throws Exception {
        stubConfig("""
            {"enabled":true,"costPoints":20,"prizes":[
              {"name":"感谢参与","emoji":"🙏","weight":100}
            ]}
            """);
        when(persistence.getOrCreateAccount(OPENID)).thenReturn(Mono.just(account));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(luckyDrawService.draw(OPENID))
            .consumeNextWith(response -> {
                assertThat(response.isWon()).isFalse();
                assertThat(response.getName()).isEqualTo("感谢参与");
                assertThat(response.getBalance()).isEqualTo(80L);
            })
            .verifyComplete();
    }

    // ========== 概率公示：公示值必须等于实际抽中率 ==========

    /**
     * 核心性质：向用户公示的概率必须与真实抽中率一致。
     *
     * <p>概率计算（{@code LuckyDrawConfigHelper.withProbabilities}，weight / 总权重）
     * 与抽取实现（{@code pickPrize}，{@code nextInt(总权重)} 累加命中）分处两个类，
     * 若将来有人改了其中一处而没同步另一处，用户看到的概率就会失真，
     * 属于「公示不实」。这个统计测试用于同时锁定两侧口径。</p>
     *
     * <p>权重 50/15/35 在 20 万次抽样下，单个奖品抽中率的标准差约 0.11%，
     * 取 0.8% 容差约为 7 倍标准差：足以抓住真实偏差，又不会因随机性偶发失败。</p>
     */
    @Test
    void publishedProbabilityShouldMatchActualDrawRate() {
        var prizes = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            LuckyPrizeDTO.builder().name("再来一次").emoji("🎁").weight(50)
                .needsAddress(false).desc("未中奖").build(),
            LuckyPrizeDTO.builder().name("定制周边小礼").emoji("🎀").weight(15)
                .needsAddress(true).desc("周边小件").build(),
            LuckyPrizeDTO.builder().name("感谢参与").emoji("🙏").weight(35)
                .needsAddress(false).desc("未中奖").build()
        )));

        // 前置：概率确实已被计算并下发（否则下面的比对会因两侧都是 0 而假通过）
        assertThat(prizes).allSatisfy(prize ->
            assertThat(prize.getProbability()).isGreaterThan(0.0));

        var rounds = 200_000;
        Map<String, Integer> hits = new HashMap<>();
        for (var i = 0; i < rounds; i++) {
            var picked = LuckyDrawServiceImpl.pickPrize(prizes);
            assertThat(picked).isNotNull();
            hits.merge(picked.getName(), 1, Integer::sum);
        }

        // 每个奖品都必须被抽到过，避免概率公示了某个实际上永远抽不到的奖品
        assertThat(hits).hasSameSizeAs(prizes);

        for (var published : prizes) {
            var actualPercent = hits.getOrDefault(published.getName(), 0) * 100.0 / rounds;
            assertThat(actualPercent)
                .as("奖品「%s」的实际抽中率应与其公示概率一致", published.getName())
                .isCloseTo(published.getProbability(), within(0.8));
        }
    }

    /** 单奖品时公示概率应为 100%，且实际也必然抽中它。 */
    @Test
    void publishedProbabilityShouldBe100ForSinglePrize() {
        var prizes = LuckyDrawConfigHelper.withProbabilities(new ArrayList<>(List.of(
            LuckyPrizeDTO.builder().name("唯一奖品").emoji("🎁").weight(7)
                .needsAddress(false).desc("说明").build()
        )));

        assertThat(prizes.get(0).getProbability()).isEqualTo(100.0);
        assertThat(LuckyDrawServiceImpl.pickPrize(prizes).getName()).isEqualTo("唯一奖品");
    }
}
