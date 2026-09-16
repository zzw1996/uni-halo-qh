package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 可选的订阅筛选项，全部由服务端从「最近已发布的日报」里统计出来。
 *
 * <p>为什么要服务端算而不是让用户自己填：日报每天只有几篇，用户凭想象填的分类/关键词
 * 很可能从来没有内容，配完发现一片空白就以为功能坏了。这里只给出<b>最近实际出现过</b>
 * 的选项并带上篇数，用户点选即可保证有内容。</p>
 */
@Data
@Builder
public class DigestFilterOptionsDTO {

    private List<DigestCategoryDTO> categories;

    private List<DigestOptionItemDTO> keywords;

    private List<DigestOptionItemDTO> sources;

    /** 统计窗口天数 */
    private int days;

    /** 窗口内参与统计的日报份数 */
    private int reportCount;

    /** 窗口内的文章总数 */
    private int articleCount;

    /** 每类最多给出多少个选项（关键词可能很多，需要截断） */
    private int optionLimit;
}
