package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DashboardSummaryDTO {

    private int totalPosts;

    private int totalCategories;

    private int totalTags;

    private int totalComments;

    private int totalVisits;

    private int totalUpvotes;

    private int totalWxUsers;

    /** 累计发放积分（正收益流水合计） */
    private long totalPointsIssued;

    /** 近 7 日发文趋势（含今天，升序） */
    private List<DashboardTrendItemDTO> weekTrend;

    /** 热榜 TOP5 */
    private List<RankArticleDTO> topArticles;
}
