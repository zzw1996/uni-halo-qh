package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.EquipOutfitResponse;
import pro.uhalo.uni.dto.LevelConfigDTO;
import pro.uhalo.uni.dto.LevelItemDTO;
import pro.uhalo.uni.dto.LevelStatusDTO;
import pro.uhalo.uni.dto.OutfitBuyResponse;
import pro.uhalo.uni.dto.OutfitItemDTO;
import pro.uhalo.uni.dto.OutfitStatusDTO;
import pro.uhalo.uni.dto.OwnedOutfitDetailDTO;
import pro.uhalo.uni.dto.TitleItemDTO;
import pro.uhalo.uni.dto.UnlockedTitleDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.LevelService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 等级/称号/装扮实现：累计获得积分来自 PointsRecord（points>0 求和），
 * 称号统计口径与积分流水一致（invite 统计来自 WxUser 邀请关系）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LevelServiceImpl implements LevelService {

    private static final int OPERATE_RATE_LIMIT_PER_MINUTE = 15;

    private final PointsPersistence persistence;
    private final WxUserService wxUserService;
    private final ReactiveSettingFetcher settingFetcher;
    private final RateLimiter rateLimiter;
    private final PayOrderPersistence payOrderPersistence;

    @Override
    public Mono<LevelStatusDTO> getLevelStatus(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> persistence.getOrCreateAccount(id)
                    .flatMap(account -> persistence.listRecordsByOpenid(id)
                        .flatMap(records -> buildLevelStatus(id, config, account, records)))));
    }

    @Override
    public Mono<LevelStatusDTO> wearTitle(String openid, String titleId) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "wear-title", OPERATE_RATE_LIMIT_PER_MINUTE, "操作过于频繁，请稍后再试");
                var normalized = StringUtils.hasText(titleId) ? titleId.trim() : "";
                if (normalized.isEmpty()) {
                    return Mono.error(new WxAuthException("titleId 不能为空"));
                }
                return loadConfig()
                    .flatMap(config -> persistence.getOrCreateAccount(id)
                        .flatMap(account -> persistence.listRecordsByOpenid(id)
                            .flatMap(records -> countStats(id, records)
                                .flatMap(stats -> {
                                    var title = config.getTitles().stream()
                                        .filter(t -> normalized.equals(t.getId()))
                                        .findFirst()
                                        .orElse(null);
                                    if (title == null) {
                                        return Mono.error(new WxAuthException("称号不存在"));
                                    }
                                    var progress = progressOf(stats, title.getType(), title.getTarget(),
                                        id);
                                    if (progress < title.getTarget()) {
                                        return Mono.error(new WxAuthException("该称号尚未解锁"));
                                    }
                                    account.setWornTitleId(normalized);
                                    return persistence.saveAccount(account)
                                        .then(buildLevelStatus(id, config, account, records));
                                }))));
            });
    }

    @Override
    public Mono<OutfitStatusDTO> getOutfitStatus(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> persistence.getOrCreateAccount(id)
                    .flatMap(account -> totalSpentCents(id)
                        .map(totalSpentCents -> {
                            // 限时结构：有效期内才视为已拥有；过期项保留在原始数据中（防误清），
                            // 但不下发到前端 owned，用户可重新购买。
                            var validOwned = OwnedOutfitParser.parse(account.getOutfitOwned()).stream()
                                .filter(OwnedOutfitParser.OwnedOutfit::isStillValid)
                                .toList();
                            var configuredKeys = config.getOutfits() == null ? Set.<String>of()
                                : config.getOutfits().stream()
                                    .map(OutfitItemDTO::getKey)
                                    .collect(Collectors.toSet());
                            return OutfitStatusDTO.builder()
                                .enabled(config.isEnabled())
                                .items(config.getOutfits())
                                .owned(validOwned.stream()
                                    .map(OwnedOutfitParser.OwnedOutfit::key)
                                    .toList())
                                // 明细含到期时间：前端据此展示「剩余 N 天 / 永久」
                                .ownedDetails(validOwned.stream()
                                    .map(item -> OwnedOutfitDetailDTO.builder()
                                        .key(item.key())
                                        .expireAt(item.expireAt())
                                        .permanent(item.expireAt() == null)
                                        .build())
                                    .toList())
                                // 已拥有但配置中已删除的装扮：单独列出，避免「买过的东西凭空消失」
                                .orphanOwned(validOwned.stream()
                                    .map(OwnedOutfitParser.OwnedOutfit::key)
                                    .filter(key -> !configuredKeys.contains(key))
                                    .toList())
                                .avatarFrame(account.getActiveAvatarFrame())
                                .nickFrame(account.getActiveNickFrame())
                                .theme(account.getActiveTheme())
                                .totalSpentCents(totalSpentCents)
                                .build();
                        }))));
    }

    /**
     * 累计消费金额（分）：只统计已发货订单。
     *
     * <p>待支付与需退款的一律不计——用「已付款但权益没发放」的订单去解锁里程碑装扮，
     * 用户会觉得门槛判定有问题。读取失败按 0 处理：里程碑只是展示层，
     * 不能因为订单表读不到让整个装扮商店打不开。</p>
     */
    private Mono<Long> totalSpentCents(String openid) {
        return payOrderPersistence.listByOpenid(openid)
            .filter(order -> "delivered".equals(order.getStatus()))
            .map(order -> {
                var price = order.getPrice() == null ? 0L : Math.max(0L, order.getPrice());
                var quantity = order.getQuantity() == null ? 1 : Math.max(1, order.getQuantity());
                return price * quantity;
            })
            .reduce(0L, Long::sum)
            .onErrorResume(ex -> {
                log.warn("统计累计消费失败，按 0 处理: openid={}, {}", openid, ex.getMessage());
                return Mono.just(0L);
            });
    }

    @Override
    public Mono<OutfitBuyResponse> buyOutfit(String openid, String key) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "buy-outfit", OPERATE_RATE_LIMIT_PER_MINUTE, "操作过于频繁，请稍后再试");
                var normalized = StringUtils.hasText(key) ? key.trim() : "";
                if (normalized.isEmpty()) {
                    return Mono.error(new WxAuthException("key 不能为空"));
                }
                return loadConfig()
                    .flatMap(config -> {
                        var item = findOutfit(config, normalized);
                        if (item == null) {
                            return Mono.error(new WxAuthException("装扮不存在"));
                        }
                        return persistence.getOrCreateAccount(id)
                            .flatMap(account -> {
                                var owned = OwnedOutfitParser.parse(account.getOutfitOwned());
                                // 已拥有且有效期内：拦截（过期视为未拥有，可重新购买）。
                                if (OwnedOutfitParser.isOwnedAndValid(owned, normalized)) {
                                    return Mono.just(OutfitBuyResponse.builder()
                                        .owned(owned.stream().map(OwnedOutfitParser.OwnedOutfit::key).toList())
                                        .balance(safeBalance(account))
                                        .message("已拥有该装扮")
                                        .build());
                                }
                                // 消费里程碑：门槛未达成时不允许兑换。
                                // 只对配了门槛的装扮查订单，避免给普通装扮白加一次查询。
                                return ensureMilestoneReached(id, item).then(Mono.defer(() -> {
                                    var balance = safeBalance(account);
                                    if (balance < item.getPoints()) {
                                        return Mono.error(new WxAuthException(
                                            "积分不足，需要 " + item.getPoints() + " 积分"));
                                    }
                                    var newBalance = balance - item.getPoints();
                                    return resolveWxUserId(id, account)
                                        .flatMap(wxUserId -> {
                                            var earnKey = PointsServiceImpl.buildEarnKey(id, "outfit_" + normalized,
                                                "own");
                                            var record = PointsPersistence.newRecord(id, "outfit_" + normalized,
                                                -item.getPoints(), newBalance, "购买装扮·" + item.getName(),
                                                normalized, earnKey, wxUserId);
                                            // 关键：先写幂等流水（create 冲突即幂等闸门），成功后再改账户。
                                            // 原实现先 saveAccount 后 appendRecord，并发下两个请求读到相同
                                            // owned/balance 各自扣分却只落一条流水，属"扣了分没记账"缺陷。
                                            // 流水 create 冲突（重复提交）直接返回"已拥有"，不改账户。
                                            return persistence.appendRecord(record)
                                                .onErrorResume(ex -> isDuplicateRecordError(ex)
                                                    ? Mono.error(new WxAuthException("请勿重复提交"))
                                                    : Mono.error(ex))
                                                .then(persistence.getOrCreateAccount(id))
                                                .flatMap(latest -> {
                                                    var latestOwned = new ArrayList<>(
                                                        OwnedOutfitParser.parse(latest.getOutfitOwned()));
                                                    if (OwnedOutfitParser.isOwnedAndValid(latestOwned, normalized)) {
                                                        return Mono.just(OutfitBuyResponse.builder()
                                                            .owned(latestOwned.stream()
                                                                .map(OwnedOutfitParser.OwnedOutfit::key).toList())
                                                            .balance(safeBalance(latest))
                                                            .message("已拥有该装扮")
                                                            .build());
                                                    }
                                                    latest.setBalance(newBalance);
                                                    if (StringUtils.hasText(wxUserId)
                                                        && !wxUserId.equals(latest.getWxUserId())) {
                                                        latest.setWxUserId(wxUserId);
                                                    }
                                                    var expireAt = resolveOutfitExpireAt(item);
                                                    latestOwned.add(new OwnedOutfitParser.OwnedOutfit(
                                                        normalized, Instant.now().getEpochSecond(), expireAt));
                                                    latest.setOutfitOwned(OwnedOutfitParser.write(latestOwned));
                                                    return persistence.saveAccount(latest)
                                                        .thenReturn(OutfitBuyResponse.builder()
                                                            .owned(latestOwned.stream()
                                                                .map(OwnedOutfitParser.OwnedOutfit::key).toList())
                                                            .balance(newBalance)
                                                            .message("已获得 · " + item.getName())
                                                            .build());
                                                });
                                        });
                                }));
                            });
                    });
            });
    }

    /** 计算到期时间：durationDays<=0 视为永久（null）。 */
    private static Long resolveOutfitExpireAt(OutfitItemDTO item) {
        if (item.getDurationDays() <= 0) {
            return null;
        }
        return Instant.now().getEpochSecond() + item.getDurationDays() * 86400L;
    }

    /**
     * 校验消费里程碑门槛：累计消费不足时不允许兑换。
     *
     * <p>只对配了 {@code minSpend} 的装扮查订单——普通装扮走这里就是一次空操作，
     * 不给最常用的兑换路径白加一次数据库查询。前端同样会按门槛显示锁定态，
     * 这里的校验是防绕过（前端算的是展示，服务端才是判定）。</p>
     */
    private Mono<Void> ensureMilestoneReached(String openid, OutfitItemDTO item) {
        if (item.getMinSpend() <= 0) {
            return Mono.empty();
        }
        return totalSpentCents(openid).flatMap(spentCents -> {
            var thresholdCents = item.getMinSpend() * 100L;
            if (spentCents < thresholdCents) {
                return Mono.error(new WxAuthException(String.format(
                    "该装扮为消费回馈，累计消费满 %d 元可解锁（当前 %.2f 元）",
                    item.getMinSpend(), spentCents / 100.0)));
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<EquipOutfitResponse> equipOutfit(String openid, String key) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "equip-outfit", OPERATE_RATE_LIMIT_PER_MINUTE, "操作过于频繁，请稍后再试");
                var normalized = StringUtils.hasText(key) ? key.trim() : "";
                return loadConfig()
                    .flatMap(config -> persistence.getOrCreateAccount(id)
                        .flatMap(account -> {
                            if (normalized.isEmpty()) {
                                account.setActiveAvatarFrame(null);
                                account.setActiveNickFrame(null);
                                account.setActiveTheme(null);
                                return persistence.saveAccount(account)
                                    .thenReturn(EquipOutfitResponse.builder()
                                        .avatarFrame(null)
                                        .nickFrame(null)
                                        .theme(null)
                                        .message("已脱下装扮")
                                        .build());
                            }
                            var item = findOutfit(config, normalized);
                            if (item == null) {
                                return Mono.error(new WxAuthException("装扮不存在"));
                            }
                            var ownedList = OwnedOutfitParser.parse(account.getOutfitOwned());
                            var active = ownedList.stream()
                                .filter(entry -> normalized.equals(entry.key())
                                    && entry.isStillValid())
                                .findFirst()
                                .orElse(null);
                            if (active == null) {
                                // 已过期或未购买：一律回退脱装扮，避免残留过期装扮的视觉效果。
                                account.setActiveAvatarFrame(null);
                                account.setActiveNickFrame(null);
                                account.setActiveTheme(null);
                                return persistence.saveAccount(account)
                                    .thenReturn(EquipOutfitResponse.builder()
                                        .avatarFrame(null)
                                        .nickFrame(null)
                                        .theme(null)
                                        .message("该装扮已过期或尚未购买，请重新获取")
                                        .build());
                            }
                            applyActive(item.getType(), item.getStyle(), account);
                            return persistence.saveAccount(account)
                                .thenReturn(EquipOutfitResponse.builder()
                                    .avatarFrame(account.getActiveAvatarFrame())
                                    .nickFrame(account.getActiveNickFrame())
                                    .theme(account.getActiveTheme())
                                    .message("已穿戴「" + item.getName() + "」")
                                    .build());
                        }));
            });
    }

    private Mono<LevelStatusDTO> buildLevelStatus(String openid, LevelConfigDTO config, UserPoints account,
        List<PointsRecord> records) {
        return countStats(openid, records)
            .map(stats -> {
                var totalEarned = records.stream()
                    .mapToLong(r -> r.getPoints() == null ? 0 : Math.max(0, r.getPoints()))
                    .sum();
                var leveled = LevelConfigHelper.resolveLevel(config, totalEarned);
                var titles = config.getTitles().stream()
                    .map(title -> {
                        var progress = (int) Math.min(title.getTarget(),
                            progressOf(stats, title.getType(), title.getTarget(), openid));
                        var unlocked = progress >= title.getTarget();
                        return UnlockedTitleDTO.builder()
                            .id(title.getId())
                            .name(title.getName())
                            .type(title.getType())
                            .desc(title.getDesc())
                            .target(title.getTarget())
                            .progress(progress)
                            .unlocked(unlocked)
                            .worn(normalizeWorn(account.getWornTitleId()).equals(title.getId()) && unlocked)
                            .build();
                    })
                    .toList();
                return LevelStatusDTO.builder()
                    .enabled(config.isEnabled())
                    .level(leveled.level())
                    .levelTitle(leveled.title())
                    .totalEarned(totalEarned)
                    .currentMin(leveled.currentMin())
                    .nextMin(leveled.nextMin())
                    .nextPoints(leveled.nextMin() <= 0 ? 0
                        : (int) Math.max(0, leveled.nextMin() - totalEarned))
                    .wornTitleId(config.getTitles().stream().anyMatch(t -> normalizeWorn(account.getWornTitleId())
                        .equals(t.getId())) ? normalizeWorn(account.getWornTitleId()) : "")
                    .unlockedTitles(titles)
                    .build();
            });
    }

    private static String normalizeWorn(String wornTitleId) {
        return StringUtils.hasText(wornTitleId) ? wornTitleId.trim() : "";
    }

    /** 统计各指标的完成进度，invite 类型需查邀请关系 */
    private Mono<CountStats> countStats(String openid, List<PointsRecord> records) {
        var totalEarned = records.stream()
            .mapToLong(r -> r.getPoints() == null ? 0 : Math.max(0, r.getPoints()))
            .sum();
        var counts = new CountStats(
            countByAction(records, "browse"),
            countByAction(records, "sign_in"),
            countByAction(records, "vote"),
            countByAction(records, "comment"),
            0L,
            totalEarned
        );
        return wxUserService.countByInviterOpenid(openid)
            .onErrorResume(ex -> {
                log.warn("count invites failed: {}", ex.getMessage());
                return Mono.just(0L);
            })
            .map(counts::withInvites);
    }

    private static long progressOf(CountStats stats, String type, int target, String openid) {
        return switch (type) {
            case "total_browse" -> stats.browse();
            case "sign_in" -> stats.signIn();
            case "vote" -> stats.vote();
            case "comment" -> stats.comment();
            case "invite" -> stats.invites();
            case "total_points" -> stats.totalEarned();
            default -> 0L;
        };
    }

    private static long countByAction(List<PointsRecord> records, String action) {
        return records.stream().filter(r -> action.equals(r.getAction())).count();
    }

    private record CountStats(long browse, long signIn, long vote, long comment, long invites,
        long totalEarned) {

        CountStats withInvites(long value) {
            return new CountStats(browse, signIn, vote, comment, value, totalEarned);
        }
    }

    private static OutfitItemDTO findOutfit(LevelConfigDTO config, String key) {
        return config.getOutfits().stream()
            .filter(item -> key.equals(item.getKey()))
            .findFirst()
            .orElse(null);
    }

    private static void applyActive(String type, String style, UserPoints account) {
        switch (type) {
            case "avatar_frame" -> account.setActiveAvatarFrame(style);
            case "nick_frame" -> account.setActiveNickFrame(style);
            case "theme" -> account.setActiveTheme(style);
            default -> throw new WxAuthException("不支持的装扮类型：" + type);
        }
    }

    private Mono<LevelConfigDTO> loadConfig() {
        return settingFetcher.get(LevelConfigHelper.groupName())
            .map(LevelConfigHelper::parse)
            .defaultIfEmpty(LevelConfigHelper.defaults());
    }

    private Mono<String> resolveWxUserId(String openid, UserPoints account) {
        if (account != null && StringUtils.hasText(account.getWxUserId())) {
            return Mono.just(account.getWxUserId().trim());
        }
        return wxUserService.fetchByOpenid(openid)
            .map(pro.uhalo.uni.utils.WxUserIds::resolveId)
            .filter(StringUtils::hasText)
            .defaultIfEmpty("");
    }

    private void ensureRateLimit(String openid, String scene, int limit, String message) {
        if (!rateLimiter.tryAcquire(openid + ":" + scene, limit, Duration.ofMinutes(1))) {
            throw new WxAuthException(message);
        }
    }

    private static Mono<String> requireOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        return Mono.just(openid.trim());
    }

    private static long safeBalance(UserPoints account) {
        return account.getBalance() == null ? 0L : Math.max(0L, account.getBalance());
    }

    private static boolean isDuplicateRecordError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }
}
