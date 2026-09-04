package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.AdminPointsRecordListResponse;
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

    Mono<PointsHistoryResponse> listHistory(String openid, int page, int size);

    Mono<AdminPointsRecordListResponse> listAllRecordsForAdmin(String action, String keyword,
        int page, int size);
}
