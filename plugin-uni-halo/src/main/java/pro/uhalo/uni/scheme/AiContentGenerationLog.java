package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * AI 定制内容的一次生成记录（审计用）。
 *
 * <p>为什么单独记一张表：简报内容本身「一个用户一天只留最新那份」（{@code AiContentIssue}），
 * 用户改了方向再生成就把上一份覆盖了——站长在后台需要看到「谁、什么时候、谁触发的、
 * 生成了什么、扣了几次」，这正是这张表要回答的。它同时也是「今天已经生成几次」的计数依据
 * （{@link #dayKey} 上有索引）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "AiContentGenerationLog", plural = "aiContentGenerationLogs",
    singular = "aiContentGenerationLog")
public class AiContentGenerationLog extends AbstractExtension {

    private String wxOpenid;

    /** 生成日期（yyyy-MM-dd，东八区） */
    private String date;

    /**
     * 计数键 {@code openid:yyyy-MM-dd}：索引字段。
     *
     * <p>「今天已经生成几次」与后台按用户筛记录都走它——把 openid 与日期拼成一个字段，
     * 就只需要一个索引（Halo 的多条件查询要多个索引，代价更大）。</p>
     */
    private String dayKey;

    /** 触发来源：manual（用户点生成）/ scheduled（整点定时）/ push（立即推送） */
    private String trigger;

    /** 结果条数（失败时为 0） */
    private Integer itemCount;

    /** 本次完成后剩余次数；特权用户（不限次）为 null */
    private Integer creditsAfter;

    /** 本次是否扣了次数（特权用户不扣） */
    private Boolean creditsCharged;

    /** 条目标题（JSON 数组）：后台一眼看出「生成了什么」 */
    private String titles;

    /** 本次用的实时素材时间窗（与简报同一口径） */
    private Integer liveHours;

    /** {@code READY} / {@code FAILED} */
    private String status;

    /** 失败原因（status=FAILED 时有值） */
    private String error;

    private Instant createdAt;
}
