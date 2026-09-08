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

    /** 每日翻牌抽奖：每天一次，先扣 cost 再中奖 */
    Mono<PointsDrawResponse> draw(String openid);

    /** 今日翻牌状态（已翻/未翻 + 消耗规则），不产生任何扣分 */
    Mono<PointsDrawResponse> drawStatus(String openid);

    /** 积分兑换：item = publish_ticket | dashboard_advanced，扣除积分并发放权益 */
    Mono<PointsExchangeResponse> exchange(String openid, String item);

    /** 兑换中心余额/今日状态查询 */
    Mono<PointsExchangeResponse> exchangeStatus(String openid);

    Mono<PointsHistoryResponse> listHistory(String openid, int page, int size);

    Mono<AdminPointsRecordListResponse> listAllRecordsForAdmin(String action, String keyword,
        int page, int size);
}
