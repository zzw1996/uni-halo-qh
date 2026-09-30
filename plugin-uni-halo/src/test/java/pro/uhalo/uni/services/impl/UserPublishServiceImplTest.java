package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.services.WxContentSecurityService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 投稿状态的服务端判定。
 *
 * <p>重点锁定「特权用户不受门槛限制」：博主/站点所有者不该被自己设的积分门槛挡住入口
 * ——曾经 {@code visible} 只看余额，管理员积分不足 600 时连投稿入口都看不到。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserPublishServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 门槛 600、单次 600：普通用户积分不足时入口不可见，特权用户豁免 */
    private static final String CONFIG_JSON = """
        {"enabled":true,"showButton":true,"minPointsToShow":600,
         "articleCost":600,"momentCost":600,"requireHaloBind":false,
         "dailyArticleLimit":1,"dailyMomentLimit":1}
        """;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private PointsPersistence pointsPersistence;

    @Mock
    private HaloPostPublisher postPublisher;

    @Mock
    private ReactiveExtensionClient extensionClient;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private WxContentSecurityService contentSecurityService;

    @Mock
    private ObjectProvider<RoleService> roleServiceProvider;

    @Mock
    private RoleService roleService;

    @Mock
    private DigestHaloClient haloClient;

    @InjectMocks
    private UserPublishServiceImpl userPublishService;

    private void stubConfig() throws Exception {
        lenient().when(settingFetcher.get("userPublishConfig"))
            .thenReturn(Mono.just(MAPPER.readTree(CONFIG_JSON)));
        // 无 ownerOpenid：本组用例只走「角色含 admin」这条特权判定
        lenient().when(settingFetcher.get("wxAppConfig"))
            .thenReturn(Mono.just(MAPPER.readTree("{}")));
        lenient().when(pointsPersistence.listRecordsByOpenid(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(List.<PointsRecord>of()));
    }

    private void stubAccount(long balance) {
        var account = new UserPoints();
        account.setBalance(balance);
        when(pointsPersistence.getOrCreateAccount("openid-1")).thenReturn(Mono.just(account));
    }

    private void stubUser(String haloUserName) {
        var user = new WxUser();
        user.setOpenid("openid-1");
        user.setHaloUserName(haloUserName);
        when(wxUserService.fetchByOpenid("openid-1")).thenReturn(Mono.just(user));
    }

    private void stubRoles(String... roles) {
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        lenient().when(roleService.getRolesByUsername(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Flux.fromArray(roles));
    }

    /** 管理员积分不足，也必须能看到入口、能发布，且不显示「还差多少积分」。 */
    @Test
    void privilegedUserShouldBypassPointsThreshold() throws Exception {
        stubConfig();
        stubAccount(0L);
        stubUser("admin-user");
        stubRoles("admin");

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isTrue();
                // 关键：门槛对特权用户不生效
                assertThat(status.isVisible()).isTrue();
                assertThat(status.getPointsToUnlock()).isZero();
                assertThat(status.isCanPublishMoment()).isTrue();
                assertThat(status.isCanPublishArticle()).isTrue();
                assertThat(status.getMomentDisabledReason()).isEmpty();
                assertThat(status.getArticleDisabledReason()).isEmpty();
            })
            .verifyComplete();
    }

    /** 普通用户积分不足：入口不可见，且给出「还差多少」与不可发布的原因（行为不能被上面的修复带偏）。 */
    @Test
    void regularUserShouldStillBeBlockedByThreshold() throws Exception {
        stubConfig();
        stubAccount(100L);
        stubUser("normal-user");
        stubRoles("user");

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isFalse();
                assertThat(status.isVisible()).isFalse();
                assertThat(status.getPointsToUnlock()).isEqualTo(500);
                assertThat(status.isCanPublishMoment()).isFalse();
                assertThat(status.getMomentDisabledReason()).contains("积分不足");
            })
            .verifyComplete();
    }

    /** 普通用户积分达标：入口可见、可发布。 */
    @Test
    void regularUserWithEnoughPointsShouldSeeEntry() throws Exception {
        stubConfig();
        stubAccount(600L);
        stubUser("normal-user");
        stubRoles("user");

        StepVerifier.create(userPublishService.getStatus("openid-1"))
            .assertNext(status -> {
                assertThat(status.isPrivileged()).isFalse();
                assertThat(status.isVisible()).isTrue();
                assertThat(status.getPointsToUnlock()).isZero();
                assertThat(status.isCanPublishMoment()).isTrue();
            })
            .verifyComplete();
    }
}
