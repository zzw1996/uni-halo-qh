package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅墙配置（设置分组 {@code subscriptionWallConfig}）。
 *
 * <p>订阅墙 = 未订阅分类的文章详情在 API 层被拦截，引导用户先订阅。
 * 与付费内容（阅读券）是两堵独立的墙：付费文章只走阅读券墙，订阅墙对付费文章直接放行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionWallConfigDTO {

    /** 总开关。默认关闭：先部署插件、更新小程序，站长确认后再开启——否则老版本小程序会把订阅墙的 403 渲染成「付费解锁」卡片 */
    private Boolean enabled;

    /** 游客（未登录微信）是否拦截 */
    private Boolean gateGuests;

    /** 会员（有效期内）是否豁免订阅墙 */
    private Boolean memberFree;

    /** 永久免费分类白名单：按分类<b>显示名</b>匹配，逗号分隔（公告类分类用） */
    private String freeCategories;
}
