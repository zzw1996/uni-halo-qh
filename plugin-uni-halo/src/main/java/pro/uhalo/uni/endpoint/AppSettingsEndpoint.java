package pro.uhalo.uni.endpoint;

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
import pro.uhalo.uni.dto.AppSettingsUpdateRequest;
import pro.uhalo.uni.dto.AppSettingsViewDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.impl.AppSettingsService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * 小程序端的「应用设置跟账号走」接口：底栏显示项、外观模式、首页布局等。
 *
 * <p>与 {@code AiContentEndpoint} 同一套约定：openid 只从 {@code Wechat-Session-Id}
 * 请求头取（绝不从查询串或请求体取身份），校验类错误把原因原样回给用户。</p>
 *
 * <p><b>为什么是「按字段增量」而不是整包</b>：客户端只提交本次改动的键，
 * 服务端只覆盖这些键。多端同时用（一台改底栏、一台改外观）不会互相抹掉，
 * 也就不需要「谁更新」的判定；同时请求体天然很小（最多十几个布尔/枚举值）。</p>
 *
 * <p><b>安全面</b>：写入键值必须过白名单（见 {@code AppSettingsCatalog}），
 * 存储里只放服务端自己生成的 JSON，客户端原文永不落库；
 * 白名单外的键被丢弃并在 {@code rejected} 里回报，不报错（避免新旧版本客户端互相打挂）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppSettingsEndpoint implements CustomEndpoint {

    private final AppSettingsService appSettingsService;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/AppSettings";
        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/app/settings", this::get,
                builder -> builder
                    .operationId("GetAppSettings")
                    .description("This user's server-side copy of app settings (custom tab bar "
                        + "visibility, appearance mode, home layout, card style ...). Returns an "
                        + "empty view with synced=false when the user has never synced, so the "
                        + "client must not treat that as 'reset my local settings'.")
                    .tag(apiTag))
            .POST(Constants.END_POINT_API_BASE_PATH + "/app/settings", this::save,
                builder -> builder
                    .operationId("SaveAppSettings")
                    .description("Merge saved app settings field by field. Send only the keys that "
                        + "changed; unknown or invalid keys are dropped and reported in "
                        + "'rejected' instead of failing the whole request.")
                    .tag(apiTag)
                    .requestBody(org.springdoc.core.fn.builders.requestbody.Builder
                        .requestBodyBuilder()
                        .implementation(AppSettingsUpdateRequest.class)))
            .build();
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        var openid = resolveOpenid(request);
        return appSettingsService.load(openid)
            .flatMap(this::jsonOk)
            // 读失败不打扰用户：前端拿不到副本时按「本地优先」继续用本地设置
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AppSettings] 读取设置副本失败 openid={}：{}", mask(openid),
                    ex.toString());
                return jsonOk(AppSettingsViewDTO.empty());
            });
    }

    private Mono<ServerResponse> save(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return error(400, "请先登录");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, AppSettingsUpdateRequest.class))
            .flatMap(body -> appSettingsService.merge(openid, body == null ? null
                : body.getSettings()))
            .flatMap(this::jsonOk)
            // 校验类错误原样返回原因（用户需要知道是哪一项没被接受）
            .onErrorResume(WxAuthException.class, ex -> error(400, ex.getReason()))
            .onErrorResume(ex -> {
                log.error("[UniHalo][AppSettings] 保存设置失败 openid={}：{}", mask(openid),
                    ex.toString(), ex);
                if (AppSettingsService.isIndexBuildingError(ex)) {
                    return error(503, "Halo 正在为该类数据重建索引，暂时写不进设置。"
                        + "稍等一会儿重试即可；若一直失败，重启一次 Halo 服务就能恢复"
                        + "（这是服务端的索引状态问题，与你的设置无关）");
                }
                return error(500, "保存失败，请稍后重试");
            });
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

    /** 日志里不出现完整 openid */
    private static String mask(String openid) {
        if (openid == null || openid.length() <= 8) {
            return "***";
        }
        return openid.substring(0, 4) + "***" + openid.substring(openid.length() - 4);
    }
}
