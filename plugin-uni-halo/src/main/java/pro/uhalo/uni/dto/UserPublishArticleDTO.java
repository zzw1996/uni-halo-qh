package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「我的投稿」列表项。
 *
 * <p>只给作者看自己（Halo 侧按 {@code spec.owner} 过滤），包含尚未发布的待审草稿——
 * 否则提交完待审的作者回到列表什么都看不到，会以为投稿丢了。</p>
 */
@Data
@Builder
public class UserPublishArticleDTO {

    /** 文章标识（Post 的 metadata.name）：打开详情与删除都用它 */
    private String name;

    private String title;

    private String slug;

    /** {@code published}（已发布）/ {@code pending}（待审或未发布） */
    private String state;

    /** 直接给用户看的状态文案 */
    private String stateText;

    private String cover;

    /** 已发布时的永久链接；草稿为空 */
    private String permalink;

    private String publishTime;

    private String createTime;

    /** 分类的 metadata.name（客户端用站点分类表换成显示名） */
    private List<String> categories;

    /** 标签名（通常是可读的 slug，如 travel） */
    private List<String> tags;

    /** 是否允许评论（Halo 的原生字段，编辑时可以改） */
    private boolean allowComment;

    /**
     * 已发布、但正文有尚未生效的修改（head 快照 ≠ release 快照）。
     *
     * <p>Halo 前台只渲染 release 快照，所以「改过了」不等于「站点上已经变了」：
     * 没有直发权限的作者改完，改动只落在草稿上，要等站长重新发布。列表必须如实显示这一点，
     * 否则作者会以为自己的修改没保存成功。</p>
     */
    private boolean hasPendingChanges;

    /** 最近修改时间（ISO-8601），列表用来区分「刚改过」与「很久没动」 */
    private String lastModifyTime;

    // 以下三项来自 Halo 控制台列表的 stats（未发布或取不到时都是 0）

    /** 阅读量 */
    private int visitCount;

    /** 点赞数 */
    private int upvoteCount;

    /** 评论数 */
    private int commentCount;
}
