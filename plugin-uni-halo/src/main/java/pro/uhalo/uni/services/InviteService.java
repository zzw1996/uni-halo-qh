package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.AdminInviteRecordListResponse;
import pro.uhalo.uni.dto.InviteInfoDTO;
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
     * 后台邀请明细（谁邀请谁、发放积分）+ 按邀请人汇总（邀请人数、总积分）。
     * items 分页，summary 为全量聚合（按总积分倒序）。
     */
    Mono<AdminInviteRecordListResponse> listInviteRecordsForAdmin(String keyword, int page, int size);
}
