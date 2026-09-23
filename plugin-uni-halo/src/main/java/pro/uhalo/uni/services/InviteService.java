package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.AdminInviteRecordListResponse;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.InviteInfoDTO;
import pro.uhalo.uni.dto.InviteLandingDTO;
import reactor.core.publisher.Mono;

/**
 * 邀请裂变：邀请码生成、邀请关系绑定与邀请人奖励发放。
 */
public interface InviteService {

    /** 查询邀请信息（无邀请码时惰性生成） */
    Mono<InviteInfoDTO> getInviteInfo(String openid);

    /**
     * 处理带邀请码的新用户注册：绑定邀请关系并为邀请人发放积分。
     * 幂等（同一被邀请人只处理一次），任何失败仅记录日志，不抛出。
     * <p>仅当 {@code isNewUser} 为 true（本次登录注册新建的小程序用户，即首次进入小程序）时才会发放，
     * 已进入过小程序的存量用户不享受邀请奖励。
     *
     * @return true 表示本次实际执行了邀请绑定与发奖
     */
    Mono<Boolean> grantInviteReward(String inviteeOpenid, String inviteCode, boolean isNewUser);

    /**
     * 把邀请人订阅的站点分类继承给被邀请人（作为他首页的起点）。
     *
     * <p>只在被邀请人「还没有自己的订阅、也没继承过」时写入；老用户打开别人的分享
     * 不会因此被改写首页。任何失败仅记录日志，不抛出——继承是锦上添花，
     * 绝不能连累登录。</p>
     *
     * @return true 表示本次真的写入了继承项
     */
    Mono<Boolean> applyInviteInheritance(String inviteeOpenid, String inviteCode);

    /**
     * 分享落地页数据：分享人是谁、他订阅了哪些分类、访问者是什么状态。
     *
     * <p>匿名可读——被分享者往往是还没登录的新用户，要求先登录才能看内容会把裂变入口堵死。</p>
     *
     * @param viewerOpenid 访问者 openid，可为空（未登录）
     */
    Mono<InviteLandingDTO> landing(String inviteCode, String viewerOpenid);

    /**
     * 老用户点「也关注这些」：把分享人订阅的分类并进他自己的订阅。
     *
     * <p>并入时自己已有的优先占满名额（自己 5 个 + 继承位 3 个），所以最多从 5 个变 8 个，
     * 不会把用户原来的订阅挤掉。</p>
     */
    Mono<DigestSubscriptionDTO> acceptRecommendation(String viewerOpenid, String inviteCode);

    /**
     * 后台邀请明细（谁邀请谁、发放积分）+ 按邀请人汇总（邀请人数、总积分）。
     * items 分页，summary 为全量聚合（含生成过邀请海报的用户，按总积分倒序）。
     */
    Mono<AdminInviteRecordListResponse> listInviteRecordsForAdmin(String keyword, int page, int size);

    /** 记录用户已生成邀请海报（生成成功后上报，管理端汇总据此展示） */
    Mono<Boolean> markPosterGenerated(String openid);

    /** 绑定邀请海报图片地址（上传到后台附件后回填到用户记录） */
    Mono<Boolean> attachPosterImage(String openid, String imageUrl);
}
