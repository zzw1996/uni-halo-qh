package pro.uhalo.uni.constants;

/**
 * 插件使用到的常量定义
 *
 * @author 小莫唐尼
 */
public class Constants {
    /**
     * 接口版本
     */
    public static final String PLUGIN_API_VERSION = "v1alpha1";

    /**
     * endpoint 中的接口基础路径
     */
    public static final String END_POINT_API_BASE_PATH = "plugins/plugin-uni-halo";

    /**
     * 基础的域名地址
     */
    public static final String BASIC_DOMAIN_NAME = "uni.uhalo.pro";

    /**
     * 基础的域名接口地址
     */
    public static final String BASIC_DOMAIN_API_NAME = "api." + BASIC_DOMAIN_NAME;

    /**
     * 自定义接口分组名称（给后台管理提供的接口）
     */
    public static final String CONSOLE_CUSTOM_API_GROUP_NAME =
        "console." + BASIC_DOMAIN_API_NAME + "/" + PLUGIN_API_VERSION;

    /**
     * 自定义接口分组名称（给用户中心提供的接口）
     */
    public static final String UC_CUSTOM_API_GROUP_NAME =
        "uc." + BASIC_DOMAIN_API_NAME + "/" + PLUGIN_API_VERSION;

    /**
     * 自定义接口分组名称
     */
    public static final String PUBLIC_CUSTOM_API_GROUP_NAME =
        BASIC_DOMAIN_API_NAME + "/" + PLUGIN_API_VERSION;

    /**
     * 微信小程序用户 openid 请求头
     */
    public static final String WECHAT_SESSION_HEADER = "Wechat-Session-Id";

    /**
     * Halo 官方评论 API 路径前缀
     */
    public static final String HALO_COMMENT_API_PREFIX =
        "/apis/api.halo.run/v1alpha1/comments";

    /**
     * 写入评论 metadata.annotations 的 openid 键
     */
    public static final String UNIHALO_OPENID_ANNOTATION = "unihalo.openid";

    /**
     * 小程序评论用户虚拟邮箱后缀
     */
    public static final String MINI_COMMENT_EMAIL_SUFFIX = "@mini.unihalo.local";

    /**
     * 微信用户未设置昵称时的默认展示名
     */
    public static final String DEFAULT_WX_DISPLAY_NAME = "微信用户";

    public static String buildMiniCommentEmail(String openid) {
        return openid.trim() + MINI_COMMENT_EMAIL_SUFFIX;
    }

    /**
     * 微信小程序用户对应的 Halo 用户名前缀
     */
    public static final String WX_HALO_USERNAME_PREFIX = "wx_";

    public static final String HALO_BIND_STATUS_BOUND = "bound";

    public static final String HALO_BIND_STATUS_UNBOUND = "unbound";

    public static final String HALO_BIND_STATUS_CREATED = "created";

    public static final int HALO_BIND_CODE_EXPIRE_SECONDS = 300;

    public static final int HALO_BIND_CODE_RATE_LIMIT_SECONDS = 60;

    public static final String HALO_BIND_CODE_REDIS_PREFIX = "bind:code:";

    public static final String HALO_BIND_CODE_RATE_PREFIX = "bind:rate:";

    public static String buildWxHaloUsername(String openid) {
        return WX_HALO_USERNAME_PREFIX + openid.trim();
    }

}
