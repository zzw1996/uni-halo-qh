package pro.uhalo.uni.infra;

import pro.uhalo.uni.constants.Constants;

public final class HaloBindCodeKeys {

    private HaloBindCodeKeys() {
    }

    public static String buildCodeKey(String openid, String account) {
        return Constants.HALO_BIND_CODE_REDIS_PREFIX + normalize(openid) + ":" + normalize(account);
    }

    public static String buildRateKey(String openid, String account) {
        return Constants.HALO_BIND_CODE_RATE_PREFIX + normalize(openid) + ":" + normalize(account);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
