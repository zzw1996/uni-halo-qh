package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /** 时间窗过滤 + 去重后参与评分的文章数 */
    private int filteredArticles;

    /** 最终精选篇数 */
    private int selected;

    private int hours;

    private int words;

    /** 实测每篇「概述 + 核心论点」的平均字数 */
    private int actualAvgWords;

    /** 成功配上头图并上传的篇数 */
    private int imageCount;

    /** 其中由服务端生成的标题卡片封面数（配不到原文头图时的兜底） */
    private int placeholderCoverCount;

    /** 因重复转载去掉的篇数 */
    private int dedupedCount;

    /** 分类补抓时额外参与评分的篇数（未开启或没缺口时为 0） */
    private int fallbackFetched;

    /** 最终选中的补位文章数（来自放宽后的时间窗） */
    private int supplementCount;

    /** 本轮「每个分类至少几篇」的设置值，0 = 未开启配额 */
    private int minPerCategory;

    /** 分类 key → 精选篇数（含兜底分类；空 key 表示未打分类） */
    private Map<String, Integer> categoryCounts;

    /** 分类分布的显示文案（「AI / ML 3 · 安全 1」）：后台面板直接显示，不必自己维护分类名映射 */
    private String categorySummary;

    /** 参与配额要求的分类（清单里除兜底分类外的全部 key） */
    private List<String> requiredCategories;

    /** 没凑够 minPerCategory 篇的分类 key */
    private List<String> missingCategories;

    private String language;

    /**
     * 本轮的 AI 花费块（按任务/通道估算 + 运行前后的账户余额）。
     *
     * <p>老报告里没有这个字段：前端据此显示「未采集」，而不是把它当成 0——0 会被读成「免费」。</p>
     */
    private DigestAiCostDTO aiCost;

    /** 本次运行耗时（毫秒） */
    private long durationMs;
}
