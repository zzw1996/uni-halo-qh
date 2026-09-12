package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 付费文章解锁记录。
 *
 * <p>一条记录代表「某微信用户已解锁某篇文章」，永久有效。
 * metadata.name 由 {@code unlockKey}（openid + 文章名）经 sha256 生成，
 * 因此重复解锁会因资源名冲突而失败，这即是幂等闸门：
 * 并发解锁同一篇文章时只有一个 create 能成功，避免重复扣券。</p>
 *
 * <p>查询走 {@code unlockKey} 单字段索引，内容拦截 Filter 每次请求都会查，
 * 必须命中索引而不能全表扫描。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "ContentUnlock", plural = "contentUnlocks", singular = "contentUnlock")
public class ContentUnlock extends AbstractExtension {

    private String wxUserId;

    private String wxOpenid;

    /** 文章 metadata.name（与前端跳转 article-detail?name= 传的值一致） */
    private String articleName;

    /** 解锁时扣减的券张数（当前固定 1，保留字段以便后续支持多篇打包） */
    private Integer ticketsCost;

    /** 解锁方式：ticket（阅读券）| points（积分直接兑换，预留） */
    private String unlockType;

    private Instant unlockTime;

    /** wxOpenid + articleName 唯一键，用于单字段索引查询与幂等去重 */
    private String unlockKey;
}
