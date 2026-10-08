package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日报订阅偏好（对外读写下发）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestSubscriptionDTO {

    /** 是否订阅 */
    private boolean enabled;

    /** 关注的分类 key */
    private List<String> categories;

    /** 关注的关键词 */
    private List<String> keywords;

    /** 关注的来源站点 */
    private List<String> sources;

    /** 关注的站点分类（作用于首页主列表与分类页） */
    private List<String> postCategories;

    /** 从邀请人继承来的站点分类（只读展示用，用户不能直接改，只能退订） */
    private List<String> inheritedCategories;

    // ⚠️ 这里刻意没有 inheritedFrom 字段，请不要加回来。
    // 它是邀请人的 openid，而下发对象正是被邀请人——等于把另一个用户的身份标识交给调用方。
    // 本插件的用户接口以 `Wechat-Session-Id: <openid>` 请求头认人（见 PayController.resolveOpenid），
    // 拿到他人 openid 即可冒充其身份。前端展示"好友推荐"只需 inheritedCategories，
    // 小程序与后台控制台都没有读过这个字段。
    // 继承关系到此为止：服务端内部仍用 DigestSubscription.inheritedFrom 计算继承项。

    /**
     * 首页<b>实际生效</b>的分类：自己订阅的（最多 5 个）在前，继承来的（最多 3 个）在后，
     * 去重后总共最多 8 个。
     *
     * <p>由服务端算好下发，前端直接拿去筛选即可——并集、去重、上限、优先级这四件事
     * 只在一个地方定义，避免各端各算一套后不一致。</p>
     */
    private List<String> effectiveCategories;

    /**
     * 额度视图：上限、免费额度、已购额度、积分解锁价格、被暂停的分类。
     *
     * <p>客户端不再自己判断上限（原来硬编码了一个与后端常量对齐的 5），
     * 上限与免费额度都是可配的，必须由服务端下发。</p>
     */
    private SubscriptionLimitsDTO limits;

    /** 最近一次修改时间（yyyy-MM-dd HH:mm），未订阅为 null */
    private String updatedAt;

    /** 是否设过筛选条件；为 false 时「我的订阅」等于「全部」 */
    private boolean filtered;

    /**
     * 用户实际关注的主题分类（{@link #postCategories} 与旧字段 {@link #categories} 的并集）。
     *
     * <p>自动打标上线后 AI 分类 key 与站点分类名是同一串，两个字段其实指向同一批东西；
     * 对上层只暴露这一个合并视图，避免各处都要记得「两个都要看」而漏掉老订阅。</p>
     */
    public java.util.List<String> getFollowedCategories() {
        var merged = new java.util.LinkedHashSet<String>();
        if (postCategories != null) {
            postCategories.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        if (categories != null) {
            categories.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        return java.util.List.copyOf(merged);
    }
}
