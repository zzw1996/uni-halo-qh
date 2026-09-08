package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.ClaimDailyTaskResponse;
import pro.uhalo.uni.dto.DailyTaskConfigDTO;
import pro.uhalo.uni.dto.DailyTaskItemDTO;
import pro.uhalo.uni.dto.DailyTaskProgressItemDTO;
import pro.uhalo.uni.dto.DailyTasksResponseDTO;
import pro.uhalo.uni.enums.PointsAction;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.DailyTaskService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.DailyTaskConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxUserIds;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每日任务实现：任务配置来自 dailyTaskConfig 设置分组，进度基于当日积分流水统计。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyTaskServiceImpl implements DailyTaskService {

    private static final String TASK_ACTION = PointsAction.TASK.value();
    private static final int CLAIM_RATE_LIMIT_PER_MINUTE = 20;

    private final PointsPersistence persistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;
    private final RateLimiter rateLimiter;

    @Override
    public Mono<DailyTasksResponseDTO> getDailyTasks(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> persistence.listRecordsByOpenid(id)
                    .flatMap(records -> persistence.getOrCreateAccount(id)
                        .map(account -> buildResponse(config, records, account)))));
    }

    @Override
    public Mono<ClaimDailyTaskResponse> claim(String openid, String type) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "task-claim", CLAIM_RATE_LIMIT_PER_MINUTE, "领取过于频繁，请稍后再试");
                var normalizedType = StringUtils.hasText(type) ? type.trim().toLowerCase() : "";
                if (!DailyTaskConfigHelper.VALID_TYPES.contains(normalizedType)) {
                    return Mono.error(new WxAuthException("不支持的任务类型：" + normalizedType));
                }
                return loadConfig()
                    .flatMap(config -> {
                        if (!config.isEnabled()) {
                            return Mono.error(new WxAuthException("每日任务未开启"));
                        }
                        var task = findEnabledTask(config, normalizedType);
                        if (task == null) {
                            return Mono.error(new WxAuthException("任务不存在或未启用"));
                        }
                        var today = PointsServiceImpl.today();
                        var earnKey = PointsServiceImpl.buildDailyEarnKey(id, TASK_ACTION,
                            normalizedType, LocalDate.parse(today));
                        return persistence.findRecordByEarnKey(earnKey)
                            .flatMap(existing -> buildClaimedResponse(id))
                            .switchIfEmpty(Mono.defer(() -> persistence.listRecordsByOpenid(id)
                                .flatMap(records -> {
                                    var progress = countToday(records, normalizedType, today);
                                    if (progress < task.getTarget()) {
                                        return Mono.error(new WxAuthException(
                                            "今日进度尚未完成，先完成任务再领取吧"));
                                    }
                                    return persistence.getOrCreateAccount(id)
                                        .flatMap(account -> processClaim(id, account, task, earnKey));
                                })));
                    });
            });
    }

    private Mono<ClaimDailyTaskResponse> processClaim(String openid, UserPoints account,
        DailyTaskItemDTO task, String earnKey) {
        var reward = task.getPoints();
        var newBalance = PointsServiceImpl.safeBalance(account) + reward;
        return resolveWxUserId(openid, account)
            .flatMap(wxUserId -> {
                account.setBalance(newBalance);
                if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                    account.setWxUserId(wxUserId);
                }
                var record = PointsPersistence.newRecord(openid, TASK_ACTION, reward, newBalance,
                    "每日任务·" + PointsAction.labelOf(task.getType()), task.getType(), earnKey, wxUserId);
                return persistence.saveAccount(account)
                    .then(persistence.appendRecord(record))
                    .thenReturn(ClaimDailyTaskResponse.builder()
                        .earned(reward)
                        .balance(newBalance)
                        .message("+" + reward + " 积分")
                        .build())
                    .onErrorResume(ex -> isDuplicateRecordError(ex)
                        ? buildClaimedResponse(openid)
                        : Mono.error(ex));
            });
    }

    private Mono<ClaimDailyTaskResponse> buildClaimedResponse(String openid) {
        return persistence.getOrCreateAccount(openid)
            .map(account -> ClaimDailyTaskResponse.builder()
                .earned(0)
                .balance(PointsServiceImpl.safeBalance(account))
                .message("今日奖励已领取")
                .build());
    }

    private DailyTasksResponseDTO buildResponse(DailyTaskConfigDTO config,
        List<PointsRecord> records, UserPoints account) {
        var today = PointsServiceImpl.today();
        var todayRecords = records.stream()
            .filter(record -> PointsServiceImpl.isSameDay(record.getCreateTime(), today))
            .toList();
        var todayEarned = todayRecords.stream()
            .mapToLong(record -> record.getPoints() == null ? 0 : Math.max(0, record.getPoints()))
            .sum();
        var claimedTypes = todayRecords.stream()
            .filter(record -> TASK_ACTION.equals(record.getAction()))
            .map(PointsRecord::getRefName)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
        var items = config.getTasks().stream()
            .filter(DailyTaskItemDTO::isEnabled)
            .map(task -> {
                var progress = countToday(todayRecords, task.getType());
                return DailyTaskProgressItemDTO.builder()
                    .type(task.getType())
                    .label(PointsAction.labelOf(task.getType()))
                    .target(task.getTarget())
                    .progress(Math.min(progress, task.getTarget()))
                    .points(task.getPoints())
                    .done(progress >= task.getTarget())
                    .claimed(claimedTypes.contains(task.getType()))
                    .build();
            })
            .toList();
        return DailyTasksResponseDTO.builder()
            .date(today)
            .todayEarned(todayEarned)
            .balance(PointsServiceImpl.safeBalance(account))
            .items(items)
            .build();
    }

    private static int countToday(List<PointsRecord> records, String action, String today) {
        return (int) records.stream()
            .filter(record -> action.equals(record.getAction()))
            .filter(record -> PointsServiceImpl.isSameDay(record.getCreateTime(), today))
            .count();
    }

    private static int countToday(List<PointsRecord> todayRecords, String action) {
        return (int) todayRecords.stream()
            .filter(record -> action.equals(record.getAction()))
            .count();
    }

    private static DailyTaskItemDTO findEnabledTask(DailyTaskConfigDTO config, String type) {
        return config.getTasks().stream()
            .filter(DailyTaskItemDTO::isEnabled)
            .filter(task -> type.equals(task.getType()))
            .findFirst()
            .orElse(null);
    }

    private Mono<DailyTaskConfigDTO> loadConfig() {
        return settingFetcher.get(DailyTaskConfigHelper.groupName())
            .map(DailyTaskConfigHelper::parse)
            .defaultIfEmpty(DailyTaskConfigHelper.defaults());
    }

    private Mono<String> resolveWxUserId(String openid, UserPoints account) {
        if (account != null && StringUtils.hasText(account.getWxUserId())) {
            return Mono.just(account.getWxUserId().trim());
        }
        return wxUserService.fetchByOpenid(openid)
            .map(WxUserIds::resolveId)
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
