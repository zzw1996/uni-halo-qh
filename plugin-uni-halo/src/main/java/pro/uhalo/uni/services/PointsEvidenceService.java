package pro.uhalo.uni.services;

import reactor.core.publisher.Mono;

/**
 * 积分领取行为证据校验：防止仅靠客户端自报（直接调接口伪造）领取积分。
 *
 * <p>证据链（优先级由强到弱使用）：</p>
 * <ul>
 *   <li>浏览/点赞：插件服务端保存的 UserActivity 记录，且 recordTime 必须为「今天」（浏览每次进页刷新；点赞需重新取消+点赞后才会刷新）</li>
 *   <li>评论：Halo Comment 实体（由评论代理服务端创建，带 unihalo.openid 注解）且创建时间为「今天」</li>
 *   <li>浏览额外要求文章（Post）真实存在</li>
 * </ul>
 */
public interface PointsEvidenceService {

    /**
     * 校验指定用户对指定文章可以领取该行为积分（证据存在且为当日产生）。
     *
     * @param action      browse | comment | upvote
     * @param articleName 文章 metadata.name
     */
    Mono<Boolean> evidenceReady(String openid, String action, String articleName);

    /** 证据缺失时给用户的提示文案 */
    static String missingEvidenceMessage(String action) {
        return switch (action) {
            case "browse" -> "未检测到今日浏览记录，无法领取积分";
            case "comment" -> "未检测到该文章今日的评论记录，无法领取积分";
            case "upvote" -> "未检测到今日点赞记录，无法领取积分";
            case "vote" -> "未检测到今日投票记录，无法领取积分";
            default -> "未检测到有效行为记录，无法领取积分";
        };
    }
}
