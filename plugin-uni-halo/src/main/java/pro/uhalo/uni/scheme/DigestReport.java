package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * AI 日报运行记录：一次流水线执行对应一条记录，Markdown 全文与发布结果都存在这里。
 *
 * <p>放 Extension 存储（底层数据库）而不是文件系统，好处是插件升级/迁移不丢历史，
 * 后台页面与定时器都只依赖这一张表，不需要额外引入缓存或文件目录。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "DigestReport", plural = "digestReports", singular = "digestReport")
public class DigestReport extends AbstractExtension {

    /** 运行日期（站点时区 yyyy-MM-dd），定时器按它做当日幂等 */
    private String date;

    /** 触发时段：HH:mm 表示定时触发，manual 表示后台手动触发 */
    private String slot;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    /** 生成好的 Markdown 全文 */
    private String markdown;

    /** DigestStatsDTO 序列化后的 JSON */
    private String stats;

    private Integer articleCount;

    /** SKIPPED / PENDING / PUBLISHED / PARTIAL / FAILED */
    private String publishState;

    private String postName;

    private String postUrl;

    /** 本次发布出来的文章数 */
    private Integer postCount;

    /**
     * 各篇文章的发布结果（DigestPostResultDTO 列表的 JSON）。
     *
     * <p>逐篇发布时一篇一条，能看出哪一篇失败、失败原因是什么。</p>
     */
    private String posts;

    /**
     * 每篇文章的用户侧数据（JSON 数组，见 DigestArticleEntryDTO）。
     *
     * <p>供小程序端列表与个性化筛选使用；不含正文（正文在已发布的文章里）。</p>
     */
    private String articleEntries;

    /**
     * 待发布的文章内容（DigestPostPayloadDTO 列表的 JSON），生成阶段就写好。
     *
     * <p>「只生成不发布」之后再点发布、或只补发失败篇目时，直接用它，不必重跑流水线。</p>
     */
    private String postPayloads;

    /** 文章封面（第一篇成功上传头图的文章），发布时同时作为封面写入 */
    private String cover;

    private String error;

    /**
     * 本时段已尝试生成的次数。
     *
     * <p>重试会<b>复用同一条记录</b>（资源名按「日期 + 时段」固定，见
     * {@code DigestPersistence.resourceName}），所以「重试次数」只能记在记录上。
     * 早前调度器是按「本时段有几条记录」来判上限的，而那个数字永远是 1，
     * 于是上限永远达不到、失败后每分钟无限重试。</p>
     */
    private Integer attempts;

    private Instant startedAt;

    private Instant finishedAt;
}
