package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「我的 AI 简报」：一次返回当天那份的内容与解释性字段。
 *
 * <p>解释性字段（{@code summary} / {@code candidateCount} / {@code matchedCount} /
 * {@code configured}）是刻意的：用户花钱买的功能最怕「看不懂为什么没有内容」，
 * 所以空简报必须能说清是「没配条件」「偏好太窄」还是「最近几天站长没生成日报」。</p>
 */
@Data
@Builder
public class AiContentFeedDTO {

    /** 站长的总开关 */
    private boolean enabled;

    /** 权益是否有效；false 时 items 为空、前端展示购买引导 */
    private boolean active;

    /** 简报日期（yyyy-MM-dd） */
    private String date;

    /** AI 导语（可能为空：未开启导语生成，或生成失败降级为纯筛选） */
    private String intro;

    /** 导语是否真的由 AI 生成 */
    private boolean aiGenerated;

    /** 是否配过偏好条件（未配置时简报取最近全部精选） */
    private boolean configured;

    /** 候选池条数（窗口内已发布日报的全部条目） */
    private int candidateCount;

    /** 命中偏好条件的条数（截断到 maxItems 之前） */
    private int matchedCount;

    /** 窗口天数 */
    private int windowDays;

    /** 一句话说明：解释这一份是怎么来的、为空时为什么为空 */
    private String summary;

    private List<AiContentItemDTO> items;
}
