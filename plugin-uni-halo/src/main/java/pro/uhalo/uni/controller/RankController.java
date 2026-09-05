package pro.uhalo.uni.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.services.RankService;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankController {

    private final RankService rankService;

    public Mono<ServerResponse> topArticles(ServerRequest request) {
        var limit = request.queryParam("limit")
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    return 10;
                }
            })
            .orElse(10);
        return rankService.topArticles(limit)
            .flatMap(items -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("items", items)))
            .onErrorResume(ex -> {
                log.warn("rank endpoint failed: {}", ex.getMessage(), ex);
                return ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "热榜加载失败，请稍后重试"));
            });
    }
}
