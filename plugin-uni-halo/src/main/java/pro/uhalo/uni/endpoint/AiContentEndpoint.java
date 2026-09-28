package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.AiContentPreferenceRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentService;
import pro.uhalo.uni.services.impl.AiContentFeedService;
import pro.uhalo.uni.services.impl.AiContentPreferenceService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * 小程序端的「AI 定制内容」接口（付费权益，匿名可读、写操作要过权益判定）。
 *
 * <p>与 {@code DigestPublicEndpoint} 同一套约定：openid 只从 {@code Wechat-Session-Id}
 * 请求头取（绝不从查询串或请求体取，避免越权看别人的简报），校验类错误把原因原样回给用户。</p>
 *
 * <p><b>边界</b>：未开通/已过期的用户拿不到任何内容（也就不会产生 AI 调用），
 * 只拿到权益状态与购买引导；简报正文永远不会下发——它引用的是已发布的站点文章，
 * 阅读量、评论、付费判定全部复用文章详情页。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentEndpoint implements CustomEndpoint {

    /** 历史简报最多返回多少条（页面是「往期」列表，不需要更多） */
    private static final int MAX_HISTORY = 30;

    private final AiContentService aiContentService;
    private final AiContentFeedService feedService;
    private final AiContentPreferenceService preferenceService;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/AiContent";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/ai/content/preference", this::getPreference,
                builder -> builder
                    .operationId("GetAiContentPreference")
                    .description("Entitlement status, the user's own AI content preferences, the "
                        + "selectable options (categories from the site's digest category list, "
                        + "sources that actually appeared in recent digests) and the limits.")
                    .tag(apiTag))
            .POST(Constants.END_POINT_API_BASE_PATH + "/ai/content/preference", this::savePreference,
                builder -> builder
                    .operationId("SaveAiContentPreference")
                    .description("Save AI content preferences (full replace). Requires an active "
                        + "entitlement; item counts and lengths are hard-limited server-side.")
                    .tag(apiTag)
                    .requestBody(org.springdoc.core.fn.builders.requestbody.Builder
                        .requestBodyBuilder()
                        .implementation(AiContentPreferenceRequest.class)))
            .GET(Constants.END_POINT_API_BASE_PATH + "/ai/content/feed", this::feed,
                builder -> builder
                    .operationId("GetAiContentFeed")
                    .description("Today's personal briefing. Generated on first read (one AI call "
                        + "per active user per day, cached for the rest of the day; degrades to a "
                        + "plain filtered list when AI is unavailable). Use ?date= for history.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("date")
                        .in(ParameterIn.QUERY)
                        .description("Briefing date (yyyy-MM-dd); empty means today")
                        .required(false)
                        .implementation(String.class)))
            .GET(Constants.END_POINT_API_BASE_PATH + "/ai/content/issues", this::issues,
                builder -> builder
                    .operationId("ListAiContentIssues")
                    .description("Past briefings (date, intro and item count only).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("limit")
                        .in(ParameterIn.QUERY)
                        .description("Max records, 1-30, default 10")
                        .required(false)
                        .implementation(Integer.class)))
            .build();
    }

    private Mono<ServerResponse> getPreference(ServerRequest request) {
        var openid = resolveOpenid(request);
        return aiContentService.view(openid)
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取偏好视图失败 openid={}：{}", mask(openid),
                    ex.toString());
                return error(500, "加载失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> savePreference(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return error(400, "请先登录");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, AiContentPreferenceRequest.class))
            .flatMap(body -> aiContentService.save(openid, body))
            .flatMap(this::jsonOk)
            // 校验与权益类错误原样返回原因：用户需要知道是「没开通」「选太多」还是「分类不在清单里」
            .onErrorResume(WxAuthException.class, ex -> error(400, ex.getReason()))
            .onErrorResume(ex -> {
                log.error("[UniHalo][AiContent] 保存偏好失败 openid={}：{}", mask(openid),
                    ex.toString(), ex);
                if (AiContentPreferenceService.isIndexBuildingError(ex)) {
                    return error(503, "Halo 正在为该类数据重建索引，暂时写不进偏好。"
                        + "稍等一会儿重试即可；若一直失败，重启一次 Halo 服务就能恢复"
                        + "（这是服务端的索引状态问题，与你的关注方向无关）");
                }
                return error(500, "保存失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> feed(ServerRequest request) {
        var openid = resolveOpenid(request);
        var date = request.queryParam("date").orElse(null);
        return feedService.feed(openid, date)
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取简报失败 openid={}：{}", mask(openid),
                    ex.toString());
                return error(500, "简报加载失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> issues(ServerRequest request) {
        var openid = resolveOpenid(request);
        var limit = Math.min(MAX_HISTORY, Math.max(1, parseIntParam(request, "limit", 10)));
        return feedService.listIssues(openid, limit)
            .map(items -> {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("items", items.stream().map(AiContentEndpoint::toSummary).toList());
                payload.put("count", items.size());
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取历史简报失败 openid={}：{}", mask(openid),
                    ex.toString());
                return error(500, "历史简报加载失败，请稍后重试");
            });
    }

    /** 历史条目只给日期、导语与条数：往期列表不需要整份内容 */
    private static Map<String, Object> toSummary(AiContentIssue issue) {
        var items = AiContentFeedService.parseItems(issue.getItems());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("date", issue.getDate());
        payload.put("intro", issue.getIntro() == null ? "" : issue.getIntro());
        payload.put("count", items.size());
        payload.put("aiGenerated", Boolean.TRUE.equals(issue.getAiGenerated()));
        return payload;
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> error(int status, String message) {
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", message));
    }

    /** openid 只从会话头取：绝不接受查询串或请求体里的身份 */
    private static String resolveOpenid(ServerRequest request) {
        var openid = request.headers().firstHeader(Constants.WECHAT_SESSION_HEADER);
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    private static int parseIntParam(ServerRequest request, String name, int fallback) {
        return request.queryParam(name).map(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }).orElse(fallback);
    }

    /** openid 是用户标识，日志里只留前几位 */
    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }
}
