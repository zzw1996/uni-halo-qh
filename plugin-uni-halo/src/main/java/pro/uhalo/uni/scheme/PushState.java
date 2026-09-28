package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 推送调度器的全局状态（单例，资源名固定 {@code push-state}）。
 *
 * <p>两件事：</p>
 * <ul>
 *   <li><b>新文章检查水位</b>：上次检查到的最新发布时间。用它而不是「当前时间减几分钟」，
 *       是因为插件可能停机几小时——按水位能补发这段时间里的更新（上限见配置的回看窗口）；</li>
 *   <li><b>当日发送计数</b>：整站每日上限的落点。放单例里而不是每次去数 {@code PushRecord}，
 *       是因为发送是串行链路，内存里加一就够了；真正的审计仍以 {@code PushRecord} 为准。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "PushState", plural = "pushStates", singular = "pushState")
public class PushState extends AbstractExtension {

    /** 单例资源名：全插件只有这一条 */
    public static final String RESOURCE_NAME = "push-state";

    /** 新文章水位：已处理过的最新文章发布时间 */
    private Instant lastCheckedPublishTime;

    /**
     * 上次<b>处理到内容</b>的检查时间（后台展示用）。
     *
     * <p>没有新内容时不更新：否则每轮检查都要写一次扩展，而为了一行展示时间做这种写入不值得。</p>
     */
    private Instant lastCheckAt;

    /** 当日计数对应的日期（yyyy-MM-dd，东八区）；跨天由调度器重置 */
    private String dailyDate;

    /** 当日已成功发送条数 */
    private Integer dailySent;

    /** 当日因超出整站上限被跳过的条数（后台告警用） */
    private Integer dailySkipped;
}
