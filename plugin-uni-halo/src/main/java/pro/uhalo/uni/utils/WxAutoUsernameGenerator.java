package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class WxAutoUsernameGenerator {

    public static final String PREFIX = "wx";
    public static final int DIGIT_LENGTH = 6;
    public static final int MAX_ATTEMPTS = 10;
    private static final int MODULUS = 1_000_000;
    private static final Pattern AUTO_CREATED_PATTERN = Pattern.compile("^wx[0-9]{6}$");
    private static final String LEGACY_PREFIX = "wx_";

    private WxAutoUsernameGenerator() {
    }

    public static int deriveSeed(String openid) {
        var crc = new CRC32();
        crc.update(openid.trim().getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % MODULUS);
    }

    public static String format(int numericSuffix) {
        return PREFIX + String.format("%0" + DIGIT_LENGTH + "d", Math.floorMod(numericSuffix, MODULUS));
    }

    public static String candidate(String openid, int attempt) {
        var seed = deriveSeed(openid);
        if (attempt <= 0) {
            return format(seed);
        }
        if (attempt < 6) {
            return format(seed + attempt);
        }
        return format(ThreadLocalRandom.current().nextInt(MODULUS));
    }

    public static boolean isAutoCreatedFormat(String haloUserName) {
        if (!StringUtils.hasText(haloUserName)) {
            return false;
        }
        var name = haloUserName.trim();
        return AUTO_CREATED_PATTERN.matcher(name).matches()
            || name.startsWith(LEGACY_PREFIX);
    }
}
