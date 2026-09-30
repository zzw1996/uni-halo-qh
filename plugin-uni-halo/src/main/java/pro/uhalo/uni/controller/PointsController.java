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
import pro.uhalo.uni.dto.ContentUnlockRequest;
import pro.uhalo.uni.dto.MembershipStatusDTO;
import pro.uhalo.uni.dto.ExchangeRequest;
import pro.uhalo.uni.dto.OutfitBuyRequest;
import pro.uhalo.uni.dto.OutfitEquipRequest;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.PointsEarnStatusResponse;
import pro.uhalo.uni.dto.WearTitleRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.ContentTicketService;
import pro.uhalo.uni.services.MembershipService;
import pro.uhalo.uni.services.DailyTaskService;
import pro.uhalo.uni.services.InviteService;
import pro.uhalo.uni.services.LevelService;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.UniHaloService;
import pro.uhalo.uni.utils.WxRequestParser;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.multipart.FilePart;

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
    private final ContentTicketService contentTicketService;
    private final MembershipService membershipService;
    private final UniHaloService uniHaloService;

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
        // 可选动作码过滤：如 action=sign_in 只取签到明细
        var action = request.queryParam("action").orElse(null);
        return withOpenid(request, openid -> pointsService.listHistory(openid, action, page, size)
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

    /**
     * 分享落地页数据。匿名可读：被分享者往往还没登录，要求先登录才能看内容会把裂变入口堵死。
     */
    public Mono<ServerResponse> inviteLanding(ServerRequest request) {
        var code = request.queryParam("code").orElse(null);
        var openid = resolveOpenid(request);
        return inviteService.landing(code, openid)
            .flatMap(this::jsonOk)
            .onErrorResume(Exception.class, e -> {
                log.warn("invite landing failed: {}", e.getMessage());
                return badRequest("分享内容加载失败，请稍后重试");
            });
    }

    /** 老用户点「也关注这些」：把分享人的订阅并进自己的（自己优先，最多 8 个） */
    public Mono<ServerResponse> inviteAccept(ServerRequest request) {
        var code = request.queryParam("code").orElse(null);
        return withOpenid(request, openid -> inviteService.acceptRecommendation(openid, code)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> invitePosterGenerate(ServerRequest request) {
        return withOpenid(request, openid -> inviteService.markPosterGenerated(openid)
            .flatMap(this::jsonOk));
    }

    public Mono<ServerResponse> invitePosterUpload(ServerRequest request) {
        return withOpenid(request, openid -> request.multipartData()
            .flatMap(parts -> {
                var part = parts.getFirst("file");
                if (!(part instanceof FilePart filePart)) {
                    return badRequest("缺少 file 字段");
                }
                return readFileBytes(filePart)
                    .flatMap(bytes -> uniHaloService.uploadImageToAttachments(
                            bytes, "invite_poster_" + openid.replaceAll("[^A-Za-z0-9_-]", "_"))
                        .flatMap(imageUrl -> inviteService.attachPosterImage(openid, imageUrl)
                            .thenReturn(imageUrl)))
                    .flatMap(imageUrl -> ServerResponse.ok()
                        .bodyValue(Map.of("imageUrl", imageUrl)))
                    .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
                    .onErrorResume(Exception.class, e -> {
                        log.warn("invite poster upload failed: {}", e.getMessage());
                        return badRequest("邀请海报上传失败：" + e.getMessage());
                    });
            }));
    }

    private static Mono<byte[]> readFileBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
            .map(dataBuffer -> {
                try {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return bytes;
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            });
    }

    /**
     * 付费文章锁定状态查询（不扣券）。
     *
     * <p>⚠️ 不使用 {@link #withOpenid}：该方法在缺少 openid 时会直接返回错误，
     * 但未登录用户同样需要知道「这篇文章要付费、我还没登录」，
     * 否则文章详情页会显示成加载失败而不是显示解锁引导卡片。</p>
     */
    public Mono<ServerResponse> contentStatus(ServerRequest request) {
        var articleName = request.queryParam("articleName").orElse(null);
        if (!StringUtils.hasText(articleName)) {
            return badRequest("articleName 不能为空");
        }
        var openid = resolveOpenid(request);
        return contentTicketService.getStatus(openid, articleName)
            .flatMap(this::jsonOk)
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("content status failed: articleName={}, {}", articleName, e.getMessage(), e);
                return badRequest("付费内容状态查询失败，请稍后重试");
            });
    }

    /** 用阅读券解锁一篇付费文章（幂等，已解锁不会重复扣券）。 */
    public Mono<ServerResponse> contentUnlock(ServerRequest request) {
        return withOpenid(request, openid -> WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, ContentUnlockRequest.class))
            .flatMap(body -> contentTicketService.unlock(openid, body.getArticleName()))
            .flatMap(this::jsonOk));
    }

    /** 当前用户的阅读券余额（未登录返回 0，不报错）。 */
    public Mono<ServerResponse> contentTickets(ServerRequest request) {
        var openid = resolveOpenid(request);
        return contentTicketService.getTicketBalance(openid)
            .map(tickets -> Map.of("tickets", tickets))
            .flatMap(this::jsonOk)
            .onErrorResume(Exception.class, e -> {
                log.warn("content tickets failed: {}", e.getMessage());
                return jsonOk(Map.of("tickets", 0));
            });
    }

    /**
     * 已解锁文章列表（需登录）。
     *
     * <p>与 contentTickets 不同：这里返回的是用户自己的消费记录，
     * openid 必须存在，不能像券余额那样对匿名请求返回空数据。</p>
     */
    public Mono<ServerResponse> unlockedArticles(ServerRequest request) {
        var limit = parseIntParam(request, "limit", 100);
        return withOpenid(request, openid -> contentTicketService.listUnlocked(openid, limit)
            .flatMap(this::jsonOk));
    }

    /**
     * 会员状态（未登录返回「未开通」而不是报错）。
     *
     * <p>与 contentTickets 同理不走 withOpenid：未登录用户看到「未开通会员」
     * 比看到一个错误页合理，且前端要据此决定是否展示会员入口。</p>
     */
    public Mono<ServerResponse> membershipStatus(ServerRequest request) {
        var openid = resolveOpenid(request);
        return membershipService.getStatus(openid)
            .flatMap(this::jsonOk)
            .onErrorResume(Exception.class, e -> {
                log.warn("membership status failed: {}", e.getMessage());
                return jsonOk(MembershipStatusDTO.builder()
                    .enabled(false)
                    .active(false)
                    .benefits(java.util.List.of())
                    .build());
            });
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
