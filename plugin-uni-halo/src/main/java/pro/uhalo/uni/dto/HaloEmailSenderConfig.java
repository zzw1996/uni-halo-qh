package pro.uhalo.uni.dto;

import org.springframework.util.StringUtils;

public record HaloEmailSenderConfig(
    boolean enable,
    String displayName,
    String username,
    String sender,
    String password,
    String host,
    Integer port,
    String encryption
) {
    public boolean isConfigured() {
        return enable
            && StringUtils.hasText(host)
            && port != null
            && port > 0
            && StringUtils.hasText(username)
            && StringUtils.hasText(password);
    }

    public String resolveDisplayName() {
        return StringUtils.hasText(displayName) ? displayName.trim() : username.trim();
    }

    public String resolveSender() {
        return StringUtils.hasText(sender) ? sender.trim() : username.trim();
    }
}
