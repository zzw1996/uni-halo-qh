package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简报里的一条内容，随 {@code AiContentIssue} 一起持久化。
 *
 * <p>字段与 {@code DigestPublicArticleDTO} 对齐（它本来就是从日报条目派生出来的），
 * 另外多两个只属于本功能的字段：{@link #reason}（AI 给的一句话推荐语）与
 * {@link #matchedReason}（命中了用户的哪个条件——不告诉用户为什么出现，
 * 筛选对他来说就是个黑盒，不知道该怎么调）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContentItemDTO {

    private String title;

    private String cover;

    /** AI 分类 key */
    private String category;

    private String categoryLabel;

    private String categoryEmoji;

    private java.util.List<String> keywords;

    private String sourceName;

    private String sourceUrl;

    private String summary;

    /** 要跳转的文章（Halo metadata.name）；实时素材与早期数据为空，那时只展示摘要 */
    private String postName;

    /** 原文外链 */
    private String link;

    /**
     * 原文发布时间（{@code MM-dd HH:mm}，东八区）。
     *
     * <p>实时素材池的条目标注「这条有多新」——简报的卖点就是最新，
     * 不标时间用户没法判断它是不是刚从源里抓到的。</p>
     */
    private String publishedAt;

    /** 命中用户偏好的原因（「分类匹配 · AI / ML · 关键词 · llm」） */
    private String matchedReason;

    /** AI 给这条写的一句话推荐理由；未开启 AI 或该条未被选中时为空 */
    private String reason;

    /** 是否为「AI 按用户定制主题直接生成」的条目（无原文出处，内容私有只归该用户） */
    private Boolean generated;

    /** AI 生成的正文（仅 generated=true 时有值；不下发给其他用户，海报只带摘要） */
    private String body;
}
