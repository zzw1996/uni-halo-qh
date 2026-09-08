package pro.uhalo.uni.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.services.DashboardService;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;

    public Mono<ServerResponse> summary(ServerRequest request) {
        var limit = request.queryParam("top")
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    return 5;
                }
            })
            .orElse(5);
        return dashboardService.summary(limit)
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body));
    }
}
