package pro.uhalo.uni.enums;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.exception.WxAuthException;

import java.util.Set;

public enum UserActivityType {

    COMMENT("comment"),
    BROWSE("browse"),
    UPVOTE("upvote"),
    VOTE("vote"),
    REACTION("reaction");

    private static final Set<String> VALUES = Set.of("comment", "browse", "upvote", "vote", "reaction");

    private final String value;

    UserActivityType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static UserActivityType parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new WxAuthException("缺少 type 参数");
        }
        var type = raw.trim().toLowerCase();
        if (!VALUES.contains(type)) {
            throw new WxAuthException("type 须为 comment、browse、upvote、vote 或 reaction");
        }
        return UserActivityType.valueOf(type.toUpperCase());
    }
}
