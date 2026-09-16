package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

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

    /** 主模型通道（OpenAI 兼容的 chat/completions 协议） */
    @Builder.Default
    private String aiApiBase = "https://api.deepseek.com/v1";

    @Builder.Default
    private String aiApiKey = "";

    @Builder.Default
    private String aiModel = "deepseek-chat";

    /** 兜底通道：主通道调用失败时自动切换，留空则不启用 */
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
