package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * AI 日报后台概览：开关状态、下次执行时间、最近一次运行。
 */
@Data
@Builder
public class DigestOverviewDTO {

    private boolean enabled;

    private boolean scheduleEnabled;

    /** 配置的执行时刻原文，如 "07:00" 或 "07:00,19:00" */
    private String scheduleTimes;

    private String timezone;

    /** 下一次计划执行时间；未开启定时时为 null */
    private Instant nextRunAt;

    /** 当前是否有一次运行正在进行 */
    private boolean running;

    private int totalReports;

    private DigestRunSummaryDTO lastReport;

    /** 当前任务的实时进度（生成阶段描述 / 逐篇发布进度），空闲时为 running=false */
    private DigestProgressDTO progress;
}
