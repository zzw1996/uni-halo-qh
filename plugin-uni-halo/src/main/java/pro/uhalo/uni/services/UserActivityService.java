package pro.uhalo.uni.services;



import pro.uhalo.uni.dto.AdminUserActivityListResponse;
import pro.uhalo.uni.dto.CancelUpvoteResponse;
import pro.uhalo.uni.dto.ContentReactionStatusDTO;
import pro.uhalo.uni.dto.RecordUserActivityRequest;
import pro.uhalo.uni.dto.UpvoteStatusResponse;
import pro.uhalo.uni.dto.UserActivityItemDTO;
import pro.uhalo.uni.dto.UserActivityListResponse;
import pro.uhalo.uni.enums.UserContentType;
import reactor.core.publisher.Mono;

public interface UserActivityService {

    Mono<UserActivityItemDTO> recordActivity(String openid, RecordUserActivityRequest request);

    Mono<UserActivityListResponse> listActivities(String openid, String type, int page, int size);

    Mono<AdminUserActivityListResponse> listAllActivities(String type, String keyword, int page, int size);

    Mono<UpvoteStatusResponse> getUpvoteStatus(String openid, String articleName, UserContentType contentType);

    Mono<CancelUpvoteResponse> cancelActivity(String openid, RecordUserActivityRequest request);

    /** 记录表情反馈（同一用户同一内容换表情 = 删旧建新），不产生积分 */
    Mono<UserActivityItemDTO> recordReaction(String openid, RecordUserActivityRequest request);

    /** 取消我对该内容的所有表情反馈 */
    Mono<CancelUpvoteResponse> cancelReaction(String openid, RecordUserActivityRequest request);

    /** 查询内容表情计数与我的表情（未登录时 mine 为空） */
    Mono<ContentReactionStatusDTO> getReactionStatus(String openid, String articleName,
        UserContentType contentType);
}

