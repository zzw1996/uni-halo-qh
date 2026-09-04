package pro.uhalo.uni.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InMemoryBindCodeBackend implements BindCodeStorageBackend {

    private final ConcurrentHashMap<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public String backendName() {
        return "memory";
    }

    @Override
    public String describe() {
        return "memory(in-process, restart clears codes)";
    }

    @Override
    public void assertRateLimitAllowed(String openid, String account) {
        cleanupExpiredEntries();
        var rateKey = HaloBindCodeKeys.buildRateKey(openid, account);
        var existing = store.get(rateKey);
        if (existing != null && !existing.isExpired()) {
            throw new WxAuthException("验证码发送过于频繁，请 60 秒后再试");
        }
    }

    @Override
    public void recordRateLimit(String openid, String account) {
        cleanupExpiredEntries();
        store.put(HaloBindCodeKeys.buildRateKey(openid, account),
            MemoryEntry.of("1", Constants.HALO_BIND_CODE_RATE_LIMIT_SECONDS));
    }

    @Override
    public void saveCode(String openid, String account, String code, String payloadJson) {
        cleanupExpiredEntries();
        store.put(HaloBindCodeKeys.buildCodeKey(openid, account),
            MemoryEntry.of(code + "|" + payloadJson, Constants.HALO_BIND_CODE_EXPIRE_SECONDS));
        log.warn("Bind code saved in memory backend, key={}, ttl={}s (not shared across nodes/restarts)",
            HaloBindCodeKeys.buildCodeKey(openid, account), Constants.HALO_BIND_CODE_EXPIRE_SECONDS);
    }

    @Override
    public String getCode(String openid, String account) {
        cleanupExpiredEntries();
        var codeKey = HaloBindCodeKeys.buildCodeKey(openid, account);
        var entry = store.get(codeKey);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            store.remove(codeKey);
            throw new WxAuthException("验证码已过期");
        }
        return entry.value();
    }

    @Override
    public void deleteCode(String openid, String account) {
        store.remove(HaloBindCodeKeys.buildCodeKey(openid, account));
    }

    private void cleanupExpiredEntries() {
        store.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private record MemoryEntry(String value, long expireAtMs) {
        static MemoryEntry of(String value, int ttlSeconds) {
            return new MemoryEntry(value, System.currentTimeMillis() + ttlSeconds * 1000L);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAtMs;
        }
    }
}
