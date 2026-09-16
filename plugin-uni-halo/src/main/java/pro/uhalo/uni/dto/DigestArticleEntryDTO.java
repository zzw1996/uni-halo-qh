package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日报里单篇文章的「用户侧」数据，随日报报告一起持久化。
 *
 * <p>用途：小程序端的日报列表与个性化筛选。之所以要单独存这一份，是因为</p>
 * <ul>
 *   <li>{@code posts}（发布结果）只有标题与链接，没有封面、分类、关键词、摘要；</li>
 *   <li>{@code postPayloads}（待发布载荷）有正文与封面，但正文很长，不适合下发给小程序列表；</li>
 *   <li>而 AI 流水线其实已经为每篇文章产出了分类、关键词、摘要、评分与来源，
 *       这些正是「让用户自己挑想看什么」的依据，丢掉就太可惜了。</li>
 * </ul>
 *
 * <p>注意：这里不存 {@code postName}。文章名在发布时才产生，而报告可能在发布前就先落库
 * （先存稿再发布是刻意设计，发布失败也能补发），因此文章名由读取时按标题与发布结果对齐，
 * 这样补发之后也不需要回头重写这份数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestArticleEntryDTO {

    /** 展示标题（优先中文译名） */
    private String title;

    /** 封面（已上传到 Halo 附件的地址） */
    private String cover;

    /** AI 分类 key：ai-ml / security / engineering / tools / opinion / other */
    private String category;

    /** 分类中文名（由渲染器同一份元数据给出，前端不必再维护一份映射） */
    private String categoryLabel;

    /** 分类图标 */
    private String categoryEmoji;

    /** AI 提取的关键词 */
    private List<String> keywords;

    /** 来源站点名 */
    private String sourceName;

    private String sourceUrl;

    /** AI 摘要 */
    private String summary;

    /** 原文外链 */
    private String link;
}
