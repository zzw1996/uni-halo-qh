package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 一篇「还没归入主题体系」的旧文章，及其 AI 判定结果。
 *
 * <p>这是「旧文章归类」的预览条目：<b>预览阶段不写库</b>，站长看着这张表逐条决定要不要采纳，
 * 因为一次判定要花 AI 调用、而写回分类标签是改站点数据的事——
 * 让机器先给出建议、人再点头，比直接批量改几十篇文章可逆得多。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostClassifyCandidateDTO {

    /** Halo 文章 metadata.name（应用时按它定位文章） */
    private String name;

    private String title;

    /** 摘要（已截断，仅供预览时判断判定是否合理） */
    private String excerpt;

    /** 文章现有的分类显示名（写回时会在其后追加，不会被覆盖） */
    private List<String> categories;

    /** 文章现有的标签显示名 */
    private List<String> tags;

    private Instant publishTime;

    /** AI 判定的主题 key（ai-ml / security / …），未能判定时为空 */
    private String suggestedCategory;

    /** 主题的中文显示名，页面直接展示这个 */
    private String suggestedCategoryLabel;

    /** AI 提取的关键词（已按全站频次门槛筛选，落库时会自动建标签） */
    private List<String> suggestedKeywords;

    /** 未能判定/出错时的说明，页面据此提示「这篇需要手工处理」 */
    private String note;
}
