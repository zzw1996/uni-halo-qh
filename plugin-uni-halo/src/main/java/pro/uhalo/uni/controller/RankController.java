package pro.uhalo.uni.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.services.RankService;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankController {

    private static final int USER_RANK_DEFAULT_LIMIT = 20;
    private static final int ACTIVITY_DEFAULT_LIMIT = 10;

    private final RankService rankService;

    public Mono<ServerResponse> topArticles(ServerRequest request) {
        var limit = parseIntParam(request, "limit", 10);
        return rankService.topArticles(limit)
            .flatMap(items -> jsonOk(Map.of("items", items)))
            .onErrorResume(ex -> {
                log.warn("rank endpoint failed: {}", ex.getMessage(), ex);
                return badRequest("热榜加载失败，请稍后重试");
            });
    }

    /**
     * 互动动态：最近用户的评论/点赞/投票，公开可读。
     */
    public Mono<ServerResponse> recentActivities(ServerRequest request) {
        var limit = parseIntParam(request, "limit", ACTIVITY_DEFAULT_LIMIT);
        return rankService.recentActivities(limit)
            .flatMap(items -> jsonOk(Map.of("items", items)))
            .onErrorResume(ex -> {
                log.warn("activity feed endpoint failed: {}", ex.getMessage(), ex);
                return badRequest("动态加载失败，请稍后重试");
            });
    }

    /**
     * 用户榜：公开可读；带 Wechat-Session-Id 头时额外返回「我的排名」。
     */
    public Mono<ServerResponse> topUsers(ServerRequest request) {
        var type = request.queryParam("type").orElse("points");
        var range = request.queryParam("range").orElse("all");
        var limit = parseIntParam(request, "limit", USER_RANK_DEFAULT_LIMIT);
        var openid = resolveOpenid(request);
        return rankService.topUsers(type, range, limit, openid)
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> {
                log.warn("user rank endpoint failed: {}", ex.getMessage(), ex);
                return badRequest("榜单加载失败，请稍后重试");
            });
    }

    private static int parseIntParam(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    /** 未登录时请求头不存在，返回 null（榜单仍可读，只是没有「我的排名」） */
    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private static Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", message));
    }
}
