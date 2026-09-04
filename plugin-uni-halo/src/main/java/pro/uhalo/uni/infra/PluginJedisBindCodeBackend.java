package pro.uhalo.uni.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Slf4j
public class PluginJedisBindCodeBackend implements BindCodeStorageBackend {

    private final BindCodeStorageSettings settings;
    private final JedisPool jedisPool;

    private PluginJedisBindCodeBackend(BindCodeStorageSettings settings, JedisPool jedisPool) {
        this.settings = settings;
        this.jedisPool = jedisPool;
    }

    static PluginJedisBindCodeBackend create(BindCodeStorageSettings settings) {
        var pool = createPool(settings);
        try (var jedis = pool.getResource()) {
            var pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new WxAuthException("Redis 连接失败: PING 响应异常 " + pong);
            }
        } catch (WxAuthException ex) {
            pool.close();
            throw ex;
        } catch (Exception ex) {
            pool.close();
            throw new WxAuthException("Redis 连接失败: " + ex.getMessage());
        }
        log.info("Plugin Redis connected, host={}, port={}, database={}",
            settings.pluginRedisHost(), settings.pluginRedisPort(), settings.pluginRedisDatabase());
        return new PluginJedisBindCodeBackend(settings, pool);
    }

    @Override
    public String backendName() {
        return "plugin-jedis";
    }

    @Override
    public String describe() {
        return backendName() + "(" + settings.pluginRedisHost() + ":" + settings.pluginRedisPort()
            + "/" + settings.pluginRedisDatabase() + ")";
    }

    @Override
    public void assertRateLimitAllowed(String openid, String account) {
        execute(jedis -> {
            if (Boolean.TRUE.equals(jedis.exists(HaloBindCodeKeys.buildRateKey(openid, account)))) {
                throw new WxAuthException("验证码发送过于频繁，请 60 秒后再试");
            }
            return null;
        });
    }

    @Override
    public void recordRateLimit(String openid, String account) {
        executeVoid(jedis -> jedis.setex(
            HaloBindCodeKeys.buildRateKey(openid, account),
            Constants.HALO_BIND_CODE_RATE_LIMIT_SECONDS,
            "1"));
    }

    @Override
    public void saveCode(String openid, String account, String code, String payloadJson) {
        var codeKey = HaloBindCodeKeys.buildCodeKey(openid, account);
        executeVoid(jedis -> jedis.setex(
            codeKey,
            Constants.HALO_BIND_CODE_EXPIRE_SECONDS,
            code + "|" + payloadJson));
        log.info("Bind code saved in plugin Redis, key={}, ttl={}s", codeKey,
            Constants.HALO_BIND_CODE_EXPIRE_SECONDS);
    }

    @Override
    public String getCode(String openid, String account) {
        return execute(jedis -> jedis.get(HaloBindCodeKeys.buildCodeKey(openid, account)));
    }

    @Override
    public void deleteCode(String openid, String account) {
        executeVoid(jedis -> jedis.del(HaloBindCodeKeys.buildCodeKey(openid, account)));
    }

    private <T> T execute(java.util.function.Function<Jedis, T> action) {
        try (var jedis = jedisPool.getResource()) {
            return action.apply(jedis);
        } catch (WxAuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WxAuthException("Redis 连接失败: " + ex.getMessage());
        }
    }

    private void executeVoid(java.util.function.Consumer<Jedis> action) {
        execute(jedis -> {
            action.accept(jedis);
            return null;
        });
    }

    private static JedisPool createPool(BindCodeStorageSettings settings) {
        var poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(1);
        var host = settings.pluginRedisHost().trim();
        var port = settings.pluginRedisPort();
        var database = settings.pluginRedisDatabase();
        var password = settings.pluginRedisPassword();
        var username = settings.pluginRedisUsername();
        if (StringUtils.hasText(username)) {
            return new JedisPool(poolConfig, host, port, 2000, username,
                StringUtils.hasText(password) ? password : null, database);
        }
        if (StringUtils.hasText(password)) {
            return new JedisPool(poolConfig, host, port, 2000, password, database);
        }
        return new JedisPool(poolConfig, host, port, 2000, null, database);
    }
}
