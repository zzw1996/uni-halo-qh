package pro.uhalo.uni.enums;

import lombok.Getter;
import pro.uhalo.uni.exception.WxAuthException;

import java.util.Set;

@Getter
public enum UserContentType {

    POST("post", "content.halo.run", "posts", "Post"),
    MOMENT("moment", "moment.halo.run", "moments", "Moment");

    private static final Set<String> VALUES = Set.of("post", "moment");

    private final String value;
    private final String group;
    private final String plural;
    private final String kind;

    UserContentType(String value, String group, String plural, String kind) {
        this.value = value;
        this.group = group;
        this.plural = plural;
        this.kind = kind;
    }

    public static UserContentType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return POST;
        }
        var normalized = raw.trim().toLowerCase();
        if (!VALUES.contains(normalized)) {
            throw new WxAuthException("contentType 须为 post 或 moment");
        }
        return "moment".equals(normalized) ? MOMENT : POST;
    }

    public String counterMeterName(String resourceName) {
        return plural + "." + group + "/" + resourceName;
    }
}
