package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户投稿状态：前端据此决定「显示什么、能不能发、发出来是直发还是待审」。
 *
 * <p>判断口径全部收在服务端：前端只认 canPublishXxx 与 disabledReason，
 * 不自己拼条件（否则前后端规则一改就会对不上，出现「按钮能点但一发就报错」）。</p>
 */
@Data
@Builder
public class UserPublishStatusDTO {

    /** 功能总开关 */
    private boolean enabled;

    /** 是否显示投稿入口（后台开关 + 积分门槛判定后的最终结论） */
    private boolean visible;

    /** 积分门槛：余额达到该值才显示入口（0=不限制） */
    private int minPointsToShow;

    /** 距离门槛还差多少积分（已达标为 0），用于「还差 X 积分」提示 */
    private int pointsToUnlock;

    /** 发布文章消耗积分 */
    private int articleCost;

    /** 发布瞬间消耗积分 */
    private int momentCost;

    /** 是否为站点所有者（博主）/管理员：发布免费、直接公开、不受每日上限约束 */
    private boolean privileged;

    /** 当前余额 */
    private long balance;

    /** 是否已微信登录 */
    private boolean loggedIn;

    /** 是否已绑定 Halo 账号（未绑定没有作者身份，不能发布） */
    private boolean haloBound;

    private String haloUserName;

    /** 文章的发布方式：direct（直接发布）/ draft（先草稿，站长审后发布）/ deny（不允许） */
    private String articleMode;

    /** 瞬间的发布方式：direct（直接公开）/ draft（待审核）/ deny（不允许） */
    private String momentMode;

    private boolean canPublishArticle;

    private boolean canPublishMoment;

    /** 不能发布的原因（前端直接展示，空串表示可以发） */
    private String articleDisabledReason;

    private String momentDisabledReason;

    private int todayArticleCount;

    private int todayMomentCount;

    /** 每日上限（0=不限） */
    private int dailyArticleLimit;

    private int dailyMomentLimit;

    private int maxArticleTitleLength;

    private int maxArticleContentLength;

    private int maxMomentLength;

    private int maxMomentImages;

    /** 内容是否走微信内容安全检测 */
    private boolean contentSecurityEnabled;
}
