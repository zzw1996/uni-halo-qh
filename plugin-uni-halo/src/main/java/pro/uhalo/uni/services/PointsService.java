package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.AdminPointsRecordListResponse;
import pro.uhalo.uni.dto.PointsDrawResponse;
import pro.uhalo.uni.dto.PointsExchangeResponse;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.PointsEarnResponse;
import pro.uhalo.uni.dto.PointsEarnStatusResponse;
import pro.uhalo.uni.dto.PointsHistoryResponse;
import pro.uhalo.uni.dto.PointsOverviewResponse;
import reactor.core.publisher.Mono;

public interface PointsService {

    Mono<PointsOverviewResponse> getOverview(String openid);

    Mono<PointsEarnResponse> earn(String openid, PointsEarnRequest request);

    Mono<PointsEarnStatusResponse> getEarnStatus(String openid, String action, String articleName);

    Mono<PointsEarnResponse> signIn(String openid);

    /** 新用户见面礼：永久幂等（每个 openid 只能领一次），points=0 时静默跳过 */
    Mono<PointsEarnResponse> claimNewUserGift(String openid);

    /**
     * 榜单奖励发放（周榜结算用）：同一 openid 同一周（{@code weekKey}）只发一次，
     * 不受每日积分上限约束。
     */
    Mono<PointsEarnResponse> grantRankReward(String openid, int points, String weekKey,
        String description);

    /** 每日翻牌抽奖：每天一次，先扣 cost 再中奖 */
    Mono<PointsDrawResponse> draw(String openid);

    /** 今日翻牌状态（已翻/未翻 + 消耗规则），不产生任何扣分 */
    Mono<PointsDrawResponse> drawStatus(String openid);

    /** 积分兑换：item = publish_ticket | dashboard_advanced，扣除积分并发放权益 */
    Mono<PointsExchangeResponse> exchange(String openid, String item);

    /** 兑换中心余额/今日状态查询 */
    Mono<PointsExchangeResponse> exchangeStatus(String openid);

    /**
     * 积分明细分页查询。
     *
     * @param action 可选的动作码过滤（如 {@code lucky_draw} 只看抽奖消耗）；为空表示不过滤。
     *               过滤发生在分页<b>之前</b>，因此筛选后的分页与积分合计都是准确的。
     */
    Mono<PointsHistoryResponse> listHistory(String openid, String action, int page, int size);

    Mono<AdminPointsRecordListResponse> listAllRecordsForAdmin(String action, String keyword,
        int page, int size);
}
