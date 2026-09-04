package pro.uhalo.uni.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointsPersistenceTest {

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private WxUserService wxUserService;

    @InjectMocks
    private PointsPersistence persistence;

    @Test
    void accountResourceNameShouldBeDeterministicForOpenid() {
        var name1 = PointsPersistence.accountResourceName("openid-abc");
        var name2 = PointsPersistence.accountResourceName("openid-abc");
        var name3 = PointsPersistence.accountResourceName("openid-xyz");

        assertThat(name1).startsWith("up-");
        assertThat(name1).isEqualTo(name2);
        assertThat(name1).isNotEqualTo(name3);
    }

    @Test
    void getOrCreateAccountShouldReturnExistingPersistedAccount() {
        var account = new UserPoints();
        account.setWxOpenid("openid-123");
        account.setBalance(88L);

        when(client.listAll(eq(UserPoints.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.just(account));

        var wxUser = new WxUser();
        wxUser.setId("wx-1");
        wxUser.setOpenid("openid-123");
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));
        when(client.update(account)).thenReturn(Mono.just(account));

        StepVerifier.create(persistence.getOrCreateAccount("openid-123"))
            .consumeNextWith(result -> assertThat(result.getBalance()).isEqualTo(88L))
            .verifyComplete();
    }
}
