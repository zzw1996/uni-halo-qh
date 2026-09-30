package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.services.impl.PushDispatchService;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推送调度器：每分钟醒一次，按配置的间隔真正跑一轮。
 *
 * <p>骨架照 {@code DigestScheduler}（那个类是被线上问题打磨过的）：</p>
 * <ul>
 *   <li><b>不把间隔写死进 {@code @Scheduled}</b>：改成「每分钟检查一次，到了间隔才跑」，
 *       站长改完配置立刻生效，不必重启；</li>
 *   <li><b>固定延迟 + 启动延迟</b>：{@code fixedDelay} 保证上一轮跑完再计时，
 *       {@code initialDelay} 避开 Halo 启动期（那时读配置、建索引都还不可靠）；</li>
 *   <li><b>防重入</b>：上一轮没跑完就跳过，避免一轮慢查询堆叠出多轮并发；</li>
 *   <li><b>失败不刷屏</b>：异常只告警一次，等下一个间隔再试。</li>
 * </ul>
 *
 * <p>推送与日报生成是两条独立的链路：日报那套是「到点必须出一份」，这里只是
 * 「有内容时提醒一下」，所以失败完全可以等到下一轮。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushScheduler implements InitializingBean {

    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 90_000L;

    private final ReactiveSettingFetcher settingFetcher;
    private final PushDispatchService dispatchService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上次真正跑一轮的时间（毫秒）：用来实现「按配置间隔跑」 */
    private volatile long lastRunAtMillis = 0L;

    /** 日志去重：同一句跳过原因不重复刷 */
    private volatile String lastSkipReason = "";

    @Override
    public void afterPropertiesSet() {
        log.info("[UniHalo][Push] 推送调度器已就绪（每分钟检查一次，按配置间隔执行）");
    }

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void checkAndRun() {
        checkAndRunOnce().subscribe(
            message -> {
                if (StringUtils.hasText(message)) {
                    log.info("[UniHalo][Push] {}", message);
                }
            },
            ex -> log.warn("[UniHalo][Push] 定时检查异常：{}", ex.getMessage()));
    }

    /** 独立成方法便于测试：返回一句可读结论（空串表示这轮什么也没做） */
    Mono<String> checkAndRunOnce() {
        if (!running.compareAndSet(false, true)) {
            return Mono.just("");
        }
        return loadConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    return skipOnce("推送总开关未开启，跳过");
                }
                if (intervalNotElapsed(config)) {
                    return Mono.just("");
                }
                lastRunAtMillis = System.currentTimeMillis();
                logSkipCleared();
                return runOnce(config);
            })
            .doFinally(signal -> running.set(false));
    }

    private Mono<String> runOnce(PushConfigDTO config) {
        // 简报线已移交 AiContentDailyScheduler：它按用户选的整点先「生成简报」再推送
        // （推送的判据是「你自己的简报已生成」，而不是「站长的日报已生成」）。
        // 这里只保留新文章线。
        var newPost = PushConfigHelper.kindEnabled(config, PushConfigHelper.KIND_NEW_POST)
            ? dispatchService.dispatchNewPosts(config).defaultIfEmpty(0)
            : Mono.just(0);
        return newPost.map(newPostSent -> {
            if (newPostSent == 0) {
                return "";
            }
            return "本轮推送完成：新文章 " + newPostSent + " 条";
        });
    }

    private boolean intervalNotElapsed(PushConfigDTO config) {
        var intervalMs = Math.max(1, config.getCheckIntervalMinutes()) * 60_000L;
        return System.currentTimeMillis() - lastRunAtMillis < intervalMs;
    }

    private Mono<String> skipOnce(String reason) {
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            return Mono.just(reason);
        }
        return Mono.just("");
    }

    private void logSkipCleared() {
        lastSkipReason = "";
    }

    private Mono<PushConfigDTO> loadConfig() {
        return settingFetcher.get(PushConfigHelper.groupName())
            .map(PushConfigHelper::parse)
            .defaultIfEmpty(PushConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Push] 读取推送配置失败（按关闭处理）：{}", ex.getMessage());
                return Mono.just(PushConfigHelper.defaults());
            });
    }
}
