package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.List;

/** 投稿文章请求体（正文为 Markdown） */
@Data
public class UserPublishArticleRequest {

    private String title;

    /** Markdown 正文 */
    private String content;

    /** 摘要，可为空 */
    private String summary;

    /** 封面图地址，可为空 */
    private String cover;

    /**
     * 分类：站内已有分类的 metadata.name / slug / 显示名皆可，最多 3 个。
     *
     * <p>只匹配已有分类，匹配不到会被忽略（不自动新建）——手填名字写错就悄悄多出一个分类，
     * 属于意料之外的副作用；自动创建只留给 AI 日报那条路径。</p>
     */
    private List<String> categories;

    /** 标签：同分类口径（只匹配已有，不新建），最多 5 个 */
    private List<String> tags;

    /**
     * 是否允许评论（Halo 原生字段）。
     *
     * <p>用包装类型而不是 boolean：投稿时不传表示「按站点默认（允许）」，
     * 而编辑时必须能区分「没带这个字段」与「显式关掉评论」——前者应保持文章原值。</p>
     */
    private Boolean allowComment;
}
