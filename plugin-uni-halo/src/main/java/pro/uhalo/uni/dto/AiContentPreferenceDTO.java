package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 用户偏好（读写下发） */
@Data
@Builder
public class AiContentPreferenceDTO {

    private boolean enabled;

    private List<String> categories;

    private List<String> sources;

    private List<String> keywords;

    /** 用户手工输入的定制主题：简报优先围绕它检索素材、AI 再按主题重写 */
    private List<String> customTopics;

    /** 是否开启每日定时推送 */
    private boolean pushEnabled;

    /** 每日推送整点（0-23）；未设置时为 null，前端显示默认时段 */
    private Integer pushHour;

    /** 是否设过筛选条件；没设过时简报取「最近全部精选」而不是空 */
    private boolean configured;

    /** 最近一次生成简报的日期（yyyy-MM-dd），没生成过为 null */
    private String lastIssueDate;

    private String updatedAt;
}
