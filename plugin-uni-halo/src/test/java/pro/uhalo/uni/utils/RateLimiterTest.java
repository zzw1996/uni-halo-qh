package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void shouldAllowUpToLimitThenReject() {
        var limiter = new RateLimiter();
        var window = Duration.ofMinutes(1);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("key", 5, window)).isTrue();
        }
        assertThat(limiter.tryAcquire("key", 5, window)).isFalse();
        // 其他 key 不受影响
        assertThat(limiter.tryAcquire("other", 5, window)).isTrue();
    }

    @Test
    void shouldResetAfterWindowElapsed() throws InterruptedException {
        var limiter = new RateLimiter();
        var window = Duration.ofMillis(80);
        assertThat(limiter.tryAcquire("key", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("key", 1, window)).isFalse();
        Thread.sleep(120);
        assertThat(limiter.tryAcquire("key", 1, window)).isTrue();
    }

    @Test
    void zeroLimitShouldAllow() {
        var limiter = new RateLimiter();
        assertThat(limiter.tryAcquire("key", 0, Duration.ofMinutes(1))).isTrue();
    }
}
