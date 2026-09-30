package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.SubscriptionLockedDTO;
import pro.uhalo.uni.dto.SubscriptionWallConfigDTO;
import pro.uhalo.uni.services.ContentTicketService;
import pro.uhalo.uni.services.MembershipService;
import pro.uhalo.uni.services.SubscriptionWallService;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.utils.SubscriptionWallConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 分类订阅墙判定。
 *
 * <p>判定顺序（非空即拦截、empty 即放行）：总开关 → 站长 → 会员 → 游客开关 →
 * 取文章 → 无分类放行 → 付费文章放行（阅读券墙管辖）→ 白名单 → 订阅交集 → 拦截。
 * 任何一步异常都放行（fail-open），与付费内容墙同一失败方向：这是转化漏斗，
 * 误拦全站文章比漏拦一篇文章的代价大得多。</p>
 *
 * <p>已订阅的口径 = {@link DigestSubscriptionDTO#getEffectiveCategories()}
 * （自己订阅且未暂停 + 邀请继承），与首页内容流完全同一份结论——
 * 首页能看到的内容详情一定打得开，否则用户会认为首页在骗他。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionWallServiceImpl implements SubscriptionWallService {

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final DigestSubscriptionService digestSubscriptionService;
    private final MembershipService membershipService;
    private final ContentTicketService contentTicketService;

    @Override
    public Mono<SubscriptionLockedDTO> resolveLockIfRequired(String openid, String articleName) {
        if (!StringUtils.hasText(articleName)) {
            return Mono.empty();
        }
        var normalizedOpenid = StringUtils.hasText(openid) ? openid.trim() : null;
        return loadConfig().flatMap(config -> {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                return Mono.<SubscriptionLockedDTO>empty();
            }
            return passByExemption(config, normalizedOpenid).flatMap(exempt -> {
                if (exempt) {
                    return Mono.<SubscriptionLockedDTO>empty();
                }
                return fetchPost(articleName).flatMap(post -> decide(config, normalizedOpenid, post));
            });
        });
    }

    /** 身份类豁免：站长 openid、有效会员、以及「不拦游客」的配置 */
    private Mono<Boolean> passByExemption(SubscriptionWallConfigDTO config, String openid) {
        if (openid == null) {
            // 游客没有身份可豁免，只看配置要不要拦
            return Mono.just(!Boolean.TRUE.equals(config.getGateGuests()));
        }
        return isOwnerOpenid(openid).flatMap(owner -> {
            if (owner) {
                return Mono.just(true);
            }
            if (!Boolean.TRUE.equals(config.getMemberFree())) {
                return Mono.just(false);
            }
            return isMemberActive(openid);
        });
    }

    /**
     * 站长豁免：{@code wxAppConfig.ownerOpenid} 显式声明的 openid。
     *
     * <p>只认站长声明、不做角色查询——角色判定要额外查 RoleService，
     * 而站长测试自己的站点用 ownerOpenid 一处配置就够。</p>
     */
    private Mono<Boolean> isOwnerOpenid(String openid) {
        return settingFetcher.get("wxAppConfig")
            .map(config -> {
                var ownerOpenid = config.path("ownerOpenid").asText("").trim();
                return StringUtils.hasText(ownerOpenid) && ownerOpenid.equals(openid);
            })
            .defaultIfEmpty(false)
            .onErrorResume(ex -> Mono.just(false));
    }

    /** 会员豁免只看会员是否在有效期；不再叠加 memberConfig.freeContent 开关，让两套权益互不牵连 */
    private Mono<Boolean> isMemberActive(String openid) {
        return membershipService.loadMemberExpireAt(openid)
            .map(MemberConfigHelper::isActive)
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Wall] 会员有效期判定失败，按非会员处理: openid={}, {}",
                    openid, ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<SubscriptionLockedDTO> decide(SubscriptionWallConfigDTO config, String openid,
        Post post) {
        var categories = post.getSpec() == null ? null : post.getSpec().getCategories();
        if (categories == null || categories.isEmpty()) {
            // 无分类文章（公告、独立页面类）没有「订阅」语义，不拦
            return Mono.empty();
        }
        return categoryInfos(categories).flatMap(infos -> contentTicketService.isPaidPost(post)
            .flatMap(paid -> {
                if (Boolean.TRUE.equals(paid)) {
                    // 付费文章有自己的阅读券墙，订阅墙不叠加
                    return Mono.<SubscriptionLockedDTO>empty();
                }
                var displayNames = infos.stream()
                    .map(SubscriptionLockedDTO.CategoryInfo::getDisplayName)
                    .collect(Collectors.toList());
                if (freeNamesHit(config.getFreeCategories(), displayNames)) {
                    return Mono.<SubscriptionLockedDTO>empty();
                }
                if (openid == null) {
                    return lockedDto(post, infos, false, true);
                }
                return digestSubscriptionService.load(openid).flatMap(subscription -> {
                    var effective = subscription.getEffectiveCategories() == null
                        ? List.<String>of() : subscription.getEffectiveCategories();
                    if (categories.stream().anyMatch(effective::contains)) {
                        return Mono.<SubscriptionLockedDTO>empty();
                    }
                    return lockedDto(post, infos, pausedHit(subscription, categories), false);
                });
            }))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Wall] 订阅判定失败，放行原生链路: article={}, {}: {}",
                    articleNameOf(post), ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.empty();
            });
    }

    /** 是否落在「已暂停」的订阅里：额度超限或会员到期后，订阅记录还在但不生效 */
    private static boolean pausedHit(DigestSubscriptionDTO subscription, List<String> categories) {
        var limits = subscription.getLimits();
        var paused = limits == null ? null : limits.getPausedCategories();
        if (paused == null || paused.isEmpty()) {
            return false;
        }
        return categories.stream().anyMatch(paused::contains);
    }

    /** 白名单按分类显示名匹配：站长配置的是人话（如「公告」），不是 metadata.name */
    private static boolean freeNamesHit(String freeCategories, List<String> displayNames) {
        if (!StringUtils.hasText(freeCategories)) {
            return false;
        }
        var freeNames = java.util.Arrays.stream(freeCategories.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
        return displayNames.stream().anyMatch(freeNames::contains);
    }

    /** 反查分类显示名用于提示文案；查不到（分类被删）时用 metadata.name 兜底 */
    private Mono<List<SubscriptionLockedDTO.CategoryInfo>> categoryInfos(List<String> categories) {
        return Flux.fromIterable(categories)
            .flatMap(name -> client.fetch(Category.class, name)
                .map(category -> SubscriptionLockedDTO.CategoryInfo.builder()
                    .name(name)
                    .displayName(displayNameOf(category, name))
                    .build())
                .defaultIfEmpty(SubscriptionLockedDTO.CategoryInfo.builder()
                    .name(name).displayName(name).build())
                .onErrorResume(ex -> Mono.just(SubscriptionLockedDTO.CategoryInfo.builder()
                    .name(name).displayName(name).build())))
            .collectList();
    }

    private static String displayNameOf(Category category, String fallback) {
        if (category == null || category.getSpec() == null) {
            return fallback;
        }
        var displayName = category.getSpec().getDisplayName();
        return StringUtils.hasText(displayName) ? displayName : fallback;
    }

    private Mono<SubscriptionLockedDTO> lockedDto(Post post,
        List<SubscriptionLockedDTO.CategoryInfo> infos, boolean paused, boolean needLogin) {
        var spec = post.getSpec();
        var status = post.getStatus();
        var firstCategory = infos.isEmpty() ? "" : infos.get(0).getDisplayName();
        var message = paused
            ? "你的订阅已暂停，解锁额度或续费会员后可继续阅读"
            : "订阅「" + firstCategory + "」分类后可阅读全文";
        return Mono.just(SubscriptionLockedDTO.builder()
            .articleName(articleNameOf(post))
            .title(spec == null ? null : spec.getTitle())
            // 摘要取自 post.status.excerpt（Halo 自动生成，列表页本就公开），
            // 绝不读取正文 Snapshot，保证锁定响应里不可能带出文章内容
            .excerpt(status == null ? null : truncate(status.getExcerpt(), 200))
            .cover(spec == null ? null : spec.getCover())
            .locked(true)
            .type(SubscriptionLockedDTO.TYPE_SUBSCRIPTION)
            .message(message)
            .categories(infos)
            .paused(paused)
            .needLogin(needLogin)
            .build());
    }

    private static String articleNameOf(Post post) {
        return post.getMetadata() == null ? "" : post.getMetadata().getName();
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private Mono<Post> fetchPost(String articleName) {
        return client.fetch(Post.class, articleName.trim())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Wall] 查询文章失败，放行: articleName={}, {}: {}",
                    articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.empty();
            });
    }

    private Mono<SubscriptionWallConfigDTO> loadConfig() {
        return settingFetcher.get(SubscriptionWallConfigHelper.groupName())
            .map(SubscriptionWallConfigHelper::parse)
            .defaultIfEmpty(SubscriptionWallConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Wall] 订阅墙配置读取失败，按关闭处理: {}", ex.getMessage());
                return Mono.just(SubscriptionWallConfigHelper.defaults());
            });
    }
}
