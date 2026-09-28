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

    /** 条目列表（AiContentItemDTO 数组的 JSON） */
    private String items;

    /** 候选池条数（用来解释「为什么只有这几条」） */
    private Integer candidateCount;

    /** 命中偏好条件的条数（去掉上限截断之前） */
    private Integer matchedCount;

    /** 导语是否由 AI 生成（false = 纯筛选结果，或 AI 失败降级） */
    private Boolean aiGenerated;

    private Instant createdAt;
}
