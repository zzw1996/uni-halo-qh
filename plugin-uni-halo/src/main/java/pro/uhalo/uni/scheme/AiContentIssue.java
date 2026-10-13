package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 用户的一份「我的 AI 简报」：一个用户每天一条。
 *
 * <p>资源名由「openid + 日期」派生（见 {@code AiContentFeedService.resourceName}），
 * 所以同一天无论打开多少次都只生成一份、也只调用一次 AI——这既是幂等，也是成本阀门。</p>
 *
 * <p>为什么要落库而不是每次现算：</p>
 * <ul>
 *   <li>AI 导语生成一次之后当天不该变（否则用户每次刷新看到的「今天为你挑了什么」都不一样）；</li>
 *   <li>落库后可以翻历史（「昨天那份」），也给后台留了审计 AI 调用次数的依据；</li>
 *   <li>顺便把当天的排序结果冻住，避免因为日报窗口滑动而让同一份简报前后不一致。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "AiContentIssue", plural = "aiContentIssues", singular = "aiContentIssue")
public class AiContentIssue extends AbstractExtension {

    private String wxOpenid;

    /** 简报日期（yyyy-MM-dd，东八区） */
    private String date;

    /** AI 导语；未开启导语生成或生成失败时为空串（前端按「无导语」渲染） */
    private String intro;

    /** AI 导语；未开启导语生成或生成失败时为空串（前端按「无导语」渲染） */
    private String outro;

    /** 条目列表（AiContentItemDTO 数组的 JSON） */
    private String items;

    /**
     * 这一份的素材是否来自「用户关键词的联网检索」。
     *
     * <p>决定说明文案说「联网检索到的 N 条相关报道」还是「最近 N 小时抓到的 N 条」——
     * 用户要能看出来内容为什么与自己的关键词相关。</p>
     */
    private Boolean searched;

    /** 候选池条数（用来解释「为什么只有这几条」） */
    private Integer candidateCount;

    /** 命中偏好条件的条数（去掉上限截断之前） */
    private Integer matchedCount;

    /** 导语是否由 AI 生成（false = 纯筛选结果，或 AI 失败降级） */
    private Boolean aiGenerated;

    /**
     * 这一份是否用了「最近几小时抓到的实时素材」（null/0 = 退回站内日报内容）。
     *
     * <p>存下来是为了让「这一天这份是怎么来的」可追溯：说明文案要说「最近 N 小时」，
     * 而 N 是当时的配置值，事后改配置不该改写历史简报的说明。</p>
     */
    private Integer liveHours;

    /**
     * 生成状态：{@code RUNNING}（生成中）/ {@code READY}（已完成）/ {@code FAILED}（失败可重试）。
     *
     * <p>为什么状态要落库：生成由用户触发、在服务端后台跑（页面退出也不中断），
     * 所以「跑到哪一步了」必须能被后来的请求读到——页面靠轮询这条记录显示进度，
     * 再进页面才能直接看到结果，而不是重新烧一次 AI。</p>
     *
     * <p>null 视为 {@code READY}：升级前落库的老记录都已完成。</p>
     */
    private String status;

    /** 当前阶段文案（生成中给用户看，例如「AI 正在挑选与撰写」） */
    private String stage;

    /** 进度百分比 0-100（只是观感，不参与任何判定） */
    private Integer progress;

    /** 失败原因（status=FAILED 时有值，前端直接展示） */
    private String error;

    /** 这条记录的最近一次写入时间：用来判断「卡住的生成」（超过阈值按失败处理） */
    private Instant updatedAt;

    private Instant createdAt;
}
