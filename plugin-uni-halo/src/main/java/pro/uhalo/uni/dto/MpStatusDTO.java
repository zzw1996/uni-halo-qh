package pro.uhalo.uni.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 公众号状态视图（配置摘要 + 生成进度 + 稿件统计）。
 *
 * <p>只下发掩码后的 AppID：AppSecret 永不回显，AppID 也只给前 6 位便于核对填的是哪个号。</p>
 */
@Data
public class MpStatusDTO {

    private boolean enabled;

    /** AppID/AppSecret 是否都已填写 */
    private boolean configured;

    private String appIdMasked;

    private String author;

    private String deliveryMode;

    private String deliveryModeLabel;

    private String contentSourceModeLabel;

    /** 文章粒度：每篇精选各一篇 / 每个方向一篇 */
    private String contentGranularityLabel;

    private boolean needOpenComment;

    private boolean uploadImages;

    private boolean postToSite;

    private boolean scheduleEnabled;

    private String scheduleTimes;

    private String timezone;

    private Instant nextRunAt;

    /** 正在生成 */
    private boolean generating;

    private String stage;

    private Instant generateStartedAt;

    private String generateError;

    /** 正在推送 */
    private boolean pushing;

    /** 推送进度：当前阶段 / 已完成篇数 / 总篇数 / 错误 */
    private String pushStage;

    private int pushCurrent;

    private int pushTotal;

    private String pushError;

    /** 最近一次批量推送的逐篇结果（name / title / ok / message） */
    private List<Map<String, Object>> pushResults;

    /** 最近一次自检是否已经跑过 */
    private boolean selfChecked;

    /** 自检结论：草稿箱这条路（封面素材 + 图床）是否能走通 */
    private boolean pushAvailable;

    /** 自检结论摘要（不用时为空） */
    private String selfCheckSummary;

    /** 方向清单（key / label / emoji / desc） */
    private List<Map<String, String>> directions;

    /** 稿件统计：各状态计数 + 总数 */
    private Map<String, Integer> counts;

    private Instant lastGeneratedAt;

    /** 标题与摘要的官方上限（页面显示 xx/32、xx/120 用） */
    private int titleLimit;

    private int digestLimit;
}
