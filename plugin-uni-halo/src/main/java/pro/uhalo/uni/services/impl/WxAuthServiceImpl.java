package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.BindHaloAccountByCodeRequest;
import pro.uhalo.uni.dto.HaloBindResultDTO;
import pro.uhalo.uni.dto.SendHaloBindCodeRequest;
import pro.uhalo.uni.dto.SendHaloBindCodeResponse;
import pro.uhalo.uni.dto.UpdateProfileRequest;
import pro.uhalo.uni.dto.WxAppConfigDTO;
import pro.uhalo.uni.dto.WxAvatarUploadDTO;
import pro.uhalo.uni.dto.WxLoginRequest;
import pro.uhalo.uni.dto.WxUserDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.HaloUserSyncService;
import pro.uhalo.uni.services.WxAuthService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.WxAutoUsernameGenerator;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WxAuthServiceImpl implements WxAuthService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int NICKNAME_MAX_LENGTH = 32;
    private static final String WX_APP_CONFIG_GROUP = "wxAppConfig";
    private static final String APP_CONFIG_GROUP = "appConfig";
    private static final String JS_CODE_TO_SESSION_URL =
        "https://api.weixin.qq.com/sns/jscode2session";

    private static final WebClient WEB_CLIENT = WebClient.builder().build();

    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;
    private final HaloUserSyncService haloUserSyncService;
    private final AttachmentService attachmentService;
    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public Mono<WxAppConfigDTO> getWxAppConfig() {
        return resolveWxAppConfigContext()
            .map(this::toPublicConfig);
    }

    @Override
    public Mono<WxUserDTO> wxLogin(WxLoginRequest request) {
        return Mono.fromCallable(() -> validateLoginRequest(request))
            .flatMap(loginRequest -> loadLoginConfig()
                .flatMap(config -> exchangeCode(config, loginRequest.getCode())
                    .flatMap(session -> handleLogin(session, loginRequest))))
            .onErrorMap(ex -> ex instanceof WxAuthException ? ex
                : new WxAuthException("登录失败：" + ex.getMessage()));
    }

    @Override
    public Mono<WxUserDTO> updateProfile(String openid, UpdateProfileRequest request) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        var profile = request != null ? request : new UpdateProfileRequest();
        var nickname = StringUtils.hasText(profile.getNickname()) ? profile.getNickname().trim() : null;
        var avatarUrl = StringUtils.hasText(profile.getAvatarUrl()) ? profile.getAvatarUrl().trim() : null;
        if (nickname == null && avatarUrl == null) {
            return Mono.error(new WxAuthException("nickname 与 avatarUrl 不能同时为空"));
        }
        if (nickname != null && nickname.length() > NICKNAME_MAX_LENGTH) {
            return Mono.error(new WxAuthException("nickname 长度不能超过 32 个字符"));
        }
        if (avatarUrl != null) {
            validateHttpsUrl(avatarUrl);
        }
        return resolveWxAppConfigContext()
            .flatMap(config -> wxUserService.fetchByOpenid(openid.trim())
                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                .flatMap(wxUser -> {
                    assertProfileModifyAllowed(config.profileModifyLimit(), wxUser);
                    if (nickname != null) {
                        wxUser.setNickname(nickname);
                        wxUser.setProfileComplete(true);
                    }
                    if (avatarUrl != null) {
                        wxUser.setAvatarUrl(processLink(avatarUrl));
                    }
                    wxUser.setProfileModifyCount(resolveModifyCount(wxUser) + 1);
                    return wxUserService.update(wxUser);
                }))
            .flatMap(this::syncBoundHaloProfile)
            .map(this::toCompleteUserDTO);
    }

    @Override
    public Mono<SendHaloBindCodeResponse> sendHaloBindCode(String openid, SendHaloBindCodeRequest request) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        var body = request != null ? request : new SendHaloBindCodeRequest();
        if (!StringUtils.hasText(body.getAccount())) {
            return Mono.error(new WxAuthException("account 不能为空"));
        }
        return resolveWxAppConfigContext()
            .flatMap(context -> {
                if (!context.haloBindEnabled()) {
                    return Mono.error(new WxAuthException("站长尚未开启 Halo 账号关联"));
                }
                return requireUnboundWxUser(openid.trim())
                    .doOnNext(wxUser -> log.info("sendHaloBindCode wx user verified, openid={}, wxUserId={}",
                        wxUser.getOpenid(), wxUser.getId()))
                    .flatMap(wxUser -> haloUserSyncService.sendHaloBindCode(wxUser.getOpenid(), body.getAccount()));
            });
    }

    @Override
    public Mono<WxUserDTO> bindHaloAccountByCode(String openid, BindHaloAccountByCodeRequest request) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        var body = request != null ? request : new BindHaloAccountByCodeRequest();
        if (!StringUtils.hasText(body.getAccount())) {
            return Mono.error(new WxAuthException("account 不能为空"));
        }
        if (!StringUtils.hasText(body.getCode())) {
            return Mono.error(new WxAuthException("code 不能为空"));
        }
        return resolveWxAppConfigContext()
            .flatMap(context -> {
                if (!context.haloBindEnabled()) {
                    return Mono.error(new WxAuthException("站长尚未开启 Halo 账号关联"));
                }
                return requireUnboundWxUser(openid.trim())
                    .doOnNext(wxUser -> log.info("bindHaloAccountByCode wx user verified, openid={}, wxUserId={}",
                        wxUser.getOpenid(), wxUser.getId()))
                    .flatMap(wxUser -> haloUserSyncService.bindHaloAccountByCode(
                            wxUser.getOpenid(), body.getAccount(), body.getCode())
                        .flatMap(result -> persistHaloBinding(wxUser, result)));
            });
    }

    @Override
    public Mono<WxUserDTO> createHaloAccount(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        return resolveWxAppConfigContext()
            .flatMap(context -> {
                if (!context.haloBindEnabled()) {
                    return Mono.error(new WxAuthException("站长尚未开启 Halo 账号关联"));
                }
                return requireUnboundWxUser(openid.trim())
                    .flatMap(wxUser -> haloUserSyncService.createHaloAccount(
                            wxUser.getOpenid(),
                            resolveDisplayName(wxUser),
                            processLink(wxUser.getAvatarUrl()))
                        .flatMap(result -> persistHaloBinding(wxUser, result)));
            });
    }

    @Override
    public Mono<WxUserDTO> unbindHaloAccount(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        var normalizedOpenid = openid.trim();
        return wxUserService.fetchByOpenid(normalizedOpenid)
            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
            .flatMap(wxUser -> {
                var bindStatus = resolveDetailedHaloBindStatus(wxUser);
                if (Constants.HALO_BIND_STATUS_UNBOUND.equals(bindStatus)) {
                    return Mono.error(new WxAuthException("当前未关联 Halo 账号"));
                }
                var haloUserName = wxUser.getHaloUserName();
                log.info("unbindHaloAccount start, openid={}, haloUserName={}, haloBindStatus={}",
                    normalizedOpenid, haloUserName, bindStatus);
                return haloUserSyncService.unbindOpenidAnnotation(haloUserName, normalizedOpenid)
                    .then(persistHaloUnbinding(wxUser));
            })
            .doOnSuccess(result -> log.info("unbindHaloAccount success, openid={}", normalizedOpenid))
            .doOnError(error -> log.error("unbindHaloAccount failed, openid={}", normalizedOpenid, error));
    }

    @Override
    public Mono<WxAvatarUploadDTO> uploadWxAvatar(String openid, FilePart filePart) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        if (filePart == null) {
            return Mono.error(new WxAuthException("缺少 file 字段"));
        }
        return wxUserService.fetchByOpenid(openid.trim())
            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
            .flatMap(user -> uploadAvatarFile(filePart)
                .map(url -> WxAvatarUploadDTO.builder().avatarUrl(url).build()));
    }

    private WxLoginRequest validateLoginRequest(WxLoginRequest request) {
        if (request == null) {
            throw new WxAuthException("请求体格式错误");
        }
        if (!StringUtils.hasText(request.getCode())) {
            throw new WxAuthException("缺少 code 参数");
        }
        request.setCode(request.getCode().trim());
        if (StringUtils.hasText(request.getNickname())) {
            var nickname = request.getNickname().trim();
            if (nickname.length() > NICKNAME_MAX_LENGTH) {
                throw new WxAuthException("nickname 长度不能超过 32 个字符");
            }
            request.setNickname(nickname);
        } else {
            request.setNickname(null);
        }
        if (StringUtils.hasText(request.getAvatarUrl())) {
            var avatarUrl = request.getAvatarUrl().trim();
            validateHttpsUrl(avatarUrl);
            request.setAvatarUrl(avatarUrl);
        } else {
            request.setAvatarUrl(null);
        }
        return request;
    }

    private Mono<WxAppConfigContext> resolveWxAppConfigContext() {
        return settingFetcher.get(WX_APP_CONFIG_GROUP)
            .switchIfEmpty(Mono.just(emptyNode()))
            .flatMap(wxConfig -> settingFetcher.get(APP_CONFIG_GROUP)
                .switchIfEmpty(Mono.just(emptyNode()))
                .map(appConfig -> WxAppConfigContext.merge(wxConfig, appConfig)));
    }

    private Mono<WxAppConfigContext> loadLoginConfig() {
        return resolveWxAppConfigContext()
            .flatMap(context -> {
                if (!StringUtils.hasText(context.appId()) || !StringUtils.hasText(context.appSecret())) {
                    return Mono.error(new WxAuthException(
                        "微信小程序 AppID 或 AppSecret 未配置，请在「微信小程序」或「应用配置」中填写"));
                }
                if (!context.loginEnabled()) {
                    return Mono.error(new WxAuthException("站长尚未开启小程序登录"));
                }
                return Mono.just(context);
            });
    }

    private WxAppConfigDTO toPublicConfig(WxAppConfigContext context) {
        if (!StringUtils.hasText(context.appId())) {
            throw new WxAuthException("微信小程序 AppID 未配置");
        }
        return WxAppConfigDTO.builder()
            .appId(context.appId())
            .loginEnabled(context.loginEnabled())
            .profileModifyLimit(context.profileModifyLimit())
            .haloBindEnabled(context.haloBindEnabled())
            .build();
    }

    private Mono<String> uploadAvatarFile(FilePart filePart) {
        return loadAttachmentUploadConfig()
            .flatMap(config -> attachmentService.upload(
                    "admin", config.policyName(), config.fileGroupName(), filePart, null)
                .flatMap(attachment -> attachmentService.getPermalink(attachment)
                    .map(uri -> externalLinkProcessor.processLink(uri.toString()))));
    }

    private Mono<AttachmentUploadConfig> loadAttachmentUploadConfig() {
        return settingFetcher.get(APP_CONFIG_GROUP)
            .switchIfEmpty(Mono.just(emptyNode()))
            .map(config -> {
                var appInfo = config.path("appInfo");
                var policyName = textValue(appInfo, "policyName");
                if (!StringUtils.hasText(policyName)) {
                    policyName = "default-policy";
                }
                return new AttachmentUploadConfig(policyName, textValue(appInfo, "fileGroupName"));
            });
    }

    private static JsonNode emptyNode() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private Mono<WechatSession> exchangeCode(WxAppConfigContext config, String code) {
        var uri = UriComponentsBuilder.fromUriString(JS_CODE_TO_SESSION_URL)
            .queryParam("appid", config.appId())
            .queryParam("secret", config.appSecret())
            .queryParam("js_code", code)
            .queryParam("grant_type", "authorization_code")
            .build()
            .toUri();
        return WEB_CLIENT.get()
            .uri(uri)
            .retrieve()
            .bodyToMono(String.class)
            .switchIfEmpty(Mono.error(new WxAuthException("微信授权失败：微信接口无响应")))
            .flatMap(this::parseWechatSessionResponse)
            .onErrorMap(this::mapWechatExchangeError);
    }

    private Mono<WechatSession> parseWechatSessionResponse(String raw) {
        return Mono.fromCallable(() -> {
            JsonNode body = OBJECT_MAPPER.readTree(raw);
            if (body.has("errcode") && !body.get("errcode").isNull()
                && body.get("errcode").asInt() != 0) {
                var errmsg = body.path("errmsg").asText("unknown");
                throw new WxAuthException("微信授权失败：" + errmsg);
            }
            var openid = body.path("openid").asText(null);
            if (!StringUtils.hasText(openid)) {
                throw new WxAuthException("微信授权失败：invalid code");
            }
            var sessionKey = body.path("session_key").asText("");
            var unionidNode = body.get("unionid");
            var unionid = unionidNode == null || unionidNode.isNull()
                ? null
                : unionidNode.asText();
            return new WechatSession(openid, sessionKey, unionid);
        });
    }

    private Throwable mapWechatExchangeError(Throwable ex) {
        if (ex instanceof WxAuthException wxAuthException) {
            return wxAuthException;
        }
        if (ex instanceof JsonProcessingException) {
            log.warn("WeChat jscode2session response parse failed", ex);
            return new WxAuthException("微信授权失败：响应解析异常");
        }
        if (ex instanceof WebClientRequestException) {
            log.warn("WeChat jscode2session request failed", ex);
            return new WxAuthException("微信授权失败：无法连接微信服务器，请检查 Halo 服务器出站网络");
        }
        if (ex instanceof WebClientResponseException responseEx) {
            log.warn("WeChat jscode2session HTTP error: {}", responseEx.getStatusCode(), ex);
            return new WxAuthException("微信授权失败：HTTP " + responseEx.getStatusCode().value());
        }
        log.warn("WeChat jscode2session failed", ex);
        var message = ex.getMessage();
        if (StringUtils.hasText(message)) {
            return new WxAuthException("微信授权失败：" + message);
        }
        return new WxAuthException("微信授权失败，请稍后重试");
    }

    private Mono<WxUserDTO> handleLogin(WechatSession session, WxLoginRequest loginRequest) {
        var completingProfile = StringUtils.hasText(loginRequest.getNickname());
        return wxUserService.fetchByOpenid(session.openid())
            .flatMap(existing -> handleExistingUser(existing, session, loginRequest, completingProfile))
            .switchIfEmpty(Mono.defer(() -> handleNewUser(session, loginRequest, completingProfile)));
    }

    private Mono<WxUserDTO> handleExistingUser(WxUser existing, WechatSession session,
        WxLoginRequest loginRequest, boolean completingProfile) {
        syncSession(existing, session);
        if (completingProfile) {
            return saveCompletedProfile(existing, loginRequest, false);
        }
        return wxUserService.update(existing)
            .map(wxUser -> isProfileComplete(wxUser)
                ? toCompleteUserDTO(wxUser)
                : toIncompleteUserDTO(wxUser));
    }

    private Mono<WxUserDTO> handleNewUser(WechatSession session, WxLoginRequest loginRequest,
        boolean completingProfile) {
        var wxUser = newStubUser(session);
        if (completingProfile) {
            return saveCompletedProfile(wxUser, loginRequest, true);
        }
        return wxUserService.create(wxUser)
            .map(this::toIncompleteUserDTO);
    }

    private Mono<WxUserDTO> saveCompletedProfile(WxUser wxUser, WxLoginRequest loginRequest,
        boolean isNew) {
        wxUser.setNickname(loginRequest.getNickname());
        if (StringUtils.hasText(loginRequest.getAvatarUrl())) {
            wxUser.setAvatarUrl(processLink(loginRequest.getAvatarUrl()));
        }
        wxUser.setProfileComplete(true);
        if (wxUser.getProfileModifyCount() == null) {
            wxUser.setProfileModifyCount(0);
        }
        var saveMono = isNew ? wxUserService.create(wxUser) : wxUserService.update(wxUser);
        return saveMono.map(this::toCompleteUserDTO);
    }

    private WxUser newStubUser(WechatSession session) {
        var wxUser = new WxUser();
        wxUser.setId(UUID.randomUUID().toString());
        wxUser.setOpenid(session.openid());
        wxUser.setSessionKey(session.sessionKey());
        wxUser.setUnionid(session.unionid());
        wxUser.setProfileComplete(false);
        wxUser.setProfileModifyCount(0);
        return wxUser;
    }

    private void syncSession(WxUser wxUser, WechatSession session) {
        wxUser.setSessionKey(session.sessionKey());
        if (session.unionid() != null) {
            wxUser.setUnionid(session.unionid());
        }
    }

    private boolean isProfileComplete(WxUser wxUser) {
        if (Boolean.TRUE.equals(wxUser.getProfileComplete())) {
            return true;
        }
        return StringUtils.hasText(wxUser.getNickname());
    }

    private WxUserDTO toCompleteUserDTO(WxUser wxUser) {
        return WxUserDTO.builder()
            .id(wxUser.getId())
            .openid(wxUser.getOpenid())
            .unionid(wxUser.getUnionid())
            .nickname(wxUser.getNickname())
            .avatarUrl(processLink(wxUser.getAvatarUrl()))
            .haloUserName(wxUser.getHaloUserName())
            .haloBindStatus(resolveHaloBindStatus(wxUser))
            .profileComplete(true)
            .profileModifyCount(resolveModifyCount(wxUser))
            .build();
    }

    private WxUserDTO toIncompleteUserDTO(WxUser wxUser) {
        return WxUserDTO.builder()
            .id(wxUser.getId())
            .openid(wxUser.getOpenid())
            .haloUserName(wxUser.getHaloUserName())
            .haloBindStatus(resolveHaloBindStatus(wxUser))
            .profileComplete(false)
            .profileModifyCount(resolveModifyCount(wxUser))
            .build();
    }

    private static int resolveModifyCount(WxUser wxUser) {
        return wxUser.getProfileModifyCount() != null ? wxUser.getProfileModifyCount() : 0;
    }

    private Mono<WxUser> syncBoundHaloProfile(WxUser wxUser) {
        if (!StringUtils.hasText(wxUser.getHaloUserName())) {
            return Mono.just(wxUser);
        }
        return haloUserSyncService.updateHaloUserProfile(
                wxUser.getHaloUserName(),
                resolveDisplayName(wxUser),
                processLink(wxUser.getAvatarUrl()))
            .thenReturn(wxUser);
    }

    private Mono<WxUser> requireUnboundWxUser(String openid) {
        return wxUserService.fetchByOpenid(openid)
            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
            .flatMap(wxUser -> StringUtils.hasText(wxUser.getHaloUserName())
                ? Mono.error(new WxAuthException("该微信账号已绑定 Halo 用户"))
                : Mono.just(wxUser));
    }

    private Mono<WxUserDTO> persistHaloBinding(WxUser wxUser, HaloBindResultDTO result) {
        wxUser.setHaloUserName(result.getHaloUserName());
        return wxUserService.update(wxUser)
            .map(saved -> toUserDTOWithBindStatus(saved, result.getHaloBindStatus()));
    }

    private WxUserDTO toUserDTOWithBindStatus(WxUser wxUser, String haloBindStatus) {
        if (isProfileComplete(wxUser)) {
            return WxUserDTO.builder()
                .id(wxUser.getId())
                .openid(wxUser.getOpenid())
                .unionid(wxUser.getUnionid())
                .nickname(wxUser.getNickname())
                .avatarUrl(processLink(wxUser.getAvatarUrl()))
                .haloUserName(wxUser.getHaloUserName())
                .haloBindStatus(haloBindStatus)
                .profileComplete(true)
                .profileModifyCount(resolveModifyCount(wxUser))
                .build();
        }
        return WxUserDTO.builder()
            .id(wxUser.getId())
            .openid(wxUser.getOpenid())
            .haloUserName(wxUser.getHaloUserName())
            .haloBindStatus(haloBindStatus)
            .profileComplete(false)
            .profileModifyCount(resolveModifyCount(wxUser))
            .build();
    }

    private Mono<WxUserDTO> persistHaloUnbinding(WxUser wxUser) {
        wxUser.setHaloUserName(null);
        return wxUserService.update(wxUser)
            .map(this::toUnboundUserDTO);
    }

    private WxUserDTO toUnboundUserDTO(WxUser wxUser) {
        if (isProfileComplete(wxUser)) {
            return WxUserDTO.builder()
                .id(wxUser.getId())
                .openid(wxUser.getOpenid())
                .unionid(wxUser.getUnionid())
                .nickname(wxUser.getNickname())
                .avatarUrl(processLink(wxUser.getAvatarUrl()))
                .haloUserName("")
                .haloBindStatus(Constants.HALO_BIND_STATUS_UNBOUND)
                .profileComplete(true)
                .profileModifyCount(resolveModifyCount(wxUser))
                .build();
        }
        return WxUserDTO.builder()
            .id(wxUser.getId())
            .openid(wxUser.getOpenid())
            .haloUserName("")
            .haloBindStatus(Constants.HALO_BIND_STATUS_UNBOUND)
            .profileComplete(false)
            .profileModifyCount(resolveModifyCount(wxUser))
            .build();
    }

    private String resolveDetailedHaloBindStatus(WxUser wxUser) {
        if (!StringUtils.hasText(wxUser.getHaloUserName())) {
            return Constants.HALO_BIND_STATUS_UNBOUND;
        }
        if (WxAutoUsernameGenerator.isAutoCreatedFormat(wxUser.getHaloUserName())) {
            return Constants.HALO_BIND_STATUS_CREATED;
        }
        return Constants.HALO_BIND_STATUS_BOUND;
    }

    private String resolveHaloBindStatus(WxUser wxUser) {
        return StringUtils.hasText(wxUser.getHaloUserName())
            ? Constants.HALO_BIND_STATUS_BOUND
            : Constants.HALO_BIND_STATUS_UNBOUND;
    }

    private String resolveDisplayName(WxUser wxUser) {
        if (wxUser != null && StringUtils.hasText(wxUser.getNickname())) {
            return wxUser.getNickname().trim();
        }
        return Constants.DEFAULT_WX_DISPLAY_NAME;
    }

    private static void assertProfileModifyAllowed(int profileModifyLimit, WxUser wxUser) {
        if (profileModifyLimit <= 0) {
            return;
        }
        if (resolveModifyCount(wxUser) >= profileModifyLimit) {
            throw new WxAuthException("资料修改次数已达上限");
        }
    }

    private WxUserDTO toUserDTO(WxUser wxUser) {
        if (isProfileComplete(wxUser)) {
            return toCompleteUserDTO(wxUser);
        }
        return toIncompleteUserDTO(wxUser);
    }

    private String processLink(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        return externalLinkProcessor.processLink(url);
    }

    private record WxAppConfigContext(String appId, String appSecret, boolean loginEnabled,
        int profileModifyLimit, boolean haloBindEnabled) {
        static WxAppConfigContext merge(JsonNode wxConfig, JsonNode appConfig) {
            var appInfo = appConfig.path("appInfo");
            var limitNode = wxConfig.get("profileModifyLimit");
            var profileModifyLimit = limitNode == null || limitNode.isNull()
                ? 0
                : Math.max(0, limitNode.asInt(0));
            return new WxAppConfigContext(
                firstNonEmpty(textValue(wxConfig, "appId"), textValue(appInfo, "appId")),
                firstNonEmpty(textValue(wxConfig, "appSecret"), textValue(appInfo, "appSecret")),
                wxConfig.has("loginEnabled")
                    ? wxConfig.path("loginEnabled").asBoolean(true)
                    : true,
                profileModifyLimit,
                wxConfig.has("haloBindEnabled")
                    ? wxConfig.path("haloBindEnabled").asBoolean(false)
                    : false
            );
        }
    }

    private record AttachmentUploadConfig(String policyName, String fileGroupName) {
    }

    private record WechatSession(String openid, String sessionKey, String unionid) {
    }

    private static String textValue(JsonNode config, String field) {
        var node = config.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static void validateHttpsUrl(String avatarUrl) {
        try {
            var uri = new URI(avatarUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw new WxAuthException("avatarUrl 须为 HTTPS 合法 URL");
            }
        } catch (URISyntaxException ex) {
            throw new WxAuthException("avatarUrl 须为 HTTPS 合法 URL");
        }
    }
}
