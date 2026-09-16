package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 小程序端可见的一份日报（按日期与时段分组）。
 */
@Data
@Builder
public class DigestPublicReportDTO {

    /** 日报日期 yyyy-MM-dd */
    private String date;

    /** 时段标识（一天可多次生成时区分） */
    private String slot;

    /** 日报封面：取第一篇带图文章的图 */
    private String cover;

    /** 入选篇数 */
    private int articleCount;

    /** 抓取到的总篇数（体现「从多少篇里挑出来的」，是内容价值的量化） */
    private int totalArticles;

    /** 成功抓取的源数量 */
    private int sourceCount;

    /** 已发布的文章数：为 0 表示这份日报还没发出来，前端应跳过 */
    private int publishedCount;

    /** 合并发布模式下的日报正文文章名，可作为文章条目的兜底跳转目标 */
    private String reportPostName;

    private List<DigestPublicArticleDTO> articles;
}
