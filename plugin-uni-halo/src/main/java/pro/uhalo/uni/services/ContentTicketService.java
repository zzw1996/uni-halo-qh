package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.ContentLockedDTO;
import reactor.core.publisher.Mono;

/**
 * 付费内容（阅读券）服务。
 *
 * <p>付费文章由站长在 Halo 后台打标签标记（标签 slug 可配置，默认 {@code paid}），
 * 插件不维护文章清单，站长无需在本插件里重复配置。</p>
 *
 * <p>⚠️ 适用范围：拦截点在文章详情 <b>API</b>
 * （{@code /apis/api.content.halo.run/v1alpha1/posts/{name}}），因此付费墙
 * 只保护小程序端。Halo 网页版主题通过 permalink 直接渲染 Snapshot 内容、
 * 不走该 API，博客网站本身仍可阅读全文。若需要网页端也付费，
 * 需另行改造主题或使用 Halo 原生的 INTERNAL/PRIVATE 可见性。</p>
 */
public interface ContentTicketService {

    /**
     * 查询某篇文章对当前用户的锁定状态（不扣券）。
     *
     * @param openid      微信 openid，可为空（未登录时按未解锁处理）
     * @param articleName 文章 metadata.name
     */
    Mono<ContentLockedDTO> getStatus(String openid, String articleName);

    /**
     * 用阅读券解锁一篇文章：扣券 + 写解锁记录，幂等。
     *
     * <p>已解锁过的文章重复调用不会重复扣券（由解锁记录的唯一资源名保证）。</p>
     */
    Mono<ContentLockedDTO> unlock(String openid, String articleName);

    /**
     * 内容拦截 Filter 使用：判断该文章是否付费、当前用户是否已解锁。
     *
     * <p>返回 {@code Mono.empty()} 表示「无需拦截，放行原生响应」；
     * 返回 {@link ContentLockedDTO} 表示应当拦截并返回该锁定信息。
     * 这样设计可让 Filter 不必关心判定细节，也避免把「放行」误写成「拦截」。</p>
     *
     * @param openid      微信 openid，可为空
     * @param articleName 文章 metadata.name
     */
    Mono<ContentLockedDTO> resolveLockIfRequired(String openid, String articleName);

    /** 当前用户持有的阅读券张数（未登录返回 0） */
    Mono<Integer> getTicketBalance(String openid);
}
