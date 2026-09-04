package pro.uhalo.uni.infra;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BindCodeStorageRouterTest {

    @Test
    void shouldFallbackToMemoryInAutoModeWhenNoRedisAvailable() {
        var router = new BindCodeStorageRouter(
            mockEnvironment(null),
            mockSettingFetcher(),
            emptyProvider(StringRedisTemplate.class),
            emptyProvider(RedisConnectionFactory.class),
            emptyProvider(ReactiveStringRedisTemplate.class));

        var backend = router.resolveBackend(BindCodeStorageSettings.defaults());
        assertThat(backend.backendName()).isEqualTo("memory");
    }

    @Test
    void shouldBuildDiagnosticsWithUnsetHaloProperties() {
        var fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.get("redisConfig")).thenReturn(Mono.empty());
        var router = new BindCodeStorageRouter(
            mockEnvironment(null),
            fetcher,
            emptyProvider(StringRedisTemplate.class),
            emptyProvider(RedisConnectionFactory.class),
            emptyProvider(ReactiveStringRedisTemplate.class));

        var message = router.buildDiagnosticsMessage(BindCodeStorageSettings.defaults());
        assertThat(message)
            .contains("halo.redis.enabled=null")
            .contains("RedisConnectionFactory=absent")
            .contains("plugin isolated context");
    }

    private static Environment mockEnvironment(Boolean haloRedisEnabled) {
        var environment = mock(Environment.class);
        when(environment.getProperty("halo.redis.enabled", Boolean.class)).thenReturn(haloRedisEnabled);
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.port")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.database")).thenReturn(null);
        return environment;
    }

    private static ReactiveSettingFetcher mockSettingFetcher() {
        var fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.get("redisConfig")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()));
        return fetcher;
    }

    private static <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
