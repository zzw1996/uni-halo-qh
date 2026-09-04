package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.scheme.WxUser;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxUserServiceImplTest {

    @Mock
    private ReactiveExtensionClient client;

    @InjectMocks
    private WxUserServiceImpl wxUserService;

    @Test
    void listUsersShouldFilterByHaloUserNameKeyword() {
        var boundUser = new WxUser();
        boundUser.setId("user-1");
        boundUser.setOpenid("openid-1");
        boundUser.setNickname("小明");
        boundUser.setHaloUserName("halo-admin");

        var unboundUser = new WxUser();
        unboundUser.setId("user-2");
        unboundUser.setOpenid("openid-2");
        unboundUser.setNickname("小红");

        when(client.listAll(eq(WxUser.class), any(ListOptions.class), isNull()))
            .thenReturn(Flux.just(boundUser, unboundUser));

        StepVerifier.create(wxUserService.listUsers("halo-admin", 0, 20))
            .expectNextMatches(response -> response.getTotal() == 1
                && response.getItems().size() == 1
                && "halo-admin".equals(response.getItems().get(0).getHaloUserName()))
            .verifyComplete();
    }

    @Test
    void listUsersShouldFilterByOpenidKeyword() {
        var user = new WxUser();
        user.setId("user-1");
        user.setOpenid("openid-abc-123");
        user.setNickname("测试用户");

        when(client.listAll(eq(WxUser.class), any(ListOptions.class), isNull()))
            .thenReturn(Flux.just(user));

        StepVerifier.create(wxUserService.listUsers("abc-123", 0, 20))
            .expectNextMatches(response -> response.getTotal() == 1
                && "openid-abc-123".equals(response.getItems().get(0).getOpenid()))
            .verifyComplete();
    }
}
