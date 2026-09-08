package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DashboardSummaryDTO;
import reactor.core.publisher.Mono;

public interface DashboardService {

    Mono<DashboardSummaryDTO> summary(int topArticlesLimit);
}
