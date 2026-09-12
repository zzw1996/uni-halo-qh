package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.utils.DigestConfigHelper;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 日报定时器。
 *
 * <p>不直接把 cron 写死在 {@code @Scheduled} 里，而是每分钟检查一次「今天该跑的时刻是否已到、
 * 该时刻今天是否还没跑过」。这样后台改执行时刻立刻生效（不用重启插件），
 * 插件重启或服务器短暂离线导致错过时间点时也能在下次检查时补跑。</p>
 *
 * <p>日志按「状态变化才打」的方式输出：每分钟一次的检查不会刷屏，但开关一改、
 * 执行时刻一改、下一次执行时间一算出来就能在 Halo 日志里看到，便于定位「为什么没跑」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestScheduler implements InitializingBean {

    /** 每分钟检查一次；首次延迟 90 秒，避开 Halo 启动期 */
    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 90_000L;

    /** 超过这个时长还停留在 RUNNING 的记录视为陈旧（进程崩溃/重启留下的），允许重跑 */
    private static final Duration STALE_RUN_WINDOW = Duration.ofMinutes(30);

    /** 同一时段每天最多尝试次数，避免持续失败时每分钟重试烧掉 AI 额度 */
    private static final int MAX_ATTEMPTS_PER_SLOT = 2;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReactiveSettingFetcher settingFetcher;
    private final DigestPersistence persistence;
    private final DigestService digestService;

    /** 上一次打过的「跳过原因」：只有原因变化时才写日志，避免每分钟刷屏 */
    private volatile String lastSkipReason = "";

    @Override
    public void afterPropertiesSet() {
        log.info("[UniHalo][Digest] AI 日报定时器已注册：每 {} 秒检查一次执行时刻",
            CHECK_INTERVAL_MS / 1000);
        loadConfig()
            .doOnNext(config -> log.info(
                "[UniHalo][Digest] 当前配置：开启={} 定时开关={} 执行时刻=[{}] 时区={} 自动发布={}",
                config.isEnabled(), config.isScheduleEnabled(), config.getScheduleTimes(),
                config.getTimezone(), config.isPublishEnabled()))
            .flatMap(config -> computeNextRun(config)
                .map(next -> "[UniHalo][Digest] 下次计划执行时间："
                    + next.atZone(DigestConfigHelper.zoneOf(config.getTimezone()))
                        .format(DATE_TIME_FORMAT)
                    + "（" + config.getTimezone() + "）")
                .defaultIfEmpty("[UniHalo][Digest] 定时执行未启用（开关关闭），不会自动生成日报"))
            .subscribe(message -> log.info(message),
                ex -> log.warn("[UniHalo][Digest] 启动时读取日报配置失败：{}", ex.getMessage()));
    }

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void checkAndRun() {
        checkAndRunOnce()
            .subscribe(message -> {
                if (StringUtils.hasText(message)) {
                    log.info("[UniHalo][Digest] {}", message);
                }
            }, ex -> log.warn("[UniHalo][Digest] 定时检查异常：{}", ex.getMessage()));
    }

    /**
     * 单次检查（定时任务实体，独立出来便于测试）。
     *
     * <p>返回非空字符串表示这次真的跑了一轮，内容用于日志。</p>
     */
    Mono<String> checkAndRunOnce() {
        return loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                logSkipOnce("AI 日报未开启（插件设置 - AI 日报 - 开启 AI 日报），跳过本次检查");
                return Mono.empty();
            }
            if (!config.isScheduleEnabled()) {
                logSkipOnce("定时生成已关闭（仅支持后台手动生成），跳过本次检查");
                return Mono.empty();
            }
            if (digestService.isRunning()) {
                logSkipOnce("已有一次日报生成任务在执行中，本次跳过");
                return Mono.empty();
            }
            var zone = DigestConfigHelper.zoneOf(config.getTimezone());
            var now = now(zone);
            var times = DigestConfigHelper.parseScheduleTimes(config.getScheduleTimes());
            var dueSlot = latestDueSlot(times, now.toLocalTime());
            if (dueSlot == null) {
                logSkipOnce("当前 " + now.format(SLOT_FORMAT) + " 未到执行时刻（计划 "
                    + config.getScheduleTimes() + "，时区 " + config.getTimezone() + "），跳过本次检查");
                return Mono.empty();
            }
            var slot = dueSlot.format(SLOT_FORMAT);
            return persistence.listByDate(now.format(DATE_FORMAT))
                .flatMap(reports -> {
                    if (shouldSkip(reports, slot, now)) {
                        logSkipOnce("今日 " + slot + " 时段已处理过（" + describe(reports, slot)
                            + "），跳过本次检查");
                        return Mono.empty();
                    }
                    resetSkipReason();
                    log.info("[UniHalo][Digest] 定时任务触发：slot={} 当前时间={} 时区={} 自动发布={}",
                        slot, now.format(DATE_TIME_FORMAT), config.getTimezone(),
                        config.isPublishEnabled());
                    return digestService.run(slot, config.isPublishEnabled())
                        .map(report -> "定时任务结束 slot=" + slot + " 状态=" + report.getStatus()
                            + (StringUtils.hasText(report.getError())
                            ? " 说明=" + report.getError() : ""));
                });
        });
    }

    /**
     * 下一次计划执行时间，给后台页面展示用；未开启定时执行时为空。
     */
    public Mono<Instant> nextRunInstant() {
        return loadConfig().flatMap(this::computeNextRun);
    }

    private Mono<Instant> computeNextRun(DigestConfigDTO config) {
        if (!config.isEnabled() || !config.isScheduleEnabled()) {
            return Mono.empty();
        }
        var zone = DigestConfigHelper.zoneOf(config.getTimezone());
        var now = now(zone);
        var times = DigestConfigHelper.parseScheduleTimes(config.getScheduleTimes());
        for (var time : times) {
            if (time.isAfter(now.toLocalTime())) {
                return Mono.just(now.toLocalDate().atTime(time).atZone(zone).toInstant());
            }
        }
        return Mono.just(now.toLocalDate().plusDays(1).atTime(times.get(0))
            .atZone(zone).toInstant());
    }

    /** 日志去重：同样的原因只打一次 */
    private void logSkipOnce(String reason) {
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            log.info("[UniHalo][Digest] {}", reason);
        }
    }

    private void resetSkipReason() {
        lastSkipReason = "";
    }

    /**
     * 今天已经到点、且最靠后的那个执行时刻；还没到任何时刻时返回 null。
     */
    private static LocalTime latestDueSlot(List<LocalTime> times, LocalTime current) {
        LocalTime due = null;
        for (var time : times) {
            if (!time.isAfter(current) && (due == null || time.isAfter(due))) {
                due = time;
            }
        }
        return due;
    }

    private static boolean shouldSkip(List<DigestReport> reports, String slot, ZonedDateTime now) {
        var forSlot = reports.stream().filter(report -> slot.equals(report.getSlot())).toList();
        if (forSlot.stream().anyMatch(report -> "SUCCESS".equals(report.getStatus()))) {
            return true;
        }
        var staleBefore = now.toInstant().minus(STALE_RUN_WINDOW);
        if (forSlot.stream().anyMatch(report -> "RUNNING".equals(report.getStatus())
            && report.getStartedAt() != null && report.getStartedAt().isAfter(staleBefore))) {
            return true;
        }
        // 失败、或陈旧的 RUNNING：允许重试，但一天最多 MAX_ATTEMPTS_PER_SLOT 次
        return forSlot.size() >= MAX_ATTEMPTS_PER_SLOT;
    }

    /** 把该时段已有的记录状态拼成一句人话，方便看出「为什么跳过」 */
    private static String describe(List<DigestReport> reports, String slot) {
        var states = reports.stream()
            .filter(report -> slot.equals(report.getSlot()))
            .map(report -> {
                var status = report.getStatus();
                var state = "SUCCESS".equals(status) ? "成功"
                    : "FAILED".equals(status) ? "失败"
                    : "RUNNING".equals(status) ? "运行中" : status;
                return state + (StringUtils.hasText(report.getError())
                    ? "：" + report.getError() : "");
            })
            .collect(Collectors.joining("；"));
        return StringUtils.hasText(states) ? states : "无记录";
    }

    /** 当前时间；独立成方法便于测试注入固定时钟 */
    ZonedDateTime now(ZoneId zone) {
        return ZonedDateTime.now(zone);
    }

    private Mono<DigestConfigDTO> loadConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }
}
