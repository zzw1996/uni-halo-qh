package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.utils.WxAutoUsernameGenerator;
import pro.uhalo.uni.dto.UpdateProfileRequest;
import pro.uhalo.uni.dto.WxLoginRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.HaloUserSyncService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxAuthServiceImplTest {

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private HaloUserSyncService haloUserSyncService;

    @Mock
    private ExternalLinkProcessor externalLinkProcessor;

    @InjectMocks
    private WxAuthServiceImpl wxAuthService;

    private WxUser existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new WxUser();
        existingUser.setId("user-1");
        existingUser.setOpenid("openid-123");
        existingUser.setSessionKey("session-key");
        existingUser.setNickname("旧昵称");
        existingUser.setAvatarUrl("https://example.com/old.png");
        existingUser.setProfileComplete(true);
        existingUser.setProfileModifyCount(0);
        existingUser.setHaloUserName("wx_openid-123");
        lenient().when(haloUserSyncService.updateHaloUserProfile(any(), any(), any()))
            .thenReturn(Mono.empty());
    }

    private void stubWxConfig(int profileModifyLimit) {
        var wxConfig = JsonNodeFactory.instance.objectNode();
        wxConfig.put("profileModifyLimit", profileModifyLimit);
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wxConfig));
        when(settingFetcher.get("appConfig")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void getWxAppConfigShouldReturnHaloBindEnabled() {
        var wxConfig = JsonNodeFactory.instance.objectNode();
        wxConfig.put("appId", "wx-test");
        wxConfig.put("loginEnabled", true);
        wxConfig.put("profileModifyLimit", 0);
        wxConfig.put("haloBindEnabled", true);
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wxConfig));
        when(settingFetcher.get("appConfig")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()));

        StepVerifier.create(wxAuthService.getWxAppConfig())
            .expectNextMatches(dto -> "wx-test".equals(dto.getAppId())
                && dto.isLoginEnabled()
                && dto.isHaloBindEnabled())
            .verifyComplete();
    }

    @Test
    void getWxAppConfigShouldDefaultHaloBindEnabledToFalse() {
        var wxConfig = JsonNodeFactory.instance.objectNode();
        wxConfig.put("appId", "wx-test");
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wxConfig));
        when(settingFetcher.get("appConfig")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()));

        StepVerifier.create(wxAuthService.getWxAppConfig())
            .expectNextMatches(dto -> !dto.isHaloBindEnabled())
            .verifyComplete();
    }

    @Test
    void sendHaloBindCodeShouldFailWhenHaloBindDisabled() {
        var wxConfig = JsonNodeFactory.instance.objectNode();
        wxConfig.put("haloBindEnabled", false);
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wxConfig));
        when(settingFetcher.get("appConfig")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()));

        var request = new pro.uhalo.uni.dto.SendHaloBindCodeRequest();
        request.setAccount("user@example.com");

        StepVerifier.create(wxAuthService.sendHaloBindCode("openid-123", request))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "站长尚未开启 Halo 账号关联".equals(((WxAuthException) ex).getReason()))
            .verify();
    }

    @Test
    void wxLoginShouldFailWhenCodeMissing() {
        var request = new WxLoginRequest();
        request.setNickname("昵称");

        StepVerifier.create(wxAuthService.wxLogin(request))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "缺少 code 参数".equals(((WxAuthException) ex).getReason()))
            .verify();
    }

    @Test
    void updateProfileShouldFailWhenOpenidMissing() {
        StepVerifier.create(wxAuthService.updateProfile(null, new UpdateProfileRequest()))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "缺少 Wechat-Session-Id".equals(((WxAuthException) ex).getReason()))
            .verify();

        verify(wxUserService, never()).fetchByOpenid(any());
    }

    @Test
    void updateProfileShouldFailWhenBodyEmpty() {
        StepVerifier.create(wxAuthService.updateProfile("openid-123", new UpdateProfileRequest()))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "nickname 与 avatarUrl 不能同时为空".equals(((WxAuthException) ex).getReason()))
            .verify();
    }

    @Test
    void updateProfileShouldFailWhenNicknameTooLong() {
        var request = new UpdateProfileRequest();
        request.setNickname("a".repeat(33));

        StepVerifier.create(wxAuthService.updateProfile("openid-123", request))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "nickname 长度不能超过 32 个字符".equals(((WxAuthException) ex).getReason()))
            .verify();
    }

    @Test
    void updateProfileShouldFailWhenUserNotFound() {
        stubWxConfig(0);
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.empty());

        var request = new UpdateProfileRequest();
        request.setNickname("新昵称");

        StepVerifier.create(wxAuthService.updateProfile("openid-123", request))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "用户不存在，请先登录".equals(((WxAuthException) ex).getReason()))
            .verify();
    }

    @Test
    void updateProfileShouldFailWhenModifyLimitReached() {
        stubWxConfig(2);
        existingUser.setProfileModifyCount(2);
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(existingUser));

        var request = new UpdateProfileRequest();
        request.setNickname("新昵称");

        StepVerifier.create(wxAuthService.updateProfile("openid-123", request))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "资料修改次数已达上限".equals(((WxAuthException) ex).getReason()))
            .verify();

        verify(wxUserService, never()).update(any());
    }

    @Test
    void updateProfileShouldUpdateNicknameAndAvatar() {
        stubWxConfig(0);
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(existingUser));
        when(wxUserService.update(existingUser)).thenReturn(Mono.just(existingUser));
        when(externalLinkProcessor.processLink("https://example.com/new.png"))
            .thenReturn("https://example.com/new.png");

        var request = new UpdateProfileRequest();
        request.setNickname("新昵称");
        request.setAvatarUrl("https://example.com/new.png");

        StepVerifier.create(wxAuthService.updateProfile("openid-123", request))
            .expectNextMatches(dto -> "新昵称".equals(dto.getNickname())
                && "https://example.com/new.png".equals(dto.getAvatarUrl())
                && "openid-123".equals(dto.getOpenid())
                && "wx_openid-123".equals(dto.getHaloUserName())
                && "bound".equals(dto.getHaloBindStatus())
                && Boolean.TRUE.equals(dto.getProfileComplete())
                && Integer.valueOf(1).equals(dto.getProfileModifyCount()))
            .verifyComplete();
    }

    @Test
    void unbindHaloAccountShouldFailWhenNotBound() {
        existingUser.setHaloUserName(null);
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(existingUser));

        StepVerifier.create(wxAuthService.unbindHaloAccount("openid-123"))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "当前未关联 Halo 账号".equals(((WxAuthException) ex).getReason()))
            .verify();

        verify(haloUserSyncService, never()).unbindOpenidAnnotation(any(), any());
    }

    @Test
    void unbindHaloAccountShouldUnbindBoundUser() {
        existingUser.setHaloUserName("halo-admin");
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(existingUser));
        when(haloUserSyncService.unbindOpenidAnnotation("halo-admin", "openid-123"))
            .thenReturn(Mono.empty());
        when(wxUserService.update(existingUser)).thenAnswer(invocation -> {
            var user = invocation.getArgument(0, WxUser.class);
            return Mono.just(user);
        });

        StepVerifier.create(wxAuthService.unbindHaloAccount("openid-123"))
            .expectNextMatches(dto -> "openid-123".equals(dto.getOpenid())
                && "".equals(dto.getHaloUserName())
                && Constants.HALO_BIND_STATUS_UNBOUND.equals(dto.getHaloBindStatus()))
            .verifyComplete();

        verify(haloUserSyncService).unbindOpenidAnnotation("halo-admin", "openid-123");
        verify(wxUserService).update(existingUser);
    }

    @Test
    void unbindHaloAccountShouldUnbindCreatedUser() {
        existingUser.setHaloUserName(WxAutoUsernameGenerator.format(WxAutoUsernameGenerator.deriveSeed("openid-123")));
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(existingUser));
        when(haloUserSyncService.unbindOpenidAnnotation(existingUser.getHaloUserName(), "openid-123"))
            .thenReturn(Mono.empty());
        when(wxUserService.update(existingUser)).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(wxAuthService.unbindHaloAccount("openid-123"))
            .expectNextMatches(dto -> Constants.HALO_BIND_STATUS_UNBOUND.equals(dto.getHaloBindStatus())
                && "".equals(dto.getHaloUserName()))
            .verifyComplete();
    }

    @Test
    void unbindHaloAccountShouldFailWhenUserNotFound() {
        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.empty());

        StepVerifier.create(wxAuthService.unbindHaloAccount("openid-123"))
            .expectErrorMatches(ex -> ex instanceof WxAuthException
                && "用户不存在，请先登录".equals(((WxAuthException) ex).getReason()))
            .verify();
    }
}
