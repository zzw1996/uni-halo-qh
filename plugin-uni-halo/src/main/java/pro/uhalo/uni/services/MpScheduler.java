package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.MpIssue;
import pro.uhalo.uni.utils.MpConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 公众号文章定时生成器。
 *
 * <p>与日报同一套「每分钟检查该时刻是否已到、今天是否已生成」的模式（不写死 cron，
 * 后台改时刻立刻生效；错过时间点会在下次检查补跑）。幂等键是「日期 + 方向」的记录：
 * 只补没生成过或失败未超上限的方向，已经生成好的方向不会被重跑（不重复烧 AI 额度）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpScheduler implements InitializingBean {

    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 150_000L;

    /** 同一方向每天最多尝试次数：失败后不再无限重试（每天最多烧两轮 AI） */
    static final int MAX_ATTEMPTS_PER_DAY = 2;

    /**
     * 执行窗口：只在该时刻后的这段时间内触发，错过就等下一个时刻。
     *
     * <p>刻意不做「全天补跑」：公众号文章生成要花 AI 额度、产出的是准备发出去的稿子，
     * 只有站长点「立即生成」（或正好在窗口内）才该跑。之前的实现只要「当天时刻已过且还没生成」
     * 就立刻开跑，表现成「随便什么时候打开管理页都看到它在生成」。</p>
     */
    static final Duration GRACE_WINDOW = Duration.ofMinutes(30);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReactiveSettingFetcher settingFetcher;
    private final MpIssueStore store;
    private final MpIssueService issueService;

    private volatile String lastSkipReason = "";

    @Override
    public void afterPropertiesSet() {
        log.info("[UniHalo][Mp] 公众号定时器已注册：每 {} 秒检查一次执行时刻",
            CHECK_INTERVAL_MS / 1000);
        loadConfig()
            .doOnNext(config -> log.info(
                "[UniHalo][Mp] 当前配置：开启={} 定时={} 执行时刻=[{}] 时区={} 推送落点={} 方向={}",
                config.isEnabled(), config.isScheduleEnabled(), config.getScheduleTimes(),
                config.getTimezone(), MpConfigHelper.deliveryModeLabel(config.getDeliveryMode()),
                MpConfigHelper.publishingDirections(config).stream()
                    .map(item -> item.label()).toList()))
            .flatMap(config -> nextRunInstant()
                .map(next -> "[UniHalo][Mp] 下次计划生成时间："
                    + next.atZone(MpConfigHelper.zoneOf(config.getTimezone()))
                        .format(DATE_TIME_FORMAT)
                    + "（" + config.getTimezone() + "）")
                .defaultIfEmpty("[UniHalo][Mp] 定时生成未启用，不会自动生成公众号文章"))
            .subscribe(message -> log.info(message),
                ex -> log.warn("[UniHalo][Mp] 启动时读取公众号配置失败：{}", ex.getMessage()));
    }

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void checkAndRun() {
        checkAndRunOnce()
            .subscribe(message -> {
                if (StringUtils.hasText(message)) {
                    log.info("[UniHalo][Mp] {}", message);
                }
            }, ex -> log.warn("[UniHalo][Mp] 定时检查异常：{}", ex.getMessage()));
    }

    /** 单次检查（独立出来便于测试）：返回非空字符串表示这次真的跑了一轮 */
    Mono<String> checkAndRunOnce() {
        return loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                logSkipOnce("微信公众号功能未开启（插件设置 - 微信公众号 - 开启微信公众号），跳过本次检查");
                return Mono.empty();
            }
            if (!config.isScheduleEnabled()) {
                logSkipOnce("公众号文章定时生成已关闭（只有点「立即生成」才会生成），跳过本次检查");
                return Mono.empty();
            }
            if (issueService.isRunning()) {
                logSkipOnce("已有一次公众号文章生成任务在执行中，本次跳过");
                return Mono.empty();
            }
            var zone = MpConfigHelper.zoneOf(config.getTimezone());
            var now = now(zone);
            var times = MpConfigHelper.parseScheduleTimes(config.getScheduleTimes());
            var dueSlot = latestDueSlot(times, now.toLocalTime());
            if (dueSlot == null) {
                logSkipOnce("当前 " + now.format(SLOT_FORMAT) + " 未到执行时刻（计划 "
                    + config.getScheduleTimes() + "，时区 " + config.getTimezone() + "），跳过本次检查");
                return Mono.empty();
            }
            var date = now.format(DATE_FORMAT);
            var dueAt = now.toLocalDate().atTime(dueSlot).atZone(zone);
            if (now.isAfter(dueAt.plus(GRACE_WINDOW))) {
                logSkipOnce("已错过 " + dueSlot.format(SLOT_FORMAT) + " 的执行窗口（超过 "
                    + GRACE_WINDOW.toMinutes() + " 分钟，现在 " + now.format(SLOT_FORMAT)
                    + "）：本次不补跑；要生成请点管理页的「立即生成」");
                return Mono.empty();
            }
            var directions = MpConfigHelper.publishingDirections(config);
            return store.listByDate(date).flatMap(existing -> {
                var byDirection = new LinkedHashMap<String, MpIssue>();
                for (var issue : existing) {
                    if (MpIssueStore.KIND_ISSUE.equals(issue.getSourceKind())
                        && StringUtils.hasText(issue.getDirection())) {
                        byDirection.put(issue.getDirection(), issue);
                    }
                }
                // 只补「今天完全没有记录」的方向；失败/空稿不自动重试——
                // 自动重试会在站长没点击的情况下再烧一轮 AI，也是「一打开页面就在生成」的来源
                var pending = directions.stream()
                    .filter(direction -> !byDirection.containsKey(direction.key()))
                    .toList();
                if (pending.isEmpty()) {
                    logSkipOnce("今日 " + date + " 的各方向都已生成过（" + describe(byDirection)
                        + "），跳过本次检查");
                    return Mono.empty();
                }
                resetSkipReason();
                log.info("[UniHalo][Mp] 定时任务触发：时刻={} 当前={} 待生成方向={}",
                    dueSlot.format(SLOT_FORMAT), now.format(DATE_TIME_FORMAT),
                    pending.stream().map(item -> item.label()).toList());
                return Flux.fromIterable(pending)
                    .concatMap(direction -> issueService.generate(direction.key(), false, "scheduled"))
                    .then(Mono.just("定时任务结束 date=" + date + " 方向="
                        + pending.stream().map(item -> item.label()).toList()));
            });
        });
    }

    /** 下一次计划执行时间（状态接口展示用）；未开启时为空 */
    public Mono<Instant> nextRunInstant() {
        return loadConfig().flatMap(config -> {
            if (!config.isEnabled() || !config.isScheduleEnabled()) {
                return Mono.empty();
            }
            var zone = MpConfigHelper.zoneOf(config.getTimezone());
            var now = now(zone);
            var times = MpConfigHelper.parseScheduleTimes(config.getScheduleTimes());
            for (var time : times) {
                if (time.isAfter(now.toLocalTime())) {
                    return Mono.just(now.toLocalDate().atTime(time).atZone(zone).toInstant());
                }
            }
            return Mono.just(now.toLocalDate().plusDays(1).atTime(times.get(0))
                .atZone(zone).toInstant());
        });
    }

    private void logSkipOnce(String reason) {
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            log.info("[UniHalo][Mp] {}", reason);
        }
    }

    private void resetSkipReason() {
        lastSkipReason = "";
    }

    /** 今天已经到点、且最靠后的那个执行时刻；还没到任何时刻时返回 null */
    private static LocalTime latestDueSlot(List<LocalTime> times, LocalTime current) {
        LocalTime due = null;
        for (var time : times) {
            if (!time.isAfter(current) && (due == null || time.isAfter(due))) {
                due = time;
            }
        }
        return due;
    }

    private static String describe(LinkedHashMap<String, MpIssue> byDirection) {
        var states = byDirection.values().stream()
            .map(issue -> issue.getDirectionLabel() + "=" + issue.getState()
                + (StringUtils.hasText(issue.getError()) ? "（" + issue.getError() + "）" : ""))
            .collect(Collectors.joining("；"));
        return StringUtils.hasText(states) ? states : "无记录";
    }

    /** 当前时间；独立成方法便于测试注入固定时钟 */
    ZonedDateTime now(ZoneId zone) {
        return ZonedDateTime.now(zone);
    }

    private Mono<MpConfigDTO> loadConfig() {
        return settingFetcher.get(MpConfigHelper.groupName())
            .map(MpConfigHelper::parse)
            .defaultIfEmpty(MpConfigHelper.defaults());
    }
}
