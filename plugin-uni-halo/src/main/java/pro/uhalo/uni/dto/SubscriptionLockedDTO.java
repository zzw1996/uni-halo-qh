package pro.uhalo.uni.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅墙锁定信息。
 *
 * <p>⚠️ 与 {@link ContentLockedDTO} 同一安全要求：<b>绝不可包含正文</b>，
 * 只携带标题、摘要、封面等列表页本就公开的信息。</p>
 *
 * <p>{@code locked} 字段与付费锁定刻意同名同值——前端 API 层对
 * 「403 且 locked=true」的识别逻辑无需改动，前端再按 {@code type}
 * 区分两种墙、渲染不同的引导卡片。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionLockedDTO {

    /** type 取值：订阅墙 */
    public static final String TYPE_SUBSCRIPTION = "subscription";

    /** 文章 metadata.name */
    private String articleName;

    private String title;

    /** Halo 自动生成的摘要（post.status.excerpt），列表页本就公开 */
    private String excerpt;

    private String cover;

    /** 固定 true：与付费锁定共用前端的 403 识别口径 */
    private Boolean locked;

    /** 固定 "subscription"：前端据此渲染订阅引导而非阅读券解锁 */
    private String type;

    /** 给前端展示的提示文案 */
    private String message;

    /** 文章所属且未订阅的分类（前端「立即订阅」动作的目标） */
    private List<CategoryInfo> categories;

    /** 是否因「订阅已暂停」（额度超限/会员到期）被拦，前端据此引导去解锁 */
    private Boolean paused;

    /** 当前请求是否未登录（前端据此先引导登录） */
    private Boolean needLogin;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryInfo {

        /** 分类 metadata.name，订阅接口用它 */
        private String name;

        /** 分类显示名，界面展示用 */
        private String displayName;
    }
}
