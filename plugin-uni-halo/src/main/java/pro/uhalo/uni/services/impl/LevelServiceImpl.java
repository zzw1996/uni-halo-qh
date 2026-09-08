package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import pro.uhalo.uni.dto.TitleItemDTO;
import pro.uhalo.uni.dto.UnlockedTitleDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.LevelService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 等级/称号/装扮实现：累计获得积分来自 PointsRecord（points>0 求和），
 * 称号统计口径与积分流水一致（invite 统计来自 WxUser 邀请关系）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LevelServiceImpl implements LevelService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int OPERATE_RATE_LIMIT_PER_MINUTE = 15;

    private final PointsPersistence persistence;
    private final WxUserService wxUserService;
    private final ReactiveSettingFetcher settingFetcher;
    private final RateLimiter rateLimiter;

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
                    .map(account -> OutfitStatusDTO.builder()
                        .enabled(config.isEnabled())
                        .items(config.getOutfits())
                        .owned(parseOwned(account.getOutfitOwned()))
                        .avatarFrame(account.getActiveAvatarFrame())
                        .nickFrame(account.getActiveNickFrame())
                        .theme(account.getActiveTheme())
                        .build())));
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
                                var owned = new ArrayList<>(parseOwned(account.getOutfitOwned()));
                                if (owned.contains(normalized)) {
                                    return Mono.just(OutfitBuyResponse.builder()
                                        .owned(owned)
                                        .balance(safeBalance(account))
                                        .message("已拥有该装扮")
                                        .build());
                                }
                                var balance = safeBalance(account);
                                if (balance < item.getPoints()) {
                                    return Mono.error(new WxAuthException(
                                        "积分不足，需要 " + item.getPoints() + " 积分"));
                                }
                                var newBalance = balance - item.getPoints();
                                return resolveWxUserId(id, account)
                                    .flatMap(wxUserId -> {
                                        account.setBalance(newBalance);
                                        if (StringUtils.hasText(wxUserId)
                                            && !wxUserId.equals(account.getWxUserId())) {
                                            account.setWxUserId(wxUserId);
                                        }
                                        owned.add(normalized);
                                        account.setOutfitOwned(writeOwned(owned));
                                        var earnKey = PointsServiceImpl.buildEarnKey(id, "outfit_" + normalized,
                                            "own");
                                        var record = PointsPersistence.newRecord(id, "outfit_" + normalized,
                                            -item.getPoints(), newBalance, "购买装扮·" + item.getName(),
                                            normalized, earnKey, wxUserId);
                                        return persistence.saveAccount(account)
                                            .then(persistence.appendRecord(record))
                                            .thenReturn(OutfitBuyResponse.builder()
                                                .owned(owned)
                                                .balance(newBalance)
                                                .message("已获得 · " + item.getName())
                                                .build())
                                            .onErrorResume(ex -> isDuplicateRecordError(ex)
                                                ? Mono.just(OutfitBuyResponse.builder()
                                                    .owned(owned)
                                                    .balance(newBalance)
                                                    .message("已拥有该装扮")
                                                    .build())
                                                : Mono.error(ex));
                                    });
                            });
                    });
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
                            if (!parseOwned(account.getOutfitOwned()).contains(normalized)) {
                                return Mono.error(new WxAuthException("请先购买该装扮"));
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
                var leveled = resolveLevel(config, totalEarned);
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

    private record Leveled(int level, String title, int currentMin, int nextMin) {
    }

    private static Leveled resolveLevel(LevelConfigDTO config, long totalEarned) {
        var levels = config.getLevels();
        if (levels.isEmpty()) {
            return new Leveled(1, "新手上路", 0, 0);
        }
        var current = levels.get(0);
        for (var item : levels) {
            if (totalEarned >= item.getMinPoints()) {
                current = item;
            }
        }
        var picked = current;
        var next = levels.stream()
            .filter(item -> item.getLevel() > picked.getLevel())
            .findFirst()
            .orElse(null);
        return new Leveled(picked.getLevel(), picked.getTitle(), picked.getMinPoints(),
            next == null ? 0 : next.getMinPoints());
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

    private static List<String> parseOwned(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            var parsed = MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String writeOwned(List<String> owned) {
        try {
            return MAPPER.writeValueAsString(owned);
        } catch (Exception ex) {
            return "[]";
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
