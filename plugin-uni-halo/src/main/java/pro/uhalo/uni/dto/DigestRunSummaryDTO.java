package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 日报运行记录摘要（后台列表用，不含 Markdown 正文）。
 */
@Data
@Builder
public class DigestRunSummaryDTO {

    private String name;

    private String date;

    /** HH:mm（定时触发）或 manual-HHmmss（手动触发） */
    private String slot;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    /** SKIPPED / PENDING / PUBLISHED / PARTIAL / FAILED */
    private String publishState;

    private Integer articleCount;

    /** 本次发布出来的文章数 */
    private Integer postCount;

    /** 各篇文章的发布结果（逐篇发布时一篇一条） */
    private List<DigestPostResultDTO> posts;

    private String postName;

    private String postUrl;

    private String cover;

    private String error;

    private Instant startedAt;

    private Instant finishedAt;

    /** 耗时毫秒，未结束时为 null */
    private Long durationMs;

    /**
     * 本轮 AI 花费（按币种），供运行历史列表直接显示。
     *
     * <p>老报告没有采集过用量：列表里就是空数组，前端显示「-」而不是 0。</p>
     */
    private List<DigestAiCostDTO.Money> aiCosts;

    /** 花费的一句话写法（如 {@code ≈¥0.42}），没有可算金额时为空串 */
    private String aiCostText;
}
