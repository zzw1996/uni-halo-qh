package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 日报统计信息，序列化后存在 DigestReport.stats，后台页面直接回显。
 */
@Data
@Builder
public class DigestStatsDTO {

    private int totalFeeds;

    private int successFeeds;

    /** 抓取到的文章总数（未过滤） */
    private int totalArticles;

    /** 时间窗过滤后参与评分的文章数 */
    private int filteredArticles;

    /** 最终精选篇数 */
    private int selected;

    private int hours;

    private int words;

    /** 实测每篇「概述 + 核心论点」的平均字数 */
    private int actualAvgWords;

    /** 成功配上头图并上传的篇数 */
    private int imageCount;

    private String language;

    /** 本次运行耗时（毫秒） */
    private long durationMs;
}
