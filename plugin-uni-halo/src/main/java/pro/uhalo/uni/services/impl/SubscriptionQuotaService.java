package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.SubscriptionConfigDTO;
import pro.uhalo.uni.dto.SubscriptionLimitsDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.utils.SubscriptionConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订阅额度：算清「现在能订几个」以及「超出额度被暂停的是哪几个」。
 *
 * <p>额度 = 免费额度 + 已购额度；会员有效期内放宽到会员上限。判定只在这一个地方做，
 * 保存校验、首页生效集合、消息推送三处都从这里取，避免出现「能存进去但首页不生效」。</p>
 *
 * <p><b>刻意不依赖 {@code MembershipService}</b>：会员到期时间就存在积分账户的同一条记录上
 * （{@code UserPoints.memberExpireAt}），这里已经要读账户，顺手取出来即可。绕一层不但多一次
 * 账户查询，还会形成「订阅额度 ↔ 会员」的循环依赖——构造器注入下 Spring 会直接启动失败。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionQuotaService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveSettingFetcher settingFetcher;
    private final PointsPersistence pointsPersistence;
    /** 特权判定：站点所有者/管理员订阅不限量（见 PrivilegeService） */
    private final PrivilegeService privilegeService;

    /**
     * 特权用户的订阅上限。
     *
     * <p>取 {@link Integer#MAX_VALUE} 而不是某个大数字：后续与「已订阅数」比较时
     * 任何实际数量都远小于它，等价于不限量，也不会在计算里溢出。</p>
     */
    static final int UNLIMITED_QUOTA = Integer.MAX_VALUE;

    /** 读取额度配置（读不到按默认，绝不让它挡住订阅保存） */
    public Mono<SubscriptionConfigDTO> loadConfig() {
        return settingFetcher.get(SubscriptionConfigHelper.groupName())
            .map(SubscriptionConfigHelper::parse)
            .defaultIfEmpty(SubscriptionConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Subscription] 读取订阅额度配置失败，按默认处理：{}", ex.getMessage());
                return Mono.just(SubscriptionConfigHelper.defaults());
            });
    }

    /**
     * 某个用户当前能订阅的分类数上限。
     *
     * <p>未登录（游客）按非会员算——游客本来也存不了订阅。</p>
     */
    public Mono<Integer> limitOf(String openid) {
        return quotaStateOf(openid).map(QuotaState::limit);
    }

    /**
     * 额度视图：给客户端展示与判断用。
     *
     * @param usedOwn 自己订阅的分类（按顺序），用于算出被暂停的那些
     */
    public Mono<SubscriptionLimitsDTO> limitsOf(String openid, List<String> usedOwn) {
        return quotaStateOf(openid).map(state -> {
            var own = usedOwn == null ? List.<String>of() : usedOwn;
            var paused = own.size() > state.limit()
                ? List.copyOf(own.subList(state.limit(), own.size()))
                : List.<String>of();
            return SubscriptionLimitsDTO.builder()
                .purchaseEnabled(state.config().isEnabled())
                .freeQuota(state.config().getFreeQuota())
                .purchasedQuota(state.purchasedQuota())
                .quotaPrice(state.config().getQuotaPrice())
                .maxQuota(state.config().getMaxQuota())
                .limit(state.limit())
                .memberActive(state.memberActive())
                .memberExpireAt(state.memberExpireAt() == null
                    ? "" : TIME_FMT.format(state.memberExpireAt().atZone(ZONE)))
                .used(own.size())
                .canPurchase(state.config().isEnabled()
                    && state.purchasedQuota() < state.config().getMaxQuota())
                .pointsBalance(state.balance())
                .pausedCategories(paused)
                .build();
        });
    }

    /**
     * 批量解析一批用户的上限：一次读全部账户，供推送这类「一次要过所有订阅用户」的场景。
     *
     * <p>逐个用户去查账户会让一次推送变成几百次查询；这里只查一次，
     * 判定口径与 {@link #quotaStateOf} 完全一致（配置 + 已购额度 + 会员）。</p>
     */
    public Mono<java.util.Map<String, Integer>> limitsByOpenid(
        java.util.Collection<String> openids) {
        var ids = openids == null ? java.util.Set.<String>of() : openids.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Mono.just(java.util.Map.of());
        }
        return loadConfig().flatMap(config -> pointsPersistence.findAccountsByOpenidsIn(ids)
            .map(accounts -> {
                var result = new java.util.HashMap<String, Integer>();
                for (var id : ids) {
                    var account = accounts.get(id);
                    var expireAt = account == null ? null : account.getMemberExpireAt();
                    if (MemberConfigHelper.isActive(expireAt)) {
                        result.put(id, config.memberLimit());
                        continue;
                    }
                    var purchased = clampPurchased(
                        account == null ? null : account.getSubscribeQuota(), config);
                    result.put(id, config.limitFor(purchased));
                }
                return result;
            }));
    }

    /** 额度状态（配置 + 账户 + 会员），保存校验与视图共用同一份推导 */
    public Mono<QuotaState> quotaStateOf(String openid) {
        return loadConfig().flatMap(config -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.just(new QuotaState(config, 0, 0L, false, null,
                    config.limitFor(0)));
            }
            var normalized = openid.trim();
            // 站点所有者/管理员：订阅不限量。上架前自测、以及「站长自己当然能订全站」
            // 这两件事都不该被额度挡住。
            return privilegeService.isPrivileged(normalized)
                .flatMap(privileged -> Mono.defer(() -> pointsPersistence
                    .findAccountByOpenid(normalized))
                .defaultIfEmpty(new UserPoints())
                .map(account -> {
                    var expireAt = account.getMemberExpireAt();
                    var memberActive = MemberConfigHelper.isActive(expireAt);
                    var purchased = clampPurchased(account.getSubscribeQuota(), config);
                    var limit = privileged ? UNLIMITED_QUOTA
                        : (memberActive ? config.memberLimit() : config.limitFor(purchased));
                    return new QuotaState(config, purchased,
                        account.getBalance() == null ? 0L : account.getBalance(),
                        memberActive, memberActive ? expireAt : null, limit);
                })
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Subscription] 读取订阅额度失败，按免费额度处理：openid={} {}",
                        mask(normalized), ex.getMessage());
                    return Mono.just(new QuotaState(config, 0, 0L, false, null,
                        config.limitFor(0)));
                }));
        });
    }

    /** 日志用的 openid 脱敏 */
    private static String mask(String openid) {
        return openid == null || openid.length() <= 6 ? "***" : openid.substring(0, 6) + "***";
    }

    /** 已购额度只信配置的上限：历史数据/后台改小上限后可能残留更大的值 */
    private static int clampPurchased(Integer stored, SubscriptionConfigDTO config) {
        var value = stored == null ? 0 : stored;
        return Math.max(0, Math.min(value, config.getMaxQuota()));
    }

    /**
     * 额度状态快照。
     *
     * @param config 额度配置
     * @param purchasedQuota 已购额度（已按配置上限收敛）
     * @param balance 积分余额
     * @param memberActive 会员是否有效
     * @param memberExpireAt 会员到期时间（非会员为 null）
     * @param limit 当前订阅上限
     */
    public record QuotaState(SubscriptionConfigDTO config, int purchasedQuota, long balance,
                             boolean memberActive, Instant memberExpireAt, int limit) {
    }
}
