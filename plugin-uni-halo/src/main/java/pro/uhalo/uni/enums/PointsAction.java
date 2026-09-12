package pro.uhalo.uni.enums;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.exception.WxAuthException;

import java.util.Locale;
import java.util.Set;

public enum PointsAction {
    BROWSE("browse"),
    COMMENT("comment"),
    UPVOTE("upvote"),
    VOTE("vote"),
    SIGN_IN("sign_in"),
    TASK("task");

    private static final Set<String> EARN_ACTIONS = Set.of(BROWSE.value, COMMENT.value, UPVOTE.value, VOTE.value);

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
            case "vote" -> "参与投票";
            case "task" -> "每日任务";
            case "invite" -> "邀请奖励";
            case "lucky_draw" -> "积分抽奖";
            case "purchase_publish_ticket" -> "道具直购·发布券";
            case "purchase_content_ticket" -> "道具直购·阅读券";
            case "purchase_outfit" -> "道具直购·装扮";
            case "purchase_dashboard" -> "道具直购·数据看板";
            case "sign_in" -> "每日签到";
            case "rank_reward" -> "榜单奖励";
            case "recharge" -> "虚拟支付充值";
            case "new_user" -> "新人见面礼";
            case "draw" -> "翻牌中奖";
            case "draw_cost" -> "翻牌消耗";
            case "exchange_publish" -> "积分兑换·发布券";
            case "exchange_publish_ticket" -> "积分兑换·发布券";
            case "exchange_content_ticket" -> "积分兑换·阅读券";
            case "unlock_content" -> "解锁付费文章";
            case "exchange_dashboard" -> "积分兑换·数据看板";
            case "admin_adjust" -> "系统调整";
            default -> {
                // 积分购买装扮的流水动作码为动态拼接 outfit_{key}，在此统一做前缀映射。
                if (action.trim().toLowerCase(Locale.ROOT).startsWith("outfit_")) {
                    yield "购买装扮";
                }
                yield "积分变动";
            }
        };
    }
}
