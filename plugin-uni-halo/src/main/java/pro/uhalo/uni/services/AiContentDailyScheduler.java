package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.impl.AiContentFeedService;
import pro.uhalo.uni.services.impl.AiContentPushService;
import pro.uhalo.uni.services.impl.PushDispatchService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * AI 定制内容的「每日定时生成 + 推送」。
 *
 * <p>与读时生成的关系：<b>只给要推送的人预生成</b>。开了每日推送、选了整点、权益有效、
 * 还有推送额度——只有这类用户会在到点时被提前生成一份；其余用户仍然是「打开页面才生成」。
 * 既有「收到推送点开就是成品」的体验，又不会给不活跃用户白烧 AI。</p>
 *
 * <p>按整点分桶：每轮只处理「pushHour == 当前小时」的用户，而不是每天一次扫全量——
 * 用户分散在不同整点，服务端峰值被摊平，也让「什么时候推」由用户自己决定。</p>
 *
 * <p>幂等有两层：① 今天已生成的用户直接跳过（用 {@code AiContentIssue} 的 date 索引
 * 一次拉全，避免逐个回查）；② 推送本身靠 {@code PushRecord} 的「每人每天一条」资源名兜底。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentDailyScheduler implements InitializingBean {

    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 120_000L;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReactiveExtensionClient client;
    private final AiContentFeedService feedService;
    private final AiContentPushService pushService;
    private final PushDispatchService pushDispatchService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上次跑过的「日期#整点」：调度每分钟醒一次，同一小时只跑一轮 */
    private volatile String lastRunSlot = "";

    @Override
    public void afterPropertiesSet() {
        log.info("[UniHalo][AiContent] 每日简报调度器已就绪（按用户选的整点分桶生成并推送）");
        // 顺手预热实时素材池：简报的素材来自现抓（上百个源），
        // 冷启动后第一位用户不该为这次抓取干等（小程序请求超时只有 30 秒）
        feedService.warmLivePool();
    }

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void checkAndRun() {
        checkAndRunOnce().subscribe(
            message -> {
                if (StringUtils.hasText(message)) {
                    log.info("[UniHalo][AiContent] {}", message);
                }
            },
            error -> log.warn("[UniHalo][AiContent] 每日简报调度异常：{}", error.getMessage()));
    }

    Mono<String> checkAndRunOnce() {
        if (!running.compareAndSet(false, true)) {
            return Mono.just("");
        }
        var now = ZonedDateTime.now(ZONE);
        var slot = now.toLocalDate() + "#" + now.getHour();
        if (slot.equals(lastRunSlot)) {
            running.set(false);
            return Mono.just("");
        }
        var hour = now.getHour();
        return pushDispatchService.loadPushConfigSafely()
            .flatMap(config -> scheduleUsable(config)
                ? runForHour(config, hour).map(count -> {
                    lastRunSlot = slot;
                    return count > 0
                        ? "已为 " + count + " 位用户生成并推送 " + hour + ":00 的简报" : "";
                })
                : Mono.just(""))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 每日简报调度失败（下一轮重试）：{}", ex.getMessage());
                return Mono.just("");
            })
            .doFinally(signal -> running.set(false));
    }

    /** 定时生成是否可用：总开关 + 简报开关 + 定时开关 + 模板与字段映射齐备 */
    static boolean scheduleUsable(PushConfigDTO config) {
        return config != null && config.isEnabled() && config.isAiDigestEnabled()
            && config.isAiDigestScheduleEnabled() && config.hasAiDigestTemplate()
            && config.aiDigestFieldMap().usable();
    }

    /**
     * 跑当前整点那一桶：找出「要在这个点收推送」的用户 → 批量生成 → 逐条推送。
     *
     * @return 成功推送的用户数
     */
    Mono<Integer> runForHour(PushConfigDTO config, int hour) {
        var today = LocalDate.now(ZONE).toString();
        return targetsOf(hour)
            .flatMap(targets -> targets.isEmpty() ? Mono.just(0)
                : generatedToday(today).flatMap(done -> {
                    var pending = targets.stream()
                        .filter(item -> !done.contains(item.openid()))
                        .limit(Math.max(1, config.getAiDigestMaxUsersPerRun()))
                        .toList();
                    if (pending.isEmpty()) {
                        return Mono.just(0);
                    }
                    log.info("[UniHalo][AiContent] {}:00 定时生成：目标 {} 位（今日已生成 {} 位）",
                        hour, pending.size(), done.size());
                    return pushTo(pending, config);
                }));
    }

    /** 批量生成完再逐条推送：单个用户失败不影响其他人 */
    private Mono<Integer> pushTo(List<Target> targets, PushConfigDTO config) {
        return Flux.fromIterable(targets)
            .concatMap(target -> pushOne(target, config))
            .reduce(0, Integer::sum);
    }

    private Mono<Integer> pushOne(Target target, PushConfigDTO config) {
        return feedService.ensureToday(target.openid())
            .flatMap(issue -> pushService.push(target.openid(), config, issue, target.preference()))
            .map(result -> result.isPushed() ? 1 : 0)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 定时推送失败（跳过该用户）：{}", ex.getMessage());
                return Mono.just(0);
            });
    }

    /**
     * 该整点要收到推送的用户。
     *
     * <p>偏好记录是「一个开通过的用户一条」，量级很小（不像文章/积分那样会持续增长），
     * 所以直接全量读回来在内存里按整点过滤，不给 {@code pushHour} 另建索引——
     * 少一个索引就少一处「建索引期间写不进去」的坑。</p>
     */
    private Mono<List<Target>> targetsOf(int hour) {
        return client.listAll(AiContentPreference.class, new ListOptions(), null)
            .filter(preference -> Boolean.TRUE.equals(preference.getPushEnabled()))
            .filter(preference -> preference.getPushHour() != null
                && preference.getPushHour() == hour)
            .filter(preference -> StringUtils.hasText(preference.getWxOpenid()))
            .map(preference -> new Target(preference.getWxOpenid().trim(), toDto(preference)))
            .collectList()
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取推送目标失败：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 今天已经生成过简报的用户（一次索引查询，避免逐个用户回查） */
    private Mono<HashSet<String>> generatedToday(String today) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("date", today)));
        return client.listAll(AiContentIssue.class, listOptions, null)
            .map(AiContentIssue::getWxOpenid)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collectList()
            .map(HashSet::new)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取今日已生成清单失败，本轮按空处理：{}",
                    ex.getMessage());
                return Mono.just(new HashSet<>());
            });
    }

    private static AiContentPreferenceDTO toDto(AiContentPreference preference) {
        return AiContentPreferenceDTO.builder()
            .enabled(Boolean.TRUE.equals(preference.getEnabled()))
            .categories(orEmpty(preference.getCategories()))
            .sources(orEmpty(preference.getSources()))
            .keywords(orEmpty(preference.getKeywords()))
            .customTopics(orEmpty(preference.getCustomTopics()))
            .pushEnabled(Boolean.TRUE.equals(preference.getPushEnabled()))
            .pushHour(preference.getPushHour())
            .build();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** 一个目标用户：openid + 他的偏好（推送文案要用主题名） */
    record Target(String openid, AiContentPreferenceDTO preference) {
    }
}
