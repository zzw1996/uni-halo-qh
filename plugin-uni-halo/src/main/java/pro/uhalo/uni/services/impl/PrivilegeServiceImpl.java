package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特权身份判定的唯一实现，见 {@link PrivilegeService} 的说明。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrivilegeServiceImpl implements PrivilegeService {

    private static final String WX_APP_CONFIG_GROUP = "wxAppConfig";

    /** Halo 的角色名注解：格式形如 {@code author,contributor} */
    private static final String ROLE_NAMES_ANNO = "rbac.authorization.halo.run/role-names";

    /** 视为「站点主人」的角色：Halo 内置的超级角色与管理员 */
    private static final Set<String> PRIVILEGED_ROLES = Set.of("super-role", "admin");

    /**
     * 判定结果缓存时长。
     *
     * <p>一次判定要读 WxUser + 角色，而首页、AI 定制内容页这类入口会在一次交互里问好几次，
     * 所以缓存短时间的结果。60 秒足够挡住同一屏内的重复调用，又不至于让「刚改完角色
     * 想立刻验证」的人等太久（真要立刻生效可用 {@link #evict}）。</p>
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;
    private final ObjectProvider<RoleService> roleServiceProvider;
    private final ReactiveExtensionClient extensionClient;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /** @param privileged 判定结果；@param expireAt 过期时刻 */
    private record Cached(boolean privileged, Instant expireAt) {
        boolean fresh() {
            return Instant.now().isBefore(expireAt);
        }
    }

    @Override
    public Mono<Boolean> isPrivileged(String openid) {
        var normalized = openid == null ? "" : openid.trim();
        if (!StringUtils.hasText(normalized)) {
            return Mono.just(false);
        }
        var hit = cache.get(normalized);
        if (hit != null && hit.fresh()) {
            return Mono.just(hit.privileged());
        }
        return wxUserService.fetchByOpenid(normalized)
            .flatMap(user -> rolesOf(user.getHaloUserName())
                .flatMap(roles -> isPrivileged(user, roles)))
            .defaultIfEmpty(false)
            .doOnNext(privileged -> cache.put(normalized,
                new Cached(privileged, Instant.now().plus(CACHE_TTL))))
            .onErrorResume(ex -> {
                // 读不到角色/用户时按「非特权」处理：宁可让站长偶尔被拦（可重试），
                // 也不能把付费权益误发给普通用户
                log.warn("[UniHalo][Privilege] 特权判定失败，按非特权处理：{}", ex.getMessage());
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Boolean> isPrivileged(WxUser user, Set<String> roles) {
        if (roles != null && roles.stream()
            .anyMatch(r -> PRIVILEGED_ROLES.contains(r.toLowerCase(Locale.ROOT)))) {
            return Mono.just(true);
        }
        if (user == null || !StringUtils.hasText(user.getOpenid())) {
            return Mono.just(false);
        }
        var openid = user.getOpenid().trim();
        var wxAppConfig = settingFetcher.get(WX_APP_CONFIG_GROUP);
        // settingFetcher.get 在分组缺失时会返回 null（不是空 Mono），这里兜住，
        // 与项目里其它读设置的地方同一约定
        return (wxAppConfig == null ? Mono.<com.fasterxml.jackson.databind.JsonNode>empty()
            : wxAppConfig)
            .map(config -> {
                var ownerOpenid = config.path("ownerOpenid").asText("").trim();
                return StringUtils.hasText(ownerOpenid) && ownerOpenid.equals(openid);
            })
            .defaultIfEmpty(false);
    }

    @Override
    public Mono<Set<String>> rolesOf(String haloUserName) {
        if (!StringUtils.hasText(haloUserName)) {
            return Mono.just(Set.of());
        }
        var roleService = roleServiceProvider.getIfAvailable();
        Mono<Set<String>> viaService = roleService == null
            ? Mono.just(Set.<String>of())
            : roleService.getRolesByUsername(haloUserName)
                .collectList()
                .map(list -> (Set<String>) new LinkedHashSet<String>(list))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Privilege] 读取角色失败（RoleService）：{}", ex.getMessage());
                    return Mono.just(Set.<String>of());
                });
        return viaService.flatMap(roles -> roles.isEmpty()
            ? rolesFromAnnotation(haloUserName)
            : Mono.just(roles));
    }

    @Override
    public void evict(String openid) {
        if (StringUtils.hasText(openid)) {
            cache.remove(openid.trim());
        }
    }

    /** 兜底：官方 RoleService 不可用（或返回空）时读 User 上的角色注解 */
    private Mono<Set<String>> rolesFromAnnotation(String haloUserName) {
        return extensionClient.fetch(User.class, haloUserName)
            .map(user -> {
                var annotations = user.getMetadata().getAnnotations();
                var raw = annotations == null ? null : annotations.get(ROLE_NAMES_ANNO);
                if (!StringUtils.hasText(raw)) {
                    return Set.<String>of();
                }
                var normalized = new LinkedHashSet<String>();
                for (var part : raw.split(",")) {
                    var name = part.trim();
                    if (StringUtils.hasText(name)) {
                        normalized.add(name);
                    }
                }
                return (Set<String>) normalized;
            })
            .defaultIfEmpty(Set.of());
    }
}
