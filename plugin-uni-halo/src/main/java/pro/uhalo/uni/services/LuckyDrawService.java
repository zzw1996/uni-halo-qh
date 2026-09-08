package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.AdminLuckyWinningListResponse;
import pro.uhalo.uni.dto.LuckyClaimRequest;
import pro.uhalo.uni.dto.LuckyClaimResponse;
import pro.uhalo.uni.dto.LuckyConfigDTO;
import pro.uhalo.uni.dto.LuckyDrawResponse;
import pro.uhalo.uni.dto.LuckyShipRequest;
import pro.uhalo.uni.dto.LuckyShipResponse;
import pro.uhalo.uni.dto.LuckyWinningUpdateRequest;
import pro.uhalo.uni.dto.MyLuckyWinningDTO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 积分幸运抽奖：每次消耗积分按权重随机抽奖品，
 * 中奖记录写入积分流水，实物奖品生成中奖单（用户填写收货信息，站长人工发放）。
 */
public interface LuckyDrawService {

    /** 抽奖配置（规则公示用，无需登录） */
    Mono<LuckyConfigDTO> getConfig();

    /** 参与一次抽奖：校验余额 → 扣积分 → 权重随机出奖（实物奖品生成中奖单） */
    Mono<LuckyDrawResponse> draw(String openid);

    /** 中奖后提交收货信息（仅本人待填写的中奖单，提交后状态为 submitted） */
    Mono<LuckyClaimResponse> submitClaim(String openid, LuckyClaimRequest request);

    /** 我的中奖单列表（用于展示待填写提醒与发放进度） */
    Mono<List<MyLuckyWinningDTO>> getMyClaims(String openid);

    /** 管理端：中奖单列表（按状态/关键词过滤） */
    Mono<AdminLuckyWinningListResponse> listAllWinningsForAdmin(String status, String keyword,
        int page, int size);

    /** 管理端：标记已发货（仅 submitted 状态可发货，物流信息可为空） */
    Mono<LuckyShipResponse> ship(String winningId, LuckyShipRequest request);

    /** 管理端：取消发货（shipped 回退为 submitted，便于更正物流信息） */
    Mono<LuckyShipResponse> reopenShipment(String winningId);

    /** 管理端：编辑中奖单（状态、收货信息、备注），用于修正用户填错的信息 */
    Mono<LuckyShipResponse> update(String winningId, LuckyWinningUpdateRequest request);

    /** 管理端：删除中奖单 */
    Mono<LuckyShipResponse> delete(String winningId);
}
