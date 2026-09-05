package pro.uhalo.uni.utils;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量内存滑动窗口限流器（单实例 Halo 插件内使用，重启清零）。
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 在 window 内允许最多 limit 次请求并标记本次消耗；超限返回 false。
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            return true;
        }
        var now = System.currentTimeMillis();
        var windowMillis = window.toMillis();
        while (true) {
            var existing = windows.get(key);
            if (existing == null || now - existing.startAt >= windowMillis) {
                var fresh = new Window(now, 1);
                if (existing == null) {
                    if (windows.putIfAbsent(key, fresh) == null) {
                        return true;
                    }
                } else if (windows.replace(key, existing, fresh)) {
                    return true;
                }
                continue;
            }
            if (existing.count < limit) {
                if (windows.replace(key, existing, new Window(existing.startAt, existing.count + 1))) {
                    return true;
                }
                continue;
            }
            return false;
        }
    }

    private record Window(long startAt, int count) {
    }
}
