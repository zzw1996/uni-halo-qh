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

    /** AI 结尾（小结/看法）；站长关掉或生成失败时为空串（前端不渲染这一段） */
    private String outro;

    /** 本次素材是否来自关键词联网检索 */
    private boolean searched;

    /** 导语是否真的由 AI 生成 */
    private boolean aiGenerated;

    /** 是否配过偏好条件（未配置时简报取最近全部精选） */
    private boolean configured;

    /** 候选池条数（实时抓取时 = 时间窗内抓到的篇数；退回日报时 = 窗口内已发布的条目数） */
    private int candidateCount;

    /** 命中偏好条件的条数（截断到 maxItems 之前） */
    private int matchedCount;

    /** 退回「站内日报内容」时的窗口天数 */
    private int windowDays;

    /**
     * 这一份取自「最近几小时抓到的实时素材」；0 = 退回站内日报内容。
     *
     * <p>前端据此说清内容的时效（「内容取自最近 48 小时」），也是排查
     * 「为什么今天这份看起来像旧闻」的第一手线索。</p>
     */
    private int liveHours;

    /** 一句话说明：解释这一份是怎么来的、为空时为什么为空 */
    private String summary;

    /**
     * 生成状态：{@code NONE}（今天还没生成）/ {@code RUNNING}（生成中）/ {@code READY} /
     * {@code FAILED}。
     *
     * <p>页面据此决定显示什么：NONE 给「生成」按钮，RUNNING 给进度，READY 给内容，
     * FAILED 给原因与重试。</p>
     */
    private String status;

    /** 生成中的阶段文案（status=RUNNING 时有值） */
    private String stage;

    /** 进度百分比 0-100（status=RUNNING 时用于进度条） */
    private int progress;

    /** 失败原因（status=FAILED 时有值） */
    private String error;

    /** 还能生成几次；{@code -1} = 不限（特权用户）或本次未查（前端据此不显示剩余次数） */
    private long remainingGenerations;

    /** 今天已经生成几次 */
    private int todayGenerations;

    /** 每天最多生成几次（0 = 不限每天） */
    private int dailyGenerationLimit;

    private List<AiContentItemDTO> items;
}
