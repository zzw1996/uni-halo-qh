package pro.uhalo.uni.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;

import java.time.Duration;

@Slf4j
public class SpringRedisBindCodeBackend implements BindCodeStorageBackend {

    private final String source;
    private final StringRedisTemplate template;

    SpringRedisBindCodeBackend(String source, StringRedisTemplate template) {
        this.source = source;
        this.template = template;
    }

    static SpringRedisBindCodeBackend create(String source, StringRedisTemplate template) {
        ping(template);
        return new SpringRedisBindCodeBackend(source, template);
    }

    @Override
    public String backendName() {
        return "halo-spring-redis";
    }

    @Override
    public String describe() {
        return backendName() + "(" + source + ")";
    }

    @Override
    public void assertRateLimitAllowed(String openid, String account) {
        var rateKey = HaloBindCodeKeys.buildRateKey(openid, account);
        if (Boolean.TRUE.equals(template.hasKey(rateKey))) {
            throw new WxAuthException("验证码发送过于频繁，请 60 秒后再试");
        }
    }

    @Override
    public void recordRateLimit(String openid, String account) {
        template.opsForValue().set(
            HaloBindCodeKeys.buildRateKey(openid, account),
            "1",
            Duration.ofSeconds(Constants.HALO_BIND_CODE_RATE_LIMIT_SECONDS));
    }

    @Override
    public void saveCode(String openid, String account, String code, String payloadJson) {
        var codeKey = HaloBindCodeKeys.buildCodeKey(openid, account);
        template.opsForValue().set(
            codeKey,
            code + "|" + payloadJson,
            Duration.ofSeconds(Constants.HALO_BIND_CODE_EXPIRE_SECONDS));
        log.info("Bind code saved in Halo Spring Redis, key={}, ttl={}s", codeKey,
            Constants.HALO_BIND_CODE_EXPIRE_SECONDS);
    }

    @Override
    public String getCode(String openid, String account) {
        var codeKey = HaloBindCodeKeys.buildCodeKey(openid, account);
        return template.opsForValue().get(codeKey);
    }

    @Override
    public void deleteCode(String openid, String account) {
        template.delete(HaloBindCodeKeys.buildCodeKey(openid, account));
    }

    private static void ping(StringRedisTemplate template) {
        var connectionFactory = template.getConnectionFactory();
        if (connectionFactory == null) {
            throw new WxAuthException("Redis 连接失败: StringRedisTemplate 未关联 ConnectionFactory");
        }
        try (var connection = connectionFactory.getConnection()) {
            var pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new WxAuthException("Redis 连接失败: PING 响应异常 " + pong);
            }
        }
    }
}
