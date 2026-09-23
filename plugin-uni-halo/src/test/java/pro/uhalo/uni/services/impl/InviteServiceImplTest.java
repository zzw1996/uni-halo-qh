package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private WxUserService wxUserService;

    @Mock
    private PointsPersistence persistence;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private InviteServiceImpl inviteService;

    private WxUser inviter;
    private WxUser invitee;
    private UserPoints inviterAccount;
    /** 被邀请人账户：邀请成立时他也能拿一笔 */
    private UserPoints inviteeAccount;

    @BeforeEach
    void setUp() {
        inviter = new WxUser();
        inviter.setId("u-inviter");
        inviter.setOpenid("openid-inviter");
        inviter.setInviteCode("ABC23456");
        inviter.setNickname("邀请人");
        invitee = new WxUser();
        invitee.setId("u-invitee");
        invitee.setOpenid("openid-invitee");
        inviterAccount = new UserPoints();
        inviterAccount.setWxUserId("wx-inviter");
        inviterAccount.setWxOpenid("openid-inviter");
        inviterAccount.setBalance(100L);
        inviteeAccount = new UserPoints();
        inviteeAccount.setWxUserId("wx-invitee");
        inviteeAccount.setWxOpenid("openid-invitee");
        inviteeAccount.setBalance(0L);
        lenient().when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    private void stubInviteConfig(String json) throws Exception {
        when(settingFetcher.get("inviteConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private void stubRewardFlow() {
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.empty());
        when(persistence.getOrCreateAccount("openid-inviter")).thenReturn(Mono.just(inviterAccount));
        lenient().when(persistence.getOrCreateAccount("openid-invitee"))
            .thenReturn(Mono.just(inviteeAccount));
        lenient().when(wxUserService.fetchByOpenid("openid-invitee"))
            .thenReturn(Mono.just(invitee));
        when(persistence.saveAccount(any(UserPoints.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(persistence.appendRecord(any(PointsRecord.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void grantInviteRewardShouldBindAndRewardBothSides() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30,\"inviteeRewardPoints\":30}");
        when(wxUserService.fetchByInviteCode("ABC23456")).thenReturn(Mono.just(inviter));
        when(wxUserService.fetchByOpenid("openid-invitee")).thenReturn(Mono.just(invitee));
        when(wxUserService.update(invitee)).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        stubRewardFlow();

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", true))
            .expectNext(true)
            .verifyComplete();

        assertThat(invitee.getInviterOpenid()).isEqualTo("openid-inviter");
        assertThat(inviterAccount.getBalance()).as("邀请人 +30").isEqualTo(130L);
        assertThat(inviteeAccount.getBalance()).as("被邀请人也 +30").isEqualTo(30L);
    }

    /**
     * 被邀请人奖励额度可配为 0：那样只给邀请人发，被邀请人那一笔不发。
     * 两侧奖励互相独立，一侧配 0 不影响另一侧。
     */
    @Test
    void grantInviteRewardShouldAllowZeroInviteeReward() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30,\"inviteeRewardPoints\":0}");
        when(wxUserService.fetchByInviteCode("ABC23456")).thenReturn(Mono.just(inviter));
        when(wxUserService.fetchByOpenid("openid-invitee")).thenReturn(Mono.just(invitee));
        when(wxUserService.update(invitee)).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        stubRewardFlow();

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", true))
            .expectNext(true)
            .verifyComplete();

        assertThat(inviterAccount.getBalance()).isEqualTo(130L);
        assertThat(inviteeAccount.getBalance()).as("配 0 时被邀请人不发").isEqualTo(0L);
    }

    @Test
    void grantInviteRewardShouldSkipSelfInvitation() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30}");
        when(wxUserService.fetchByInviteCode("ABC23456")).thenReturn(Mono.just(inviter));

        StepVerifier.create(inviteService.grantInviteReward("openid-inviter", "ABC23456", true))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void grantInviteRewardShouldSkipWhenCodeUnknown() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30}");
        when(wxUserService.fetchByInviteCode("XYZ12345")).thenReturn(Mono.empty());

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "XYZ12345", true))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }

    @Test
    void grantInviteRewardShouldBeIdempotentWhenAlreadyRewarded() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30}");
        when(wxUserService.fetchByInviteCode("ABC23456")).thenReturn(Mono.just(inviter));
        when(wxUserService.fetchByOpenid("openid-invitee")).thenReturn(Mono.just(invitee));
        when(wxUserService.update(invitee)).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        var existing = new PointsRecord();
        existing.setEarnKey(PointsServiceImpl.buildEarnKey("openid-inviter", "invite", "openid-invitee"));
        when(persistence.findRecordByEarnKey(any())).thenReturn(Mono.just(existing));

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", true))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }

    /** 已进入过小程序的存量用户（非新注册）不发放邀请奖励 */
    @Test
    void grantInviteRewardShouldSkipExistingInvitee() throws Exception {
        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", false))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
        verify(wxUserService, never()).fetchByInviteCode(any());
    }

    @Test
    void grantInviteRewardShouldSkipWhenInviteeAlreadyBound() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30}");
        invitee.setInviterOpenid("openid-another");
        when(wxUserService.fetchByInviteCode("ABC23456")).thenReturn(Mono.just(inviter));
        when(wxUserService.fetchByOpenid("openid-invitee")).thenReturn(Mono.just(invitee));

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", true))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
        verify(wxUserService, never()).update(any());
    }

    @Test
    void getInviteInfoShouldReturnCodeAndCount() throws Exception {
        stubInviteConfig("{\"enabled\":true,\"rewardPoints\":30}");
        when(wxUserService.fetchByOpenid("openid-inviter")).thenReturn(Mono.just(inviter));
        when(wxUserService.countByInviterOpenid("openid-inviter")).thenReturn(Mono.just(2L));

        StepVerifier.create(inviteService.getInviteInfo("openid-inviter"))
            .consumeNextWith(info -> {
                assertThat(info.getInviteCode()).isEqualTo("ABC23456");
                assertThat(info.getRewardPoints()).isEqualTo(30);
                assertThat(info.getInvitedCount()).isEqualTo(2L);
                assertThat(info.isEnabled()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void grantInviteRewardShouldNotThrowOnDisabledConfig() throws Exception {
        stubInviteConfig("{\"enabled\":false,\"rewardPoints\":30}");

        StepVerifier.create(inviteService.grantInviteReward("openid-invitee", "ABC23456", true))
            .expectNext(false)
            .verifyComplete();
        verify(persistence, never()).appendRecord(any());
    }
}
