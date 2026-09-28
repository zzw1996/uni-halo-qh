package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 日报配置（设置分组 aiDigestConfig）。
 *
 * <p>默认关闭：日报会对外发起大量抓取并按官方接口自动发布文章，属于对站点内容有实际
 * 影响的行为，需要站长确认 AI Key 与发布参数后主动开启。</p>
 */
@Data
@Builder
public class DigestConfigDTO {

    /** 总开关：关闭后定时器与后台「立即生成」都不执行 */
    @Builder.Default
    private boolean enabled = false;

    /** 定时执行开关：关闭后仍可手动生成 */
    @Builder.Default
    private boolean scheduleEnabled = true;

    /** 每天执行时刻，支持逗号分隔多个时刻（如 07:00,19:00），按站点时区计算 */
    @Builder.Default
    private String scheduleTimes = "07:00";

    @Builder.Default
    private String timezone = "Asia/Shanghai";

    /**
     * 大模型通道清单（后台「大模型通道」面板 / 设置页里的 aiProviders）。
     *
     * <p>非空时只按这份清单调用：数组顺序即优先级，第一条失败自动跳下一条，
     * 每条的开关决定它是否参与。留空则沿用下面的「主模型 / 兜底通道」两个字段，
     * 行为与改造前完全一致——老站点不动任何配置就能继续跑。</p>
     */
    @Builder.Default
    private List<DigestAiProviderDTO> aiProviders = List.of();

    /**
     * 模型单价表（后台「模型单价」/ 设置页里的 aiPrices）。
     *
     * <p>用来把 token 数换算成钱。官方不提供用量与账单接口，所以费用只能自己算，
     * 而价格表是唯一能校准它的地方——留空则用插件内置的默认单价（只含 DeepSeek 当前在售模型）。</p>
     */
    @Builder.Default
    private List<DigestAiPriceDTO> aiPrices = List.of();

    /**
     * 价格表为空时是否自动从官网读一次单价（默认开）。
     *
     * <p>开着的话站长什么都不用填：内置价目表先用着，官网读到之后按官网的算。
     * 抓不到就继续用内置表，只记一条日志——绝不猜数字。</p>
     */
    @Builder.Default
    private boolean priceAutoRefreshEnabled = true;

    /** 上次从官网读到单价的时间（面板显示「官网价，读取于 …」，也用于判断要不要再读） */
    @Builder.Default
    private String aiPriceFetchedAt = "";

    /**
     * 运行前后是否查询账户余额（DeepSeek 官方 {@code /user/balance}）。
     *
     * <p>查到的前后差值就是这一轮在账号上实际的扣减，用来校验本地估算准不准。
     * 它是账号级数据：同账号下别的应用/别的 Key 的消耗也会体现在差额里。
     * 不支持的通道会自动跳过，所以开着也不会影响运行。</p>
     */
    @Builder.Default
    private boolean balanceCheckEnabled = true;

    /** 主模型通道（OpenAI 兼容的 chat/completions 协议）；清单留空时才生效 */
    @Builder.Default
    private String aiApiBase = "https://api.deepseek.com/v1";

    @Builder.Default
    private String aiApiKey = "";

    @Builder.Default
    private String aiModel = "deepseek-chat";

    /** 兜底通道：主通道调用失败时自动切换，留空则不启用；清单留空时才生效 */
    @Builder.Default
    private String fallbackApiBase = "";

    @Builder.Default
    private String fallbackApiKey = "";

    @Builder.Default
    private String fallbackModel = "";

    /** 抓取时间窗（小时） */
    @Builder.Default
    private int hours = 48;

    /** 精选篇数 */
    @Builder.Default
    private int topN = 15;

    /**
     * 每个分类至少选中的篇数。
     *
     * <p>改造前精选是「全局按分数取前 topN」，分类只影响正文分组，所以某个分类一篇都没选上是常态。
     * 打开配额后先按分类补齐这一下限，剩下的名额再按分数补满 {@link #topN}——
     * 每天的输出就变成「至少 = 分类数，最多 = 精选篇数」，不再看运气。</p>
     *
     * <p>{@code 0} = 关闭配额，回到改造前的全局排名。</p>
     */
    @Builder.Default
    private int minPerCategory = 1;

    /** 单个分类最多选中的篇数；{@code 0} = 不限（用于个别分类霸榜时压一压） */
    @Builder.Default
    private int maxPerCategory = 0;

    /**
     * 分类在时间窗内没有素材时，是否放宽时间窗再抓一轮把它补上。
     *
     * <p>只在该分类确实没有候选文章时触发，多花一轮 RSS 抓取 + 一轮 AI 评分
     * （评分篇数由 {@link #categoryFallbackScanLimit} 限住）。</p>
     */
    @Builder.Default
    private boolean categoryFallbackEnabled = true;

    /** 补抓用的时间窗（小时），默认 7 天 */
    @Builder.Default
    private int categoryFallbackHours = 168;

    /** 补抓时最多再评分多少篇（控制 AI 成本） */
    @Builder.Default
    private int categoryFallbackScanLimit = 30;

    /**
     * 分类里所有候选都配不到图时的兜底方式：
     * <ul>
     *   <li>{@code placeholder}（默认）——服务端绘制一张标题卡片当封面（分类色 + 分类名 + 标题 + 日期），
     *       保证这一篇仍然「带封面」；</li>
     *   <li>{@code no-cover}——允许这篇不发封面；</li>
     *   <li>{@code skip}——跳过这篇（该分类本期可能因此没有文章，报告里会写明）。</li>
     * </ul>
     */
    @Builder.Default
    private String coverFallbackMode = "placeholder";

    /** 每篇目标字数（概述 + 核心论点合计） */
    @Builder.Default
    private int words = 600;

    /** zh / en */
    @Builder.Default
    private String language = "zh";

    /** 是否抓取并上传文章头图 */
    @Builder.Default
    private boolean imageEnabled = true;

    /**
     * 只发布带封面图的文章。
     *
     * <p>开启后，没配到头图（或头图上传失败）的文章不会进入成稿与发布，
     * 由后续名次的文章补位；全部都没图时本轮直接失败并给出原因，不发无图文章。</p>
     */
    @Builder.Default
    private boolean requireImage = true;

    /** 附件策略名，留空则复用「应用配置」里的 appConfig.appInfo.policyName */
    @Builder.Default
    private String attachmentPolicyName = "";

    /** 附件分组名，留空则复用「应用配置」里的 appConfig.appInfo.fileGroupName */
    @Builder.Default
    private String attachmentGroupName = "";

    /** 是否在生成后自动发布文章 */
    @Builder.Default
    private boolean publishEnabled = true;

    /**
     * 发布方式：
     *
     * <ul>
     *   <li>{@code per-article}：精选 N 篇就发 N 篇独立文章（每篇含标题、原文链接、头图、
     *       概述、核心论点、关键词）；</li>
     *   <li>{@code digest}：合并成一篇「AI 日报」文章（含今日看点、数据概览、分类列表）；</li>
     *   <li>{@code both}：两者都发。</li>
     * </ul>
     */
    @Builder.Default
    private String publishMode = "per-article";

    /**
     * Halo 接口地址（可选）。
     *
     * <p>留空时用 {@code http://127.0.0.1:{server.port}}（插件与 Halo 同进程，本机回环最快）。
     * 如果后台能发文而插件不能，可填站点地址（如 {@code https://www.lxfzzw.com}），
     * 让插件的请求与后台浏览器走完全相同的链路，用于排除中间层差异。</p>
     */
    @Builder.Default
    private String haloBaseUrl = "";

    /** 文章作者（Halo 用户名 metadata.name） */
    @Builder.Default
    private String postOwner = "admin";

    /** 标题模板，支持占位符 {date} */
    @Builder.Default
    private String postTitleTemplate = "AI 日报 · {date}";

    /**
     * 额外附加的分类名（逗号分隔，按 displayName 或 name 匹配，匹配不到的忽略）。
     *
     * <p>自动分类上线后这一项基本用不上了：每篇文章的 AI 分类会自动落到站点分类上。
     * 保留它是为了需要时再钉一个固定分类（例如「AI 日报」）。</p>
     */
    @Builder.Default
    private String postCategories = "";

    /** 自动按 AI 分类打标（不存在则创建站点分类） */
    @Builder.Default
    private boolean autoCategoryEnabled = true;

    /** 自动按 AI 关键词打标签（不存在则创建站点标签） */
    @Builder.Default
    private boolean autoTagEnabled = true;

    /**
     * 建标签的关键词频次门槛。
     *
     * <p>一轮里至少有多少篇文章提到这个词，才值得为它建一个标签。
     * 默认 2：否则一轮就能造出十几个只出现一次的标签，标签墙很快没法看。</p>
     */
    @Builder.Default
    private int autoTagMinCount = 2;

    /** 文章标签名（逗号分隔，按 displayName 或 name 匹配，匹配不到的忽略） */
    @Builder.Default
    private String postTags = "";

    /**
     * 日报分类清单（后台 repeater 填写）。
     *
     * <p>留空 = 沿用内置六类（AI / ML、安全、工程、工具 / 开源、观点 / 杂谈、其他），
     * 与改造前完全一致；填了就变成「AI 只能从这里选，生成的文章也按这里的名称/图标/说明落」。</p>
     */
    @Builder.Default
    private List<DigestCategoryConfigDTO> digestCategories = List.of();

    /**
     * 分类落库方式：
     * <ul>
     *   <li>{@code create}（默认）——站点分类不存在就创建，即改造前行为；</li>
     *   <li>{@code reuse}——只复用已有站点分类（按 key/中文名匹配），匹配不到就不打分类；</li>
     *   <li>{@code map}——按清单里的「映射到已有分类名」落库，同样只匹配不创建。</li>
     * </ul>
     * 后两种是给「不想让日报在站点上多出自动创建的分类」的站点准备的
     * （自动创建的分类会出现在小程序的分类页/订阅列表里）。
     */
    @Builder.Default
    private String categoryMode = "create";

    /**
     * AI 给出清单外分类时的处理：
     * <ul>
     *   <li>{@code other}（默认）——落到清单里名为 {@code other} 的分类；清单里没有它就等同 drop；</li>
     *   <li>{@code drop}——不打分类。</li>
     * </ul>
     */
    @Builder.Default
    private String unknownCategoryPolicy = "other";

    /** 是否输出 Mermaid 图表（小程序等不支持 Mermaid 的场景可关闭，改为纯文本图） */
    @Builder.Default
    private boolean mermaidEnabled = true;

    /** 文末署名行；默认留空（不输出任何署名） */
    @Builder.Default
    private String footerNote = "";

    /**
     * 发布后轮询确认的最长等待秒数。
     *
     * <p>长文发布走异步接口后仍需等服务端生成 release snapshot，这里给足等待时间，
     * 避免因为「发布请求已返回但快照还没生成」而误判失败。</p>
     */
    @Builder.Default
    private int publishWaitSeconds = 600;
}
