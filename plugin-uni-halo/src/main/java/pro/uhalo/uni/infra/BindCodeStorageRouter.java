package pro.uhalo.uni.infra;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.exception.WxAuthException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.function.Function;

@Component
@Slf4j
public class BindCodeStorageRouter {

    private static final String REDIS_CONFIG_GROUP = "redisConfig";

    private final Environment environment;
    private final ReactiveSettingFetcher settingFetcher;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider;
    private final ObjectProvider<ReactiveStringRedisTemplate> reactiveStringRedisTemplateProvider;
    private final InMemoryBindCodeBackend inMemoryBackend = new InMemoryBindCodeBackend();
    private volatile StringRedisTemplate haloFallbackTemplate;

    public BindCodeStorageRouter(Environment environment,
                                 ReactiveSettingFetcher settingFetcher,
                                 ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                                 ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider,
                                 ObjectProvider<ReactiveStringRedisTemplate> reactiveStringRedisTemplateProvider) {
        this.environment = environment;
        this.settingFetcher = settingFetcher;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.redisConnectionFactoryProvider = redisConnectionFactoryProvider;
        this.reactiveStringRedisTemplateProvider = reactiveStringRedisTemplateProvider;
    }

    public void logDiagnostics(BindCodeStorageSettings settings) {
        log.info(buildDiagnosticsMessage(settings));
    }

    public <T> Mono<T> withBackend(Function<BindCodeStorageBackend, T> action) {
        return loadSettings()
            .flatMap(settings -> Mono.fromCallable(() -> {
                logDiagnostics(settings);
                var backend = resolveBackend(settings);
                log.info("Bind code storage active backend: {}", backend.describe());
                return action.apply(backend);
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    BindCodeStorageBackend resolveBackend(BindCodeStorageSettings settings) {
        var mode = settings.normalizedMode();
        if (BindCodeStorageSettings.MODE_MEMORY.equals(mode)) {
            log.warn("Bind code storage forced to memory backend by plugin config");
            return inMemoryBackend;
        }
        if (BindCodeStorageSettings.MODE_PLUGIN_REDIS.equals(mode)) {
            return requirePluginRedis(settings);
        }
        if (BindCodeStorageSettings.MODE_HALO.equals(mode)) {
            return requireHaloRedis();
        }
        return resolveAutoBackend(settings);
    }

    private BindCodeStorageBackend resolveAutoBackend(BindCodeStorageSettings settings) {
        var haloError = tryHaloRedis();
        if (haloError.backend() != null) {
            return haloError.backend();
        }
        if (settings.pluginRedisConfigured()) {
            try {
                return PluginJedisBindCodeBackend.create(settings);
            } catch (WxAuthException ex) {
                log.warn("Plugin Redis unavailable in auto mode: {}", ex.getReason());
            }
        } else if (Boolean.TRUE.equals(readHaloRedisEnabledProperty())
            || isTrue(System.getenv("HALO_REDIS_ENABLED"))) {
            log.warn(
                "Halo Redis auto-config unavailable in plugin context ({}). "
                    + "Community edition or isolated plugin context cannot inject RedisConnectionFactory. "
                    + "Configure plugin Redis in「验证码 Redis 配置」.",
                haloError.reason());
        }
        log.warn(
            "All Redis backends unavailable, falling back to in-memory bind code storage. "
                + "Codes will not survive restart and are not shared across nodes. Diagnostics: {}",
            buildDiagnosticsMessage(settings));
        return inMemoryBackend;
    }

    private BindCodeStorageBackend requireHaloRedis() {
        var haloError = tryHaloRedis();
        if (haloError.backend() != null) {
            return haloError.backend();
        }
        throw new WxAuthException(
            "Redis 连接失败: Halo 内置 Redis 不可用（" + haloError.reason() + "）。"
                + "诊断：" + buildDiagnosticsMessage(BindCodeStorageSettings.defaults()));
    }

    private BindCodeStorageBackend requirePluginRedis(BindCodeStorageSettings settings) {
        if (!settings.pluginRedisConfigured()) {
            throw new WxAuthException(
                "Redis 未配置：请在插件「验证码 Redis 配置」中启用并填写 Redis 地址");
        }
        return PluginJedisBindCodeBackend.create(settings);
    }

    private HaloRedisAttempt tryHaloRedis() {
        var template = stringRedisTemplateProvider.getIfAvailable();
        if (template != null) {
            try {
                return new HaloRedisAttempt(SpringRedisBindCodeBackend.create("StringRedisTemplate", template), null);
            } catch (WxAuthException ex) {
                return new HaloRedisAttempt(null, ex.getReason());
            }
        }
        var factory = redisConnectionFactoryProvider.getIfAvailable();
        if (factory != null) {
            try {
                if (haloFallbackTemplate == null) {
                    synchronized (this) {
                        if (haloFallbackTemplate == null) {
                            var created = new StringRedisTemplate(factory);
                            created.afterPropertiesSet();
                            haloFallbackTemplate = created;
                        }
                    }
                }
                return new HaloRedisAttempt(
                    SpringRedisBindCodeBackend.create("RedisConnectionFactory", haloFallbackTemplate), null);
            } catch (WxAuthException ex) {
                return new HaloRedisAttempt(null, ex.getReason());
            }
        }
        return new HaloRedisAttempt(null, "RedisConnectionFactory=absent, StringRedisTemplate=absent");
    }

    public Mono<BindCodeStorageSettings> loadSettings() {
        return settingFetcher.get(REDIS_CONFIG_GROUP)
            .map(this::parseSettings)
            .defaultIfEmpty(BindCodeStorageSettings.defaults());
    }

    BindCodeStorageSettings parseSettings(JsonNode config) {
        if (config == null || config.isNull()) {
            return BindCodeStorageSettings.defaults();
        }
        var pluginRedis = config.path("pluginRedis");
        return new BindCodeStorageSettings(
            textValue(config, "storageMode", BindCodeStorageSettings.MODE_AUTO),
            boolValue(pluginRedis, "enabled", false),
            textValue(pluginRedis, "host", ""),
            intValue(pluginRedis, "port", 6379),
            intValue(pluginRedis, "database", 0),
            textValue(pluginRedis, "password", ""),
            textValue(pluginRedis, "username", "")
        );
    }

    String buildDiagnosticsMessage(BindCodeStorageSettings settings) {
        return "storageMode=" + settings.normalizedMode()
            + ", pluginRedis.enabled=" + settings.pluginRedisEnabled()
            + ", pluginRedis.host=" + (StringUtils.hasText(settings.pluginRedisHost())
            ? settings.pluginRedisHost() : "<unset>")
            + ", pluginRedis.port=" + settings.pluginRedisPort()
            + ", halo.redis.enabled=" + readHaloRedisEnabledProperty()
            + ", HALO_REDIS_ENABLED=" + System.getenv("HALO_REDIS_ENABLED")
            + ", spring.data.redis.host=" + propertyOrUnset("spring.data.redis.host")
            + ", spring.data.redis.port=" + propertyOrUnset("spring.data.redis.port")
            + ", spring.data.redis.database=" + propertyOrUnset("spring.data.redis.database")
            + ", haloProRedisBeans(RedisConnectionFactory=" + beanStatus(redisConnectionFactoryProvider)
            + ", StringRedisTemplate=" + beanStatus(stringRedisTemplateProvider)
            + ", ReactiveStringRedisTemplate=" + beanStatus(reactiveStringRedisTemplateProvider)
            + ", note=Halo Pro ≥2.16 injects Redis beans into main app; plugin isolated context often sees absent even when Redis container is healthy)";
    }

    private Boolean readHaloRedisEnabledProperty() {
        return environment.getProperty("halo.redis.enabled", Boolean.class);
    }

    private String propertyOrUnset(String key) {
        var value = environment.getProperty(key);
        return StringUtils.hasText(value) ? value : "<unset>";
    }

    private static <T> String beanStatus(ObjectProvider<T> provider) {
        return provider.getIfAvailable() != null ? "present" : "absent";
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String textValue(JsonNode node, String field, String defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private static boolean boolValue(JsonNode node, String field, boolean defaultValue) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        return node.path(field).asBoolean(defaultValue);
    }

    private static int intValue(JsonNode node, String field, int defaultValue) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        return node.path(field).asInt(defaultValue);
    }

    private record HaloRedisAttempt(BindCodeStorageBackend backend, String reason) {
    }
}
