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
     * Halo 官方文章详情 API 路径前缀。
     *
     * <p>路径中的 {@code {name}} 是文章的 metadata.name（不是 slug）：
     * 小程序所有跳转详情页的入口都传 {@code post.metadata.name}，
     * 例如 {@code /pagesA/article-detail/article-detail?name=' + post.metadata.name}。
     * 阅读券拦截必须用同一口径，否则会出现「拦截不到」或「误拦截」。</p>
     */
    public static final String HALO_POST_API_PREFIX =
        "/apis/api.content.halo.run/v1alpha1/posts";

    /**
     * Halo 官方 Console 文章接口前缀（建稿、发布）。
     *
     * <p>与 {@link #HALO_POST_API_PREFIX} 的区别：前者是公开读接口，这里是需要个人令牌
     * 的后台写接口。AI 日报用它建稿并发布，正文里的 Markdown 由 Halo 服务端渲染成 HTML，
     * 插件不需要自己实现 Markdown 渲染。</p>
     */
    public static final String HALO_CONSOLE_POST_API_PREFIX =
        "/apis/api.console.halo.run/v1alpha1/posts";

    /**
     * Halo 官方附件上传接口：从远程地址转存。
     */
    public static final String HALO_CONSOLE_ATTACHMENT_UPLOAD_FROM_URL =
        "/apis/api.console.halo.run/v1alpha1/attachments/-/upload-from-url";

    /**
     * Halo 官方附件上传接口：本地文件（multipart，字段 file + policyName + groupName）。
     */
    public static final String HALO_CONSOLE_ATTACHMENT_UPLOAD =
        "/apis/api.console.halo.run/v1alpha1/attachments/upload";

    /**
     * Halo 官方分类 / 标签读接口。
     */
    public static final String HALO_CONTENT_CATEGORIES_API =
        "/apis/api.content.halo.run/v1alpha1/categories";

    public static final String HALO_CONTENT_TAGS_API =
        "/apis/api.content.halo.run/v1alpha1/tags";

    /**
     * Halo 核心用户读接口，用于发布前校验文章作者是否存在。
     */
    public static final String HALO_CORE_USERS_API =
        "/apis/api.halo.run/v1alpha1/users";

    /**
     * Halo 控制台用户接口（列表 + 单查）。
     *
     * <p>判断「某个用户名是否存在」以这里的列表接口为准：它就是后台用户管理页用的接口，
     * 而核心组的单查接口在部分版本/权限下会直接返回 404，不能当作「用户不存在」的证据。</p>
     */
    public static final String HALO_CONSOLE_USERS_API =
        "/apis/api.console.halo.run/v1alpha1/users";

    /**
     * 文章「已发布」标签键。
     *
     * <p>对应 {@code run.halo.app.core.extension.content.Post.PUBLISHED_LABEL}；
     * 插件是通过 HTTP 读 JSON 的，拿不到常量，所以在这里固化一份。</p>
     */
    public static final String HALO_POST_PUBLISHED_LABEL = "content.halo.run/published";

    /**
     * 付费文章标记标签的默认 slug。
     *
     * <p>站长在 Halo 后台给文章打上该 slug 的标签，即表示这篇文章需要阅读券解锁。
     * 之所以用 slug 而不是 displayName：slug 是唯一且稳定的，displayName 可被随意修改；
     * 之所以用标签而不是插件自维护的文章清单：站长在博客后台就能直接操作，零额外配置。</p>
     */
    public static final String DEFAULT_PAID_CONTENT_TAG_SLUG = "paid";

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
