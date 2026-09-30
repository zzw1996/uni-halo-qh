package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * AI 日报「已发文章」记录：一条 = 一篇发布过的文章的去重键。
 *
 * <p>单轮生成内的链接去重（{@code DigestTextUtils.dedupKey}）只管本轮内存，
 * 而抓取时间窗默认 48h、分类补抓最长 168h——第二天的抓取天然与昨天重叠，
 * 没有跨天记录就必然重复。这张表把「发过的文章」记下来，供下一轮在评分前过滤。</p>
 *
 * <p>资源名 = {@code dsa-yyyyMMdd-<hash8>}：同一天内同一篇文章天然幂等（重复 create 冲突即忽略）；
 * 超出记忆窗口后同一篇文章允许再次出现（记录换日期前缀不冲突）——
 * 14 天前的「常青内容」重新入选是合理的，要杜绝的是隔天重复。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "DigestSeenArticle", plural = "digestSeenArticles", singular = "digestSeenArticle")
public class DigestSeenArticle extends AbstractExtension {

    /** 去重键：归一化链接（无链接退标题指纹），口径与单轮去重完全一致 */
    private String dedupKey;

    /** 记录日期（yyyy-MM-dd）：索引字段，查询按「最近 14 天」滚动，老数据无需 GC */
    private String seenDate;

    private String title;

    /** 来源日报的资源名（哪一期用掉的），排查问题时可回溯 */
    private String digestName;

    private Instant seenAt;
}
