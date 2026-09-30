package pro.uhalo.uni.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.dto.UserPublishMomentRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.UserPublishService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.multipart.FilePart;

import java.util.Map;
import java.util.function.Function;

/**
 * 用户投稿接口。
 *
 * <p>鉴权沿用各接口一致的 {@code Wechat-Session-Id}（openid）；积分校验、角色策略、
 * 内容安全检测与发布都在服务端完成，前端只负责展示结论。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPublishController {

    private final UserPublishService userPublishService;

    /** 投稿状态：开关、门槛、价格、余额、角色策略结论、今日已发数 */
    public Mono<ServerResponse> status(ServerRequest request) {
        return withOpenid(request, openid -> userPublishService.getStatus(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> publishArticle(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, UserPublishArticleRequest.class))
            .flatMap(body -> userPublishService.publishArticle(openid, body))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> publishMoment(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, UserPublishMomentRequest.class))
            .flatMap(body -> userPublishService.publishMoment(openid, body))
            .flatMap(this::jsonOk));
    }

    /** 瞬间配图上传：multipart，字段名 file */
    public Mono<ServerResponse> uploadMomentImage(ServerRequest request) {
        return withOpenid(request, openid -> request.multipartData()
            .flatMap(parts -> {
                var part = parts.getFirst("file");
                if (!(part instanceof FilePart filePart)) {
                    return Mono.error(new WxAuthException("缺少 file 字段"));
                }
                return userPublishService.uploadMomentImage(openid, filePart);
            })
            .flatMap(url -> jsonOk(Map.of("url", url))));
    }

    /** 提交投票（非匿名场景，服务端带令牌提交） */
    public Mono<ServerResponse> submitVote(ServerRequest request) {
        var name = request.pathVariable("name");
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> parseJsonNode(raw))
            .flatMap(body -> userPublishService.submitVote(openid, name, body))
            .flatMap(this::jsonOk));
    }

    private Mono<com.fasterxml.jackson.databind.JsonNode> parseJsonNode(String raw) {
        try {
            return Mono.just(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(raw == null || raw.isBlank() ? "{}" : raw));
        } catch (Exception ex) {
            return Mono.error(new WxAuthException("请求体格式错误"));
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** 删除瞬间：博主/管理员可删任意，普通用户只能删自己的（判定在服务端） */
    public Mono<ServerResponse> deleteMoment(ServerRequest request) {
        var name = request.pathVariable("name");
        return withOpenid(request, openid -> userPublishService.deleteMoment(openid, name)
            .then(jsonOk(Map.of("success", true))));
    }

    private Mono<ServerResponse> withOpenid(ServerRequest request,
        Function<String, Mono<ServerResponse>> handler) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return handler.apply(openid)
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("user publish endpoint failed: {}", e.getMessage(), e);
                // 带上异常类名：业务异常都已在此之前转成 WxAuthException，
                // 走到这里的属于未预期 bug，类名能帮站长快速定位（完整堆栈在日志）
                return badRequest("投稿失败：" + e.getClass().getSimpleName() + "，请稍后重试");
            });
    }

    private String resolveOpenid(ServerRequest request) {
        return request.headers().firstHeader("Wechat-Session-Id");
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> badRequest(String reason) {
        return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", reason));
    }
}
