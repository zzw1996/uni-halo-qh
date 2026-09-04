package pro.uhalo.uni.infra;

public interface BindCodeStorageBackend {

    String backendName();

    String describe();

    void assertRateLimitAllowed(String openid, String account);

    void recordRateLimit(String openid, String account);

    void saveCode(String openid, String account, String code, String payloadJson);

    /**
     * @return raw stored value {@code code|payloadJson}, or {@code null} if never sent
     */
    String getCode(String openid, String account);

    void deleteCode(String openid, String account);
}
