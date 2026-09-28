package pro.uhalo.uni.utils;

/**
 * 首页内容流的降级与提示文案（纯函数，便于逐条钉住）。
 *
 * <p>为什么要单独一块：首页「为什么给我看这些」有三种完全不同的原因——没有订阅（走人气推荐）、
 * 订阅被额度暂停、订阅来自邀请人推荐。它们看起来都像「首页没按我的意思来」，
 * 不解释清楚用户只会认为功能坏了（真实反馈：取消全部订阅后首页还在按分类筛，
 * 其实是邀请人的推荐在生效，而当时一句话提示都没有）。</p>
 */
public final class HomeFeedHints {

    /** 没有订阅：首页用人气分类兜底 */
    public static final String FALLBACK_NO_SUBSCRIPTION = "no-subscription";
    /** 订阅只有继承来的推荐（用户自己一个都没订） */
    public static final String FALLBACK_INHERITED_ONLY = "inherited-only";
    /** 订阅了但订阅的分类都没有内容 */
    public static final String FALLBACK_NO_CONTENT = "no-content";
    /** 人气分类也没有内容 */
    public static final String FALLBACK_HOT_EMPTY = "hot-empty";

    private HomeFeedHints() {
    }

    /**
     * 首页内容流的降级结论。
     *
     * @param scope       实际使用的口径（mine / hot）
     * @param ownCount    用户自己订阅的分类数
     * @param inheritedCount 从邀请人继承来的分类数
     * @param pausedCount 因超出额度被暂停的分类数
     * @param itemsEmpty  这一页是否没有内容
     */
    public record Decision(String fallback, String hint) {
    }

    public static Decision decide(String scope, int ownCount, int inheritedCount, int pausedCount,
        boolean itemsEmpty) {
        if (scope == null || !"mine".equalsIgnoreCase(scope)) {
            // 人气兜底：没有订阅（或订阅全被暂停）时就走到这里
            if (itemsEmpty) {
                return new Decision(FALLBACK_HOT_EMPTY, "暂时没有可推荐的内容，先看看全站最新");
            }
            return new Decision(FALLBACK_NO_SUBSCRIPTION,
                "你还没订阅分类，这是大家在读的内容；去发现页选几个，首页就只给你看关心的内容");
        }
        if (itemsEmpty) {
            // 订了却没有内容：与「没有订阅」是两回事，要说清「订阅没丢」
            return new Decision(FALLBACK_NO_CONTENT, "你订阅的分类暂时没有内容，已为你展示全站最新");
        }
        if (pausedCount > 0) {
            return new Decision(null, "有 " + pausedCount
                + " 个分类因超出订阅额度暂停，续费会员或积分解锁后自动恢复");
        }
        if (ownCount == 0 && inheritedCount > 0) {
            // 用户自己一个都没订，内容全来自邀请人的推荐：必须说明，否则他会以为
            // 「我明明取消完了，首页怎么还按分类筛」
            return new Decision(FALLBACK_INHERITED_ONLY,
                "首页这些内容来自邀请人的推荐（" + inheritedCount
                    + " 个分类）；去发现页自己选几个，就会换成你关心的内容");
        }
        return new Decision(null, "");
    }
}
