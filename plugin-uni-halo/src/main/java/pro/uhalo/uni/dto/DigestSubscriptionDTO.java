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
