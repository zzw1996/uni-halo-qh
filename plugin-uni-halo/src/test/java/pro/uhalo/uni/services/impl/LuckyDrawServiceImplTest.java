package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
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
}
