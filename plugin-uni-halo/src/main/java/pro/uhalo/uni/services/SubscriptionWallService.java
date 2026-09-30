package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.SubscriptionLockedDTO;
import reactor.core.publisher.Mono;

/**
 * 分类订阅墙服务。
 *
 * <p>未订阅分类的文章详情在 API 层拦截（403 + {@link SubscriptionLockedDTO}），
 * 引导用户先订阅再阅读。<b>列表接口一律放行</b>——用户能浏览、不能读全文，
 * 这是刻意的转化漏斗而不是安全边界，因此判定失败一律 fail-open 放行。</p>
 *
 * <p>与付费内容墙（阅读券）是两堵独立的墙：付费文章只走阅读券墙，
 * 订阅墙对付费文章直接放行，不双重拦截。</p>
 */
public interface SubscriptionWallService {

    /**
     * 内容拦截 Filter 使用：判断该文章是否因「分类未订阅」需要拦截。
     *
     * <p>返回 {@code Mono.empty()} 表示放行原生响应；返回 DTO 表示拦截并下发引导信息。
     * 与付费内容墙同一契约。</p>
     *
     * @param openid      微信 openid，可为空（游客）
     * @param articleName 文章 metadata.name
     */
    Mono<SubscriptionLockedDTO> resolveLockIfRequired(String openid, String articleName);
}
