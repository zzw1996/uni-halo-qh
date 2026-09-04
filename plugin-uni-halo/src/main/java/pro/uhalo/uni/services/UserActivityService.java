package pro.uhalo.uni.services;



import pro.uhalo.uni.dto.AdminUserActivityListResponse;
import pro.uhalo.uni.dto.CancelUpvoteResponse;
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
}

