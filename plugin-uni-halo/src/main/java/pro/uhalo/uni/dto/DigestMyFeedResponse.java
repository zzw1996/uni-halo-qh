package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「我的订阅」日报流。
 */
@Data
@Builder
public class DigestMyFeedResponse {

    /** 站点是否开启了 AI 日报 */
    private boolean enabled;

    /** 该用户是否已订阅 */
    private boolean subscribed;

    /** 是否真的应用了偏好筛选；为 false 时 items 就是全部日报 */
    private boolean personalized;

    /** 匹配情况说明：让「为什么只看到这几篇」一眼可见 */
    private String summary;

    /** 命中篇数 */
    private int matchedCount;

    /** 统计窗口内总篇数 */
    private int totalCount;

    /** 统计窗口天数 */
    private int days;

    private List<DigestPublicReportDTO> items;

    /**
     * 一篇都没匹配上时给出的修正建议（最近实际出现过的热门分类）。
     *
     * <p>不给建议的话，用户只会看到一片空白，不知道是自己选得太窄还是功能坏了。</p>
     */
    private List<DigestCategoryDTO> suggestions;

    /** 空结果时的提示文案 */
    private String message;
}
