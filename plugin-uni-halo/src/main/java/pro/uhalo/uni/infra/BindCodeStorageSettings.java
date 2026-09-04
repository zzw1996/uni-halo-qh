package pro.uhalo.uni.infra;

import org.springframework.util.StringUtils;

public record BindCodeStorageSettings(
    String storageMode,
    boolean pluginRedisEnabled,
    String pluginRedisHost,
    int pluginRedisPort,
    int pluginRedisDatabase,
    String pluginRedisPassword,
    String pluginRedisUsername
) {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_HALO = "halo";
    public static final String MODE_PLUGIN_REDIS = "plugin-redis";
    public static final String MODE_MEMORY = "memory";

    public static BindCodeStorageSettings defaults() {
        return new BindCodeStorageSettings(MODE_AUTO, false, "", 6379, 0, "", "");
    }

    public boolean pluginRedisConfigured() {
        return pluginRedisEnabled && StringUtils.hasText(pluginRedisHost);
    }

    public String normalizedMode() {
        return StringUtils.hasText(storageMode) ? storageMode.trim() : MODE_AUTO;
    }
}
