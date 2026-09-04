package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.HaloBindCodePayload;
import pro.uhalo.uni.dto.HaloBindResultDTO;
import pro.uhalo.uni.dto.SendHaloBindCodeResponse;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.BindCodeStorageRouter;
import pro.uhalo.uni.services.HaloBindCodeStore;
import pro.uhalo.uni.services.HaloBindMailService;
import pro.uhalo.uni.services.HaloUserSyncService;
import pro.uhalo.uni.utils.EmailUtils;
import pro.uhalo.uni.utils.EndpointErrorResolver;
import pro.uhalo.uni.utils.HaloAccountNormalizer;
import pro.uhalo.uni.utils.WxAutoUsernameGenerator;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.exception.ExtensionException;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class HaloUserSyncServiceImpl implements HaloUserSyncService {

    private static final String BASIC_CONFIG_GROUP = "basicConfig";
    private static final String CONSOLE_USERS_PATH = "/apis/api.console.halo.run/v1alpha1/users";
    private static final String CORE_USERS_PATH = "/apis/api.halo.run/v1alpha1/users";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final UserService userService;
    private final ReactiveExtensionClient extensionClient;
    private final HaloBindCodeStore bindCodeStore;
    private final HaloBindMailService bindMailService;
    private final BindCodeStorageRouter bindCodeStorageRouter;
    private final WebClient webClient;

    public HaloUserSyncServiceImpl(ReactiveSettingFetcher settingFetcher,
                                   UserService userService,
                                   ReactiveExtensionClient extensionClient,
                                   HaloBindCodeStore bindCodeStore,
                                   HaloBindMailService bindMailService,
                                   BindCodeStorageRouter bindCodeStorageRouter,
                                   @Value("${server.port:8090}") int serverPort) {
        this.settingFetcher = settingFetcher;
        this.userService = userService;
        this.extensionClient = extensionClient;
        this.bindCodeStore = bindCodeStore;
        this.bindMailService = bindMailService;
        this.bindCodeStorageRouter = bindCodeStorageRouter;
        this.webClient = WebClient.builder()
            .baseUrl("http://127.0.0.1:" + serverPort)
            .build();
    }

    @Override
    public Mono<SendHaloBindCodeResponse> sendHaloBindCode(String openid, String account) {
        validateOpenid(openid);
        validateAccount(account);
        var normalizedOpenid = openid.trim();
        var normalizedAccount = HaloAccountNormalizer.normalizeBindAccount(account);
        log.info("sendHaloBindCode start, openid={}, account={}", normalizedOpenid, normalizedAccount);
        return resolveHaloAccountTarget(normalizedAccount)
            .doOnNext(target -> log.info(
                "sendHaloBindCode resolved Halo user, openid={}, account={}, haloUserName={}, email={}",
                normalizedOpenid, normalizedAccount, target.haloUserName(), target.email()))
            .flatMap(target -> {
                if (!EmailUtils.isValidRegistrationEmail(target.email())) {
                    log.warn("sendHaloBindCode rejected, haloUserName={} has no registration email",
                        target.haloUserName());
                    return Mono.error(new WxAuthException("Halo 用户未绑定邮箱"));
                }
                return assertHaloUserAvailableByService(target.haloUserName(), normalizedOpenid)
                    .then(bindCodeStorageRouter.loadSettings().doOnNext(bindCodeStorageRouter::logDiagnostics))
                    .then(bindCodeStore.assertRateLimitAllowed(normalizedOpenid, normalizedAccount))
                    .then(Mono.fromCallable(this::generateCode))
                    .flatMap(code -> bindCodeStore.saveCode(
                            normalizedOpenid,
                            normalizedAccount,
                            code,
                            new HaloBindCodePayload(target.haloUserName(), target.email()))
                        .then(bindMailService.sendBindCode(
                            target.haloUserName(), target.email(), code))
                        .then(bindCodeStore.recordRateLimit(normalizedOpenid, normalizedAccount))
                        .thenReturn(SendHaloBindCodeResponse.builder()
                            .success(true)
                            .maskedEmail(EmailUtils.maskEmail(target.email()))
                            .expireSeconds(Constants.HALO_BIND_CODE_EXPIRE_SECONDS)
                            .build()));
            })
            .doOnSuccess(response -> log.info(
                "sendHaloBindCode success, openid={}, account={}, maskedEmail={}",
                normalizedOpenid, normalizedAccount, response.getMaskedEmail()))
            .doOnError(error -> log.error(
                "sendHaloBindCode failed, openid={}, account={}",
                normalizedOpenid, normalizedAccount, error))
            .onErrorMap(error -> {
                var reason = EndpointErrorResolver.findWxAuthReason(error);
                if (StringUtils.hasText(reason)) {
                    return new WxAuthException(reason);
                }
                return new WxAuthException(EndpointErrorResolver.resolveSendHaloBindCodeError(error));
            });
    }

    @Override
    public Mono<HaloBindResultDTO> bindHaloAccountByCode(String openid, String account, String code) {
        validateOpenid(openid);
        validateAccount(account);
        HaloAccountNormalizer.validateBindCode(code);
        var normalizedOpenid = openid.trim();
        var normalizedAccount = HaloAccountNormalizer.normalizeBindAccount(account);
        var normalizedCode = code.trim();
        log.info("bindHaloAccountByCode start, openid={}, account={}", normalizedOpenid, normalizedAccount);
        return bindCodeStore.verifyAndConsume(normalizedOpenid, normalizedAccount, normalizedCode)
            .flatMap(payload -> resolveHaloAccountTarget(normalizedAccount)
                .flatMap(target -> {
                    if (!target.haloUserName().equals(payload.haloUserName())) {
                        log.warn("bindHaloAccountByCode account mismatch, account={}, payloadUser={}, resolvedUser={}",
                            normalizedAccount, payload.haloUserName(), target.haloUserName());
                        return Mono.error(new WxAuthException("Halo 账号不存在"));
                    }
                    return assertHaloUserAvailableByService(target.haloUserName(), normalizedOpenid)
                        .then(bindOpenidAnnotation(target.haloUserName(), normalizedOpenid))
                        .thenReturn(HaloBindResultDTO.builder()
                            .haloUserName(target.haloUserName())
                            .haloBindStatus(Constants.HALO_BIND_STATUS_BOUND)
                            .build());
                }))
            .doOnSuccess(result -> log.info(
                "bindHaloAccountByCode success, openid={}, account={}, haloUserName={}",
                normalizedOpenid, normalizedAccount, result.getHaloUserName()))
            .doOnError(error -> log.error(
                "bindHaloAccountByCode failed, openid={}, account={}",
                normalizedOpenid, normalizedAccount, error))
            .onErrorMap(error -> {
                var reason = EndpointErrorResolver.findWxAuthReason(error);
                if (StringUtils.hasText(reason)) {
                    return new WxAuthException(reason);
                }
                return new WxAuthException(EndpointErrorResolver.resolveBindHaloAccountByCodeError(error));
            });
    }

    @Override
    public Mono<HaloBindResultDTO> createHaloAccount(String openid, String displayName, String avatarUrl) {
        validateOpenid(openid);
        var normalizedOpenid = openid.trim();
        var email = Constants.buildMiniCommentEmail(normalizedOpenid);
        return assertOpenidNotBound(normalizedOpenid)
            .then(loadPersonalToken())
            .flatMap(token -> resolveAvailableWxAutoUsername(normalizedOpenid)
                .flatMap(haloUserName -> assertHaloEmailUnavailable(email, normalizedOpenid, token)
                    .then(createHaloUser(normalizedOpenid, haloUserName, email, displayName, avatarUrl, token))
                    .map(createdName -> HaloBindResultDTO.builder()
                        .haloUserName(createdName)
                        .haloBindStatus(Constants.HALO_BIND_STATUS_CREATED)
                        .build())));
    }

    private Mono<String> resolveAvailableWxAutoUsername(String openid) {
        return reactor.core.publisher.Flux.range(0, WxAutoUsernameGenerator.MAX_ATTEMPTS)
            .concatMap(attempt -> {
                var candidate = WxAutoUsernameGenerator.candidate(openid, attempt);
                return isHaloUsernameAvailable(candidate)
                    .filter(Boolean::booleanValue)
                    .map(ignored -> candidate);
            })
            .next()
            .switchIfEmpty(Mono.error(new WxAuthException("无法生成可用的 Halo 用户名，请稍后重试")));
    }

    private Mono<Boolean> isHaloUsernameAvailable(String haloUserName) {
        return extensionClient.fetch(User.class, haloUserName)
            .hasElement()
            .map(exists -> !exists);
    }

    @Override
    public Mono<Void> unbindOpenidAnnotation(String haloUserName, String openid) {
        return extensionClient.fetch(User.class, haloUserName)
            .switchIfEmpty(Mono.error(new WxAuthException("Halo 账号不存在")))
            .flatMap(user -> {
                var metadata = user.getMetadata();
                if (metadata == null) {
                    return Mono.error(new WxAuthException("Halo 用户元数据缺失"));
                }
                var annotations = metadata.getAnnotations();
                if (annotations != null) {
                    var boundOpenid = annotations.get(Constants.UNIHALO_OPENID_ANNOTATION);
                    if (StringUtils.hasText(boundOpenid) && !boundOpenid.equals(openid)) {
                        return Mono.error(new WxAuthException("Halo 账号关联信息与当前微信用户不一致"));
                    }
                }
                var updatedAnnotations = annotations != null
                    ? new HashMap<>(annotations)
                    : new HashMap<String, String>();
                updatedAnnotations.remove(Constants.UNIHALO_OPENID_ANNOTATION);
                metadata.setAnnotations(updatedAnnotations.isEmpty() ? null : updatedAnnotations);
                return extensionClient.update(user);
            })
            .then()
            .onErrorMap(this::mapUnbindOpenidAnnotationError);
    }

    @Override
    public Mono<Void> updateHaloUserProfile(String haloUserName, String displayName, String avatarUrl) {
        if (!StringUtils.hasText(haloUserName)) {
            return Mono.empty();
        }
        return loadPersonalToken()
            .flatMap(token -> fetchCoreUser(haloUserName.trim(), token)
                .flatMap(user -> {
                    var updated = user.deepCopy();
                    var spec = updated.with("spec");
                    if (StringUtils.hasText(displayName)) {
                        ((ObjectNode) spec).put("displayName", displayName.trim());
                    }
                    if (avatarUrl != null) {
                        if (StringUtils.hasText(avatarUrl)) {
                            ((ObjectNode) spec).put("avatar", avatarUrl.trim());
                        } else {
                            ((ObjectNode) spec).putNull("avatar");
                        }
                    }
                    return webClient.put()
                        .uri(CORE_USERS_PATH + "/{name}", haloUserName.trim())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated)
                        .retrieve()
                        .bodyToMono(Void.class);
                }))
            .onErrorResume(ex -> {
                log.warn("Failed to sync Halo user profile for {}: {}", haloUserName, ex.getMessage());
                return Mono.empty();
            });
    }

    private Mono<HaloAccountTarget> resolveHaloAccountTarget(String account) {
        var normalizedAccount = HaloAccountNormalizer.normalizeBindAccount(account);
        if (normalizedAccount.contains("@")) {
            return userService.listByEmail(normalizedAccount)
                .next()
                .map(this::toAccountTarget)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("sendHaloBindCode Halo user not found by email: {}", account);
                    return Mono.error(new WxAuthException("Halo 账号不存在"));
                }));
        }
        return userService.getUser(normalizedAccount)
            .map(this::toAccountTarget)
            .doOnError(error -> log.error("sendHaloBindCode failed to load Halo user by username: {}",
                account, error))
            .onErrorMap(ex -> ex instanceof WxAuthException ? ex
                : new WxAuthException("Halo 账号不存在"));
    }

    private Mono<Void> assertHaloUserAvailableByService(String haloUserName, String openid) {
        return userService.getUser(haloUserName)
            .flatMap(user -> {
                var annotations = user.getMetadata().getAnnotations();
                if (annotations == null) {
                    return Mono.<Void>empty();
                }
                var boundOpenid = annotations.get(Constants.UNIHALO_OPENID_ANNOTATION);
                if (StringUtils.hasText(boundOpenid) && !boundOpenid.equals(openid)) {
                    return Mono.error(new WxAuthException("该 Halo 账号已绑定其他微信用户"));
                }
                return Mono.empty();
            })
            .onErrorMap(ex -> ex instanceof WxAuthException ? ex
                : new WxAuthException("Halo 账号不存在"));
    }

    private HaloAccountTarget toAccountTarget(User user) {
        return new HaloAccountTarget(
            user.getMetadata().getName(),
            user.getSpec().getEmail()
        );
    }

    private String generateCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private Mono<Void> assertOpenidNotBound(String openid) {
        return loadPersonalToken()
            .flatMap(token -> findUserNameByOpenidAnnotation(openid, token)
                .flatMap(existing -> Mono.<Void>error(
                    new WxAuthException("该微信账号已绑定 Halo 用户"))))
            .then();
    }

    private Mono<Void> assertHaloEmailUnavailable(String email, String openid, String token) {
        return findUserNameByEmail(email, token)
            .flatMap(name -> fetchCoreUser(name, token)
                .flatMap(user -> failIfHaloTargetOccupied(user, openid, "Halo 邮箱已被占用")))
            .then();
    }

    private Mono<Void> failIfHaloTargetOccupied(JsonNode user, String openid, String occupiedMessage) {
        var boundOpenid = extractOpenidAnnotation(user);
        if (StringUtils.hasText(boundOpenid) && boundOpenid.equals(openid)) {
            return Mono.error(new WxAuthException("该微信账号已绑定 Halo 用户"));
        }
        return Mono.error(new WxAuthException(occupiedMessage));
    }

    private Mono<Void> assertHaloUserAvailable(String haloUserName, String openid, String token) {
        return fetchCoreUser(haloUserName, token)
            .flatMap(user -> {
                var boundOpenid = extractOpenidAnnotation(user);
                if (StringUtils.hasText(boundOpenid) && !boundOpenid.equals(openid)) {
                    return Mono.error(new WxAuthException("该 Halo 账号已绑定其他微信用户"));
                }
                return Mono.<Void>empty();
            });
    }

    private Mono<Void> bindOpenidAnnotation(String haloUserName, String openid) {
        return extensionClient.fetch(User.class, haloUserName)
            .switchIfEmpty(Mono.error(new WxAuthException("Halo 账号不存在")))
            .flatMap(user -> {
                var metadata = user.getMetadata();
                if (metadata == null) {
                    return Mono.error(new WxAuthException("Halo 用户元数据缺失"));
                }
                var annotations = metadata.getAnnotations();
                var updatedAnnotations = annotations != null
                    ? new HashMap<>(annotations)
                    : new HashMap<String, String>();
                updatedAnnotations.put(Constants.UNIHALO_OPENID_ANNOTATION, openid);
                metadata.setAnnotations(updatedAnnotations);
                return extensionClient.update(user);
            })
            .then()
            .onErrorMap(this::mapBindOpenidAnnotationError);
    }

    private WxAuthException mapBindOpenidAnnotationError(Throwable ex) {
        if (ex instanceof WxAuthException wxAuthException) {
            return wxAuthException;
        }
        if (ex instanceof ExtensionException extensionException) {
            var reason = extensionException.getReason();
            if (StringUtils.hasText(reason)) {
                if (reason.contains("404") || reason.toLowerCase().contains("not found")) {
                    return new WxAuthException("Halo 账号不存在");
                }
                return new WxAuthException("绑定关系写入失败：" + reason);
            }
            return new WxAuthException("绑定关系写入失败，请稍后重试");
        }
        return new WxAuthException("绑定关系写入失败：" + resolveErrorMessage(ex));
    }

    private WxAuthException mapUnbindOpenidAnnotationError(Throwable ex) {
        if (ex instanceof WxAuthException wxAuthException) {
            return wxAuthException;
        }
        if (ex instanceof ExtensionException extensionException) {
            var reason = extensionException.getReason();
            if (StringUtils.hasText(reason)) {
                if (reason.contains("404") || reason.toLowerCase().contains("not found")) {
                    return new WxAuthException("Halo 账号不存在");
                }
                return new WxAuthException("解绑关系写入失败：" + reason);
            }
            return new WxAuthException("解绑关系写入失败，请稍后重试");
        }
        return new WxAuthException("解绑关系写入失败：" + resolveErrorMessage(ex));
    }

    private Mono<String> createHaloUser(String openid, String haloUserName, String email,
                                        String displayName, String avatarUrl, String token) {
        var requestBody = buildCreateUserRequest(openid, haloUserName, email, displayName, avatarUrl);
        return webClient.post()
            .uri(CONSOLE_USERS_PATH)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .mapNotNull(this::extractUserName)
            .switchIfEmpty(Mono.just(haloUserName))
            .onErrorResume(WebClientResponseException.Conflict.class,
                ex -> Mono.error(new WxAuthException("Halo 用户名或邮箱已被占用")))
            .onErrorMap(ex -> ex instanceof WxAuthException ? ex
                : new WxAuthException("创建 Halo 用户失败：" + resolveErrorMessage(ex)));
    }

    private Mono<String> fetchConsoleUser(String haloUserName, String token) {
        return webClient.get()
            .uri(CONSOLE_USERS_PATH + "/{name}", haloUserName)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .mapNotNull(this::extractUserName)
            .onErrorResume(WebClientResponseException.NotFound.class, ex -> Mono.empty())
            .onErrorResume(ex -> {
                log.warn("Failed to fetch Halo user {}: {}", haloUserName, ex.getMessage());
                return Mono.empty();
            });
    }

    private Mono<String> findUserNameByEmail(String email, String token) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(CORE_USERS_PATH)
                .queryParam("fieldSelector", "spec.email=" + email)
                .build())
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .mapNotNull(this::extractFirstUserName)
            .onErrorResume(ex -> Mono.empty());
    }

    private Mono<String> findUserNameByOpenidAnnotation(String openid, String token) {
        var annotationKey = "metadata.annotations." + Constants.UNIHALO_OPENID_ANNOTATION;
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(CORE_USERS_PATH)
                .queryParam("fieldSelector", annotationKey + "=" + openid)
                .build())
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .mapNotNull(this::extractFirstUserName)
            .onErrorResume(ex -> Mono.empty());
    }

    private Mono<JsonNode> fetchCoreUser(String haloUserName, String token) {
        return webClient.get()
            .uri(CORE_USERS_PATH + "/{name}", haloUserName)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class);
    }

    private Map<String, Object> buildCreateUserRequest(String openid, String haloUserName, String email,
                                                       String displayName, String avatarUrl) {
        var annotations = new HashMap<String, String>();
        annotations.put(Constants.UNIHALO_OPENID_ANNOTATION, openid);
        var request = new HashMap<String, Object>();
        request.put("name", haloUserName);
        request.put("email", email);
        request.put("displayName", StringUtils.hasText(displayName)
            ? displayName.trim()
            : Constants.DEFAULT_WX_DISPLAY_NAME);
        request.put("avatar", StringUtils.hasText(avatarUrl) ? avatarUrl.trim() : "");
        request.put("phone", "");
        request.put("password", UUID.randomUUID().toString().replace("-", "") + "Aa1!");
        request.put("bio", "");
        request.put("annotations", annotations);
        request.put("roles", java.util.Set.of());
        return request;
    }

    private Mono<String> loadPersonalToken() {
        return settingFetcher.get(BASIC_CONFIG_GROUP)
            .switchIfEmpty(Mono.error(new WxAuthException(
                "未配置 basicConfig.tokenConfig.personalToken，请在插件基本配置中填写个人令牌")))
            .map(config -> textValue(config.path("tokenConfig"), "personalToken"))
            .flatMap(token -> StringUtils.hasText(token)
                ? Mono.just(token.trim())
                : Mono.error(new WxAuthException(
                    "未配置 basicConfig.tokenConfig.personalToken，请在插件基本配置中填写个人令牌")));
    }

    private static void validateOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new WxAuthException("缺少 Wechat-Session-Id");
        }
    }

    private static void validateAccount(String account) {
        if (!StringUtils.hasText(account)) {
            throw new WxAuthException("account 不能为空");
        }
    }

    private String extractOpenidAnnotation(JsonNode user) {
        return textValue(user.path("metadata").path("annotations"), Constants.UNIHALO_OPENID_ANNOTATION);
    }

    private String extractUserName(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        if (body.has("user")) {
            return textValue(body.path("user").path("metadata"), "name");
        }
        return textValue(body.path("metadata"), "name");
    }

    private String extractFirstUserName(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        var items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        return textValue(items.get(0).path("metadata"), "name");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String resolveErrorMessage(Throwable ex) {
        if (ex instanceof WebClientResponseException responseEx) {
            var body = responseEx.getResponseBodyAsString();
            if (StringUtils.hasText(body)) {
                return body;
            }
            return "HTTP " + responseEx.getStatusCode().value();
        }
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "未知错误";
    }

    private record HaloAccountTarget(String haloUserName, String email) {
    }
}
