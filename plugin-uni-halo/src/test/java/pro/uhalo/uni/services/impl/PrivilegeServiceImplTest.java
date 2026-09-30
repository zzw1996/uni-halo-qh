package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 特权身份判定（站点所有者 / 管理员）。
 *
 * <p>这层判定决定「谁可以豁免付费与门槛」，是站长上架前自测的地基：
 * 漏一次就会出现「站长自己用不了自己站的功能」。判错方向的代价也不对称——
 * 误判为特权等于把付费权益白送，所以读不到角色时必须是 false。</p>
 */
@ExtendWith(MockitoExtension.class)
class PrivilegeServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private ObjectProvider<RoleService> roleServiceProvider;

    @Mock
    private RoleService roleService;

    @Mock
    private ReactiveExtensionClient extensionClient;

    @InjectMocks
    private PrivilegeServiceImpl privilegeService;

    private WxUser user(String openid, String haloUserName) {
        var user = new WxUser();
        user.setOpenid(openid);
        user.setHaloUserName(haloUserName);
        return user;
    }

    /** 角色含 admin：管理员身份即特权（无需站长再声明 openid）。走完整路径：openid → 用户 → 角色。 */
    @Test
    void adminRoleShouldBePrivileged() throws Exception {
        when(wxUserService.fetchByOpenid("openid-admin"))
            .thenReturn(Mono.just(user("openid-admin", "halo-admin")));
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        when(roleService.getRolesByUsername("halo-admin")).thenReturn(Flux.just("admin"));
        lenient().when(settingFetcher.get("wxAppConfig"))
            .thenReturn(Mono.just(MAPPER.readTree("{}")));
        assertThat(privilegeService.isPrivileged("openid-admin").block()).isTrue();
    }

    /** super-role 同样算特权（大小写不敏感）。 */
    @Test
    void superRoleShouldBePrivilegedCaseInsensitively() throws Exception {
        when(wxUserService.fetchByOpenid("openid-super"))
            .thenReturn(Mono.just(user("openid-super", "halo-super")));
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        when(roleService.getRolesByUsername("halo-super")).thenReturn(Flux.just("Super-Role"));
        lenient().when(settingFetcher.get("wxAppConfig"))
            .thenReturn(Mono.just(MAPPER.readTree("{}")));
        assertThat(privilegeService.isPrivileged("openid-super").block()).isTrue();
    }

    /** 站长 openid：角色查不到也放行（后台显式声明的博主身份优先）。 */
    @Test
    void ownerOpenidShouldBePrivileged() throws Exception {
        when(wxUserService.fetchByOpenid("openid-owner"))
            .thenReturn(Mono.just(user("openid-owner", "halo-owner")));
        lenient().when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        lenient().when(roleService.getRolesByUsername(any()))
            .thenReturn(Flux.just("contributor"));
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"ownerOpenid\":\"openid-owner\"}")));
        assertThat(privilegeService.isPrivileged("openid-owner").block()).isTrue();
    }

    /** 普通用户（角色普通 + 不是站长 openid）：非特权。 */
    @Test
    void regularUserShouldNotBePrivileged() throws Exception {
        when(wxUserService.fetchByOpenid("openid-user"))
            .thenReturn(Mono.just(user("openid-user", "halo-user")));
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        when(roleService.getRolesByUsername("halo-user")).thenReturn(Flux.just("contributor"));
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(MAPPER.readTree(
            "{\"ownerOpenid\":\"openid-owner\"}")));
        assertThat(privilegeService.isPrivileged("openid-user").block()).isFalse();
    }

    /** 未声明的空 openid：不查库直接 false（避免把空值当站长）。 */
    @Test
    void blankOpenidShouldNotBePrivileged() {
        assertThat(privilegeService.isPrivileged("").block()).isFalse();
        assertThat(privilegeService.isPrivileged((String) null).block()).isFalse();
    }

    /** 读角色失败：按非特权处理（宁可让站长偶尔被拦，不能把权益误发给普通用户）。 */
    @Test
    void roleReadFailureShouldFailClosed() {
        when(wxUserService.fetchByOpenid("openid-x"))
            .thenReturn(Mono.just(user("openid-x", "halo-x")));
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        when(roleService.getRolesByUsername("halo-x"))
            .thenReturn(Flux.error(new IllegalStateException("role service down")));
        when(extensionClient.fetch(org.mockito.ArgumentMatchers.<Class<run.halo.app.core.extension.User>>any(),
            org.mockito.ArgumentMatchers.<String>any()))
            .thenReturn(Mono.error(new IllegalStateException("annotation read down")));

        StepVerifier.create(privilegeService.isPrivileged("openid-x"))
            .assertNext(privileged -> assertThat(privileged).isFalse())
            .verifyComplete();
    }

    /** 缓存：同一 openid 的重复判定不再打 RoleService（高频入口靠它挡住重复查询）。 */
    @Test
    void repeatedChecksShouldHitCache() {
        when(wxUserService.fetchByOpenid("openid-cache"))
            .thenReturn(Mono.just(user("openid-cache", "halo-cache")));
        when(roleServiceProvider.getIfAvailable()).thenReturn(roleService);
        when(roleService.getRolesByUsername("halo-cache")).thenReturn(Flux.just("admin"));

        assertThat(privilegeService.isPrivileged("openid-cache").block()).isTrue();
        assertThat(privilegeService.isPrivileged("openid-cache").block()).isTrue();
        org.mockito.Mockito.verify(roleService, org.mockito.Mockito.times(1))
            .getRolesByUsername("halo-cache");

        // 清缓存后重新判定会再查一次
        privilegeService.evict("openid-cache");
        assertThat(privilegeService.isPrivileged("openid-cache").block()).isTrue();
        org.mockito.Mockito.verify(roleService, org.mockito.Mockito.times(2))
            .getRolesByUsername("halo-cache");
    }
}
