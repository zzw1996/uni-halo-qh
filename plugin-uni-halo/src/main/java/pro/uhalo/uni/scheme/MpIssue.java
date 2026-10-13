package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 公众号稿件与推送记录：一条记录对应「一篇准备进公众号的文章」。
 *
 * <p>两种来源共用一个类型（避免第二张表与第二套注册逻辑）：</p>
 * <ul>
 *   <li>{@code kind=ISSUE}：按方向生成的公众号专属文章（每方向每天一条，资源名即幂等键）；</li>
 *   <li>{@code kind=BACKFILL}：从历史 AI 日报期次补推的稿子（合并版或逐篇）。</li>
 * </ul>
 *
 * <p>生成内容（markdown/html/封面）与推送结果同表保存：重新推送、复制正文、
 * 换图片地址都不需要重跑抓取与 AI，也保证「推出去的和当时生成的一致」。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "MpIssue", plural = "mpIssues", singular = "mpIssue")
public class MpIssue extends AbstractExtension {

    /**
     * 来源类型：ISSUE（按方向生成）/ BACKFILL（历史日报补推）。
     *
     * <p><b>字段名不能叫 {@code kind}</b>：{@code AbstractExtension} 自带
     * {@code getKind()/setKind()}，Halo 的 {@code ExtensionOperator.groupVersionKind()}
     * 就是拿实例的 {@code apiVersion + kind} 去查 scheme。子类再声明一个 {@code kind}
     * 字段会让 Lombok 生成的访问器覆盖基类方法，于是 Halo 把来源类型（{@code ISSUE}）
     * 当成了扩展的 kind，注册/写入时报
     * {@code Scheme not found for uni.uhalo.pro/v1alpha1/ISSUE}。
     * 同理，任何字段都不要取名 {@code apiVersion} / {@code metadata}。</p>
     */
    private String sourceKind;

    /** 幂等键来源：ISSUE = 日期 + 方向 key；BACKFILL = 日报记录名（+模式） */
    private String sourceKey;

    /** 日期（yyyy-MM-dd，站点时区） */
    private String date;

    /** 方向 key（BACKFILL 为空） */
    private String direction;

    /** 方向中文名（展示用） */
    private String directionLabel;

    /** 触发来源：manual（管理页点击）/ scheduled（定时窗口） */
    private String trigger;

    /** 推送过程日志：每步一行（准备正文 / 换图床 / 封面素材 / 建草稿 / 发布），便于事后确认走到哪一步 */
    private String pushLog;

    /** 公众号标题（已按官方 32 字上限收敛） */
    private String title;

    /** 摘要 / 引言（总是显式生成：留空时微信会抓正文前 54 字） */
    private String digest;

    /** 摘要的实际字数（管理页显示 xx/120） */
    private Integer digestWords;

    /** 生成时的 Markdown（存档用，也是重渲染正文的来源） */
    private String markdown;

    /** 微信可用的正文 HTML（图片还是站点地址） */
    private String html;

    /** 图片已换成微信图床地址的正文 HTML（首次推送/复制时生成并缓存） */
    private String wechatHtml;

    /** 封面地址（Halo 附件绝对地址） */
    private String cover;

    /** 封面是否为服务端生成的标题卡片 */
    private Boolean coverPlaceholder;

    /** 封面的永久素材 media_id（推送后缓存，重推不再消耗素材配额） */
    private String coverMediaId;

    /** 正文图片没能换成微信图床的张数与原因（展示用） */
    private String imageNote;

    /**
     * 内容完整性说明（展示用）。
     *
     * <p>大模型偶尔只回一半（有概述、没小编看法）或输出被长度上限截断，过去这种「半篇」
     * 是静默的：稿子照样标 GENERATED，打开文章才发现少了一块。现在把「哪几篇缺什么、
     * 为什么」写在这条记录上，列表页直接提示，站长一眼就能决定是重试还是照发。</p>
     */
    private String contentNote;

    /** 精选条目（JSON 数组：标题/来源/链接/概述/分类），管理页展示用 */
    private String articles;

    private Integer articleCount;

    /**
     * 状态：
     * GENERATING（生成中）/ GENERATED（已生成待推送）/ DRAFTED（已进草稿箱）/
     * PUBLISHED（已发布）/ MASSED（已群发）/ EMPTY（该方向本期没有候选）/
     * FAILED（生成或推送失败，见 error）
     */
    private String state;

    /** 实际推送方式：draft / publish / mass（未推送时为空） */
    private String pushMode;

    /** 草稿 media_id（推送成功后写入） */
    private String draftMediaId;

    /** 发布任务 id（freepublish/submit 返回） */
    private String publishId;

    /** 发布状态（freepublish/get 的 publish_status，0 = 成功） */
    private Integer publishStatus;

    /** 发布后的文章地址（article_url） */
    private String articleUrl;

    /** 同步发布到站点时的文章名与地址（postToSite 开启时） */
    private String sitePostName;

    private String sitePostUrl;

    /** 微信错误码与原始 errmsg（推送失败时保留，便于对账） */
    private Integer errcode;

    private String errmsg;

    private String error;

    /** AI 花费（JSON，沿用日报的费用快照结构，可为空） */
    private String aiCost;

    /** 尝试次数（生成与推送共用，用于限制自动重试） */
    private Integer attempts;

    private Instant startedAt;

    private Instant finishedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
