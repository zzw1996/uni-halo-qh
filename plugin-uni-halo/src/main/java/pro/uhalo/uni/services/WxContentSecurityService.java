package pro.uhalo.uni.services;

import reactor.core.publisher.Mono;

/**
 * 微信内容安全检测（UGC 合规要求）。
 *
 * <p>小程序只要允许用户产生内容，微信审核就会检查是否接入了内容安全接口。
 * 这里只做文本检测（{@code msgSecCheck}）；图片检测需要额外的 mediaCheckAsync 通道，
 * 当前未接入——所以「待审」角色的内容不会立刻公开，图片风险由审核环节兜住。</p>
 */
public interface WxContentSecurityService {

    /**
     * 校验文本。
     *
     * <p>违规直接报错（拒绝发布）；检测接口本身不可用（网络/令牌问题）时放行并在日志里告警，
     * 避免因为微信侧抖动把「发布」整条链路卡死。</p>
     *
     * @param openid  用户 openid（v2 接口带上可提高判定准确度）
     * @param content 待检测文本
     */
    Mono<Void> checkText(String openid, String content);
}
