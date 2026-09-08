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
import pro.uhalo.uni.dto.ClaimDailyTaskRequest;
import pro.uhalo.uni.dto.ExchangeRequest;
import pro.uhalo.uni.dto.LuckyClaimRequest;
import pro.uhalo.uni.dto.OutfitBuyRequest;
import pro.uhalo.uni.dto.OutfitEquipRequest;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.PointsEarnStatusResponse;
import pro.uhalo.uni.dto.WearTitleRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.DailyTaskService;
import pro.uhalo.uni.services.InviteService;
import pro.uhalo.uni.services.LevelService;
import pro.uhalo.uni.services.LuckyDrawService;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class PointsController {

    private final PointsService pointsService;
    private final DailyTaskService dailyTaskService;
    private final LevelService levelService;
    private final InviteService inviteService;
    private final LuckyDrawService luckyDrawService;

    public Mono<ServerResponse> overview(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.getOverview(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> earn(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, PointsEarnRequest.class))
            .flatMap(body -> pointsService.earn(openid, body))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> earnStatus(ServerRequest request) {
        var action = request.queryParam("action").orElse(null);
        var articleName = request.queryParam("articleName").orElse(null);
        return withOpenid(request, openid -> pointsService.getEarnStatus(openid, action, articleName)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> signIn(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.signIn(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> newUserGift(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.claimNewUserGift(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> draw(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.draw(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> drawStatus(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.drawStatus(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> exchange(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, ExchangeRequest.class))
            .flatMap(body -> pointsService.exchange(openid, body.getItem()))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> exchangeStatus(ServerRequest request) {
        return withOpenid(request, openid -> pointsService.exchangeStatus(openid)
            .flatMap(this::jsonOk));
    }


    public Mono<ServerResponse> history(ServerRequest request) {
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 10);
        return withOpenid(request, openid -> pointsService.listHistory(openid, page, size)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> dailyTasks(ServerRequest request) {
        return withOpenid(request, openid -> dailyTaskService.getDailyTasks(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> claimTask(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, ClaimDailyTaskRequest.class))
            .flatMap(body -> dailyTaskService.claim(openid, body.getType()))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> levelStatus(ServerRequest request) {
        return withOpenid(request, openid -> levelService.getLevelStatus(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> wearTitle(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, WearTitleRequest.class))
            .flatMap(body -> levelService.wearTitle(openid, body.getTitleId()))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> outfitStatus(ServerRequest request) {
        return withOpenid(request, openid -> levelService.getOutfitStatus(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> buyOutfit(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, OutfitBuyRequest.class))
            .flatMap(body -> levelService.buyOutfit(openid, body.getKey()))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> equipOutfit(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, OutfitEquipRequest.class))
            .flatMap(body -> levelService.equipOutfit(openid, body.getKey()))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> inviteInfo(ServerRequest request) {
        return withOpenid(request, openid -> inviteService.getInviteInfo(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> luckyConfig(ServerRequest request) {
        return luckyDrawService.getConfig()
            .flatMap(this::jsonOk)
            .onErrorResume(Exception.class, e -> {
                log.warn("lucky config failed: {}", e.getMessage());
                return badRequest("抽奖配置加载失败");
            });
    }

    public Mono<ServerResponse> luckyDraw(ServerRequest request) {
        return withOpenid(request, openid -> luckyDrawService.draw(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> luckySubmit(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, LuckyClaimRequest.class))
            .flatMap(body -> luckyDrawService.submitClaim(openid, body))
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> luckyMine(ServerRequest request) {
        return withOpenid(request, openid -> luckyDrawService.getMyClaims(openid)
            .flatMap(this::jsonOk));
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
                log.warn("points endpoint failed: {}", e.getMessage(), e);
                return badRequest("积分操作失败，请稍后重试");
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

    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }
}
