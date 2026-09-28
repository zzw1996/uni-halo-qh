package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 推送看板数据（控制台「消息推送」统计）。
 *
 * <p>看板上最有用的一栏是 {@link #failureReasons}：订阅消息的失败几乎都是「配置没配好」
 * 或「用户没额度」，按微信错误码分组能直接告诉站长该做什么（见
 * {@code PushStatsService.meaningOf}）。</p>
 */
@Data
@Builder
public class PushStatsDTO {

    /** 今日成功发送条数 */
    private int todaySent;

    /** 今日失败条数 */
    private int todayFailed;

    /** 新文章提醒今日成功数 */
    private long newPostSent;

    /** 简报提醒今日成功数 */
    private long aiDigestSent;

    /** 历史推送记录总数（含失败，用于判断功能有没有在跑） */
    private int totalRecords;

    /** 失败原因分组（按错误码，数量倒序） */
    private List<FailureReason> failureReasons;

    /** 今日记录样本（最多 10 条，便于快速定位） */
    private List<RecordSample> todaySamples;

    @Data
    @Builder
    public static class FailureReason {
        private int errorCode;
        private int count;
        /** 这个错误码意味着什么、站长该做什么 */
        private String meaning;
        private List<RecordSample> samples;
    }

    @Data
    @Builder
    public static class RecordSample {
        /** 已脱敏：只留 openid 前几位 */
        private String openid;
        private String kind;
        private String status;
        private Integer errorCode;
        private String message;
        private String page;
        private String sentAt;
    }
}
