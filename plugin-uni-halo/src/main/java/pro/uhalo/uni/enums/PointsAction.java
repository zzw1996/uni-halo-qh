package pro.uhalo.uni.enums;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.exception.WxAuthException;

import java.util.Locale;
import java.util.Set;

public enum PointsAction {
    BROWSE("browse"),
    COMMENT("comment"),
    UPVOTE("upvote"),
    SIGN_IN("sign_in");

    private static final Set<String> EARN_ACTIONS = Set.of(BROWSE.value, COMMENT.value, UPVOTE.value);

    private final String value;

    PointsAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PointsAction parseEarnAction(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new WxAuthException("action 不能为空");
        }
        var normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (var action : values()) {
            if (action.value.equals(normalized) && EARN_ACTIONS.contains(normalized)) {
                return action;
            }
        }
        throw new WxAuthException("不支持的 action：" + raw);
    }

    public static String labelOf(String action) {
        if (!StringUtils.hasText(action)) {
            return "积分变动";
        }
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "browse" -> "浏览文章";
            case "comment" -> "发表评论";
            case "upvote" -> "点赞文章";
            case "sign_in" -> "每日签到";
            case "recharge" -> "虚拟支付充值";
            case "new_user" -> "新人见面礼";
            case "draw" -> "翻牌中奖";
            case "draw_cost" -> "翻牌消耗";
            case "exchange_publish" -> "积分兑换·发布券";
            case "exchange_dashboard" -> "积分兑换·数据看板";
            case "admin_adjust" -> "系统调整";
            default -> "积分变动";
        };
    }
}
