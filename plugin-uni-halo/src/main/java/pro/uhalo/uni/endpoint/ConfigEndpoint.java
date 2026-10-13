package pro.uhalo.uni.endpoint;

import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import pro.uhalo.uni.constants.Constants;
import org.springframework.http.codec.multipart.FilePart;
import pro.uhalo.uni.dto.BindHaloAccountByCodeRequest;
import pro.uhalo.uni.dto.RecordUserActivityRequest;
import pro.uhalo.uni.dto.SendHaloBindCodeRequest;
import pro.uhalo.uni.dto.UpdateProfileRequest;
import org.springframework.http.MediaType;
import pro.uhalo.uni.utils.EndpointErrorResolver;
import pro.uhalo.uni.utils.WxRequestParser;
import pro.uhalo.uni.enums.UserContentType;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.UniHaloService;
import pro.uhalo.uni.services.UserActivityService;
import pro.uhalo.uni.services.WxAuthService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.exception.ExtensionException;

import java.util.Map;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Component
@Slf4j
public class ConfigEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final UniHaloService uniHaloService;
    private final WxAuthService wxAuthService;
    private final UserActivityService userActivityService;
    private final WxUserService wxUserService;

    public ConfigEndpoint(ReactiveExtensionClient client,
                          UniHaloService uniHaloService,
                          WxAuthService wxAuthService,
                          UserActivityService userActivityService,
                          WxUserService wxUserService) {
        this.client = client;
        this.uniHaloService = uniHaloService;
        this.wxAuthService = wxAuthService;
        this.userActivityService = userActivityService;
        this.wxUserService = wxUserService;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String ApiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Config";

        return SpringdocRouteBuilder.route()
            .GET(Constants.END_POINT_API_BASE_PATH + "/getConfigs", this::getAllConfigs,
                builder -> {
                    builder.operationId("GetConfigs")
                        .description("All config.")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/getConfigs/{groupName}",
                this::getConfigsByName, builder -> {
                    builder.operationId("GetConfigsByGroupName")
                        .description("Detail config.")
                        .tag(ApiTag)
                        .parameter(parameterBuilder()
                            .name("groupName")
                            .in(ParameterIn.PATH)
                            .description("Config group name")
                            .required(true)
                            .implementation(String.class)
                        );
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/getWxAppConfig", this::getWxAppConfig,
                builder -> {
                    builder.operationId("GetWxAppConfig")
                        .description("Get public WeChat mini program config.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/login", this::wxLogin,
                builder -> {
                    builder.operationId("WxLoginFallback")
                        .description("WeChat mini program login (fallback path).")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/uploadWxAvatar", this::uploadWxAvatar,
                builder -> {
                    builder.operationId("UploadWxAvatar")
                        .description("Upload WeChat mini program avatar for chooseAvatar.")
                        .tag(ApiTag);
                })
            .PUT(Constants.END_POINT_API_BASE_PATH + "/updateProfile", this::updateProfile,
                builder -> {
                    builder.operationId("UpdateProfile")
                        .description("Update WeChat user profile.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/sendHaloBindCode", this::sendHaloBindCode,
                builder -> {
                    builder.operationId("SendHaloBindCode")
                        .description("Send Halo account bind verification code to registered email.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/bindHaloAccountByCode", this::bindHaloAccountByCode,
                builder -> {
                    builder.operationId("BindHaloAccountByCode")
                        .description("Bind an existing Halo account using email verification code.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/createHaloAccount", this::createHaloAccount,
                builder -> {
                    builder.operationId("CreateHaloAccount")
                        .description("Create a new Halo account for the current WeChat user.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/unbindHaloAccount", this::unbindHaloAccount,
                builder -> {
                    builder.operationId("UnbindHaloAccount")
                        .description("Unbind the current WeChat user from the associated Halo account.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/userActivities", this::recordUserActivity,
                builder -> {
                    builder.operationId("RecordUserActivity")
                        .description("Record WeChat user activity for an article.")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/userActivities", this::listUserActivities,
                builder -> {
                    builder.operationId("ListUserActivities")
                        .description("List WeChat user activities with pagination.")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/userActivities/upvote/status",
                this::getUpvoteStatus,
                builder -> {
                    builder.operationId("GetUpvoteStatus")
                        .description("Check whether the current user has upvoted an article.")
                        .tag(ApiTag)
                        .parameter(parameterBuilder()
                            .name("articleName")
                            .in(ParameterIn.QUERY)
                            .description("Article or moment metadata name")
                            .required(true)
                            .implementation(String.class)
                        )
                        .parameter(parameterBuilder()
                            .name("contentType")
                            .in(ParameterIn.QUERY)
                            .description("Content type: post (default) or moment")
                            .required(false)
                            .implementation(String.class)
                        );
                })
            .DELETE(Constants.END_POINT_API_BASE_PATH + "/userActivities", this::cancelUserActivity,
                builder -> {
                    builder.operationId("CancelUserActivity")
                        .description("Cancel WeChat user activity such as upvote for an article.")
                        .tag(ApiTag);
                })
            .POST(Constants.END_POINT_API_BASE_PATH + "/userActivities/reactions",
                this::recordUserReaction, builder -> {
                    builder.operationId("RecordContentReaction")
                        .description("Record an emoji reaction for a post or moment (switch replaces old).")
                        .tag(ApiTag);
                })
            .DELETE(Constants.END_POINT_API_BASE_PATH + "/userActivities/reactions",
                this::cancelUserReaction, builder -> {
                    builder.operationId("CancelContentReaction")
                        .description("Cancel all my emoji reactions for a post or moment.")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/userActivities/reactions/status",
                this::getContentReactions, builder -> {
                    builder.operationId("GetContentReactions")
                        .description("Get emoji reaction counts and my current reaction.")
                        .tag(ApiTag)
                        .parameter(parameterBuilder()
                            .name("articleName")
                            .in(ParameterIn.QUERY)
                            .description("Article or moment metadata name")
                            .required(true)
                            .implementation(String.class)
                        )
                        .parameter(parameterBuilder()
                            .name("contentType")
                            .in(ParameterIn.QUERY)
                            .description("Content type: post (default) or moment")
                            .required(false)
                            .implementation(String.class)
                        );
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/getQRCodeImg/{postId}", this::getQRCodeImg,
                builder -> {
                    builder.operationId("getQRCodeImg")
                        .description("getQRCodeImg")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/getInviteQRCode/{inviteCode}", this::getInviteQRCode,
                builder -> {
                    builder.operationId("getInviteQRCode")
                        .description("Generate mini program QR code for invite scene (scene=inv:code).")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/getQRCodeInfo/{key}", uniHaloService::getQRCodeInfo,
                builder -> {
                    builder.operationId("getQRCodeInfo")
                        .description("getQRCodeInfo")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/refund-admin", this::getRefundAdmin,
                builder -> {
                    builder.operationId("PayOrderRefundAdminPage")
                        .description("Virtual payment order / manual refund list page (HTML shell, needs personal token).")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/ai-digest-admin", this::getAiDigestAdmin,
                builder -> {
                    builder.operationId("AiDigestAdminPage")
                        .description("AI daily digest management page (HTML shell, needs personal token). "
                            + "Served by the backend so it works even when the console bundle is cached.")
                        .tag(ApiTag);
                })
            .GET(Constants.END_POINT_API_BASE_PATH + "/mp-admin", this::getMpAdmin,
                builder -> {
                    builder.operationId("MpAdminPage")
                        .description("WeChat Official Account publishing page (HTML shell, needs "
                            + "personal token). Served by the backend so it works even when the "
                            + "console bundle is cached.")
                        .tag(ApiTag);
                })
            .build();
    }

    Mono<ServerResponse> getQRCodeImg(ServerRequest request) {
        var postId = request.pathVariable("postId");
        if (!StringUtils.hasText(postId)) {
            return badRequest("postId 不能为空");
        }
        return uniHaloService.resolveQRCodeImageUrl(postId.trim())
            .flatMap(imageUrl -> ServerResponse.ok().bodyValue(imageUrl))
            .switchIfEmpty(Mono.defer(() -> badRequest("二维码生成失败，请稍后重试")))
            .onErrorResume(ExtensionException.class, e -> {
                log.warn("getQRCodeImg extension error, postId={}: {}", postId, e.getMessage(), e);
                return badRequest(resolveErrorMessage(e));
            })
            .onErrorResume(ResponseStatusException.class, e -> {
                log.warn("getQRCodeImg status error, postId={}: {}", postId, e.getMessage(), e);
                return badRequest(resolveErrorMessage(e));
            })
            .onErrorResume(Exception.class, e -> {
                log.warn("getQRCodeImg failed, postId={}: {}", postId, e.getMessage(), e);
                return badRequest(resolveErrorMessage(e));
            });
    }

    Mono<ServerResponse> getInviteQRCode(ServerRequest request) {
        var inviteCode = request.pathVariable("inviteCode");
        if (!StringUtils.hasText(inviteCode)) {
            return badRequest("邀请码不能为空");
        }
        var normalized = inviteCode.trim().toUpperCase();
        return wxUserService.fetchByInviteCode(normalized)
            .hasElement()
            .flatMap(exists -> {
                if (!exists) {
                    return badRequest("邀请码不存在");
                }
                return uniHaloService.resolveInviteQRCodeData(normalized)
                    .flatMap(data -> ServerResponse.ok().bodyValue(data))
                    .switchIfEmpty(Mono.defer(() -> badRequest("二维码生成失败，请稍后重试")))
                    .onErrorResume(ExtensionException.class, e -> {
                        log.warn("getInviteQRCode extension error, code={}: {}", normalized, e.getMessage(), e);
                        return badRequest(resolveErrorMessage(e));
                    })
                    .onErrorResume(ResponseStatusException.class, e -> {
                        log.warn("getInviteQRCode status error, code={}: {}", normalized, e.getMessage(), e);
                        return badRequest(resolveErrorMessage(e));
                    })
                    .onErrorResume(Exception.class, e -> {
                        log.warn("getInviteQRCode failed, code={}: {}", normalized, e.getMessage(), e);
                        return badRequest(resolveErrorMessage(e));
                    });
            });
    }

    Mono<ServerResponse> getAiDigestAdmin(ServerRequest request) {
        return Mono.fromCallable(() -> {
                var resource = new org.springframework.core.io.ClassPathResource("static/ai-digest-admin.html");
                try (var in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            })
            .flatMap(html -> ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(html))
            .onErrorResume(ex -> {
                log.warn("ai-digest-admin page failed: {}", ex.getMessage());
                return badRequest("AI 日报管理页加载失败");
            });
    }

    Mono<ServerResponse> getRefundAdmin(ServerRequest request) {
        return Mono.fromCallable(() -> {
                var resource = new org.springframework.core.io.ClassPathResource("static/refund-admin.html");
                try (var in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            })
            .flatMap(html -> ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(html))
            .onErrorResume(ex -> {
                log.warn("refund-admin page failed: {}", ex.getMessage());
                return badRequest("退款清单页加载失败");
            });
    }

    Mono<ServerResponse> getMpAdmin(ServerRequest request) {
        return Mono.fromCallable(() -> {
                var resource = new org.springframework.core.io.ClassPathResource("static/mp-admin.html");
                try (var in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            })
            .flatMap(html -> ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(html))
            .onErrorResume(ex -> {
                log.warn("mp-admin page failed: {}", ex.getMessage());
                return badRequest("公众号管理页加载失败");
            });
    }

    Mono<ServerResponse> getAllConfigs(ServerRequest request) {
        return uniHaloService.getAppConfigs()
            .flatMap(config -> ServerResponse.ok().bodyValue(config));
    }

    Mono<ServerResponse> getConfigsByName(ServerRequest request) {
        var groupName = request.pathVariable("groupName");
        return uniHaloService.getAppConfigsByGroupName(groupName)
            .flatMap(config -> ServerResponse.ok().bodyValue(config));
    }

    Mono<ServerResponse> getWxAppConfig(ServerRequest request) {
        return wxAuthService.getWxAppConfig()
            .flatMap(config -> ServerResponse.ok().bodyValue(config))
            .onErrorResume(WxAuthException.class, e ->
                ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .bodyValue(Map.of("error", e.getReason())));
    }

    Mono<ServerResponse> wxLogin(ServerRequest request) {
        return WxRequestParser.readRawBody(request)
            .flatMap(WxRequestParser::parseLoginRequest)
            .flatMap(wxAuthService::wxLogin)
            .flatMap(dto -> ServerResponse.ok().bodyValue(dto))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("wxLogin failed: {}", e.getMessage());
                return badRequest("登录失败，请稍后重试");
            });
    }

    Mono<ServerResponse> uploadWxAvatar(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return request.multipartData()
            .flatMap(parts -> {
                var part = parts.getFirst("file");
                if (!(part instanceof FilePart filePart)) {
                    return badRequest("缺少 file 字段");
                }
                return wxAuthService.uploadWxAvatar(openid, filePart)
                    .flatMap(dto -> ServerResponse.ok().bodyValue(dto));
            })
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("uploadWxAvatar failed: {}", e.getMessage());
                return badRequest("头像上传失败，请稍后重试");
            });
    }

    Mono<ServerResponse> updateProfile(ServerRequest request) {
        var openid = resolveOpenid(request);
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, UpdateProfileRequest.class))
            .flatMap(body -> wxAuthService.updateProfile(openid, body)
                .flatMap(dto -> ServerResponse.ok().bodyValue(dto)))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("updateProfile failed: {}", e.getMessage());
                return badRequest("更新资料失败，请稍后重试");
            });
    }

    Mono<ServerResponse> sendHaloBindCode(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        log.info("sendHaloBindCode request received, openid={}", openid);
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, SendHaloBindCodeRequest.class))
            .flatMap(body -> wxAuthService.sendHaloBindCode(openid, body)
                .flatMap(dto -> ServerResponse.ok().bodyValue(dto)))
            .onErrorResume(WxAuthException.class, e -> {
                log.warn("sendHaloBindCode rejected, openid={}, reason={}", openid, e.getReason(), e);
                return badRequest(e.getReason());
            })
            .onErrorResume(Exception.class, e -> {
                var reason = EndpointErrorResolver.resolveSendHaloBindCodeError(e);
                log.error("sendHaloBindCode failed, openid={}, reason={}", openid, reason, e);
                return badRequest(reason);
            });
    }

    Mono<ServerResponse> bindHaloAccountByCode(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        log.info("bindHaloAccountByCode request received, openid={}", openid);
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, BindHaloAccountByCodeRequest.class))
            .flatMap(body -> wxAuthService.bindHaloAccountByCode(openid, body)
                .flatMap(dto -> ServerResponse.ok().bodyValue(dto)))
            .onErrorResume(WxAuthException.class, e -> {
                log.warn("bindHaloAccountByCode rejected, openid={}, reason={}", openid, e.getReason(), e);
                return badRequest(e.getReason());
            })
            .onErrorResume(Exception.class, e -> {
                var reason = EndpointErrorResolver.resolveBindHaloAccountByCodeError(e);
                log.error("bindHaloAccountByCode failed, openid={}, reason={}", openid, reason, e);
                return badRequest(reason);
            });
    }

    Mono<ServerResponse> createHaloAccount(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return wxAuthService.createHaloAccount(openid)
            .flatMap(dto -> ServerResponse.ok().bodyValue(dto))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("createHaloAccount failed: {}", e.getMessage());
                return badRequest("创建 Halo 账号失败，请稍后重试");
            });
    }

    Mono<ServerResponse> unbindHaloAccount(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        log.info("unbindHaloAccount request received, openid={}", openid);
        return wxAuthService.unbindHaloAccount(openid)
            .flatMap(dto -> ServerResponse.ok().bodyValue(dto))
            .onErrorResume(WxAuthException.class, e -> {
                log.warn("unbindHaloAccount rejected, openid={}, reason={}", openid, e.getReason(), e);
                return badRequest(e.getReason());
            })
            .onErrorResume(Exception.class, e -> {
                var reason = EndpointErrorResolver.resolveUnbindHaloAccountError(e);
                log.error("unbindHaloAccount failed, openid={}, reason={}", openid, reason, e);
                return badRequest(reason);
            });
    }

    Mono<ServerResponse> recordUserActivity(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, RecordUserActivityRequest.class))
            .flatMap(body -> userActivityService.recordActivity(openid, body)
                .flatMap(dto -> ServerResponse.ok().bodyValue(dto)))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(ExtensionException.class, e -> {
                log.warn("recordUserActivity extension error: {}", e.getMessage());
                return badRequest("记录行为失败");
            })
            .onErrorResume(Exception.class, e -> {
                log.warn("recordUserActivity failed: {}", e.getMessage(), e);
                return badRequest("记录行为失败");
            });
    }

    Mono<ServerResponse> listUserActivities(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        var type = request.queryParam("type").orElse(null);
        var page = parseIntParam(request, "page", 0);
        var size = parseIntParam(request, "size", 10);
        return userActivityService.listActivities(openid, type, page, size)
            .flatMap(response -> ServerResponse.ok().bodyValue(response))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("listUserActivities failed: {}", e.getMessage());
                return badRequest("查询行为记录失败，请稍后重试");
            });
    }

    Mono<ServerResponse> getUpvoteStatus(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        var articleName = request.queryParam("articleName").orElse(null);
        var contentType = UserContentType.parse(request.queryParam("contentType").orElse(null));
        return userActivityService.getUpvoteStatus(openid, articleName, contentType)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("getUpvoteStatus failed: {}", e.getMessage());
                return badRequest("查询点赞状态失败，请稍后重试");
            });
    }

    Mono<ServerResponse> cancelUserActivity(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, RecordUserActivityRequest.class))
            .flatMap(body -> userActivityService.cancelActivity(openid, body)
                .flatMap(response -> jsonOk(response)))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(ExtensionException.class, e -> {
                log.warn("cancelUserActivity extension error: {}", e.getMessage());
                return badRequest("取消行为失败");
            })
            .onErrorResume(Exception.class, e -> {
                log.warn("cancelUserActivity failed: {}", e.getMessage(), e);
                return badRequest("取消行为失败");
            });
    }

    Mono<ServerResponse> recordUserReaction(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, RecordUserActivityRequest.class))
            .flatMap(body -> userActivityService.recordReaction(openid, body)
                .flatMap(dto -> ServerResponse.ok().bodyValue(dto)))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(ExtensionException.class, e -> {
                log.warn("recordUserReaction extension error: {}", e.getMessage());
                return badRequest("表情反馈失败");
            })
            .onErrorResume(Exception.class, e -> {
                log.warn("recordUserReaction failed: {}", e.getMessage(), e);
                return badRequest("表情反馈失败");
            });
    }

    Mono<ServerResponse> cancelUserReaction(ServerRequest request) {
        var openid = resolveOpenid(request);
        if (!StringUtils.hasText(openid)) {
            return badRequest("缺少 Wechat-Session-Id");
        }
        return WxRequestParser.readRawBody(request)
            .flatMap(raw -> WxRequestParser.parseBody(raw, RecordUserActivityRequest.class))
            .flatMap(body -> userActivityService.cancelReaction(openid, body)
                .flatMap(response -> jsonOk(response)))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(ExtensionException.class, e -> {
                log.warn("cancelUserReaction extension error: {}", e.getMessage());
                return badRequest("取消表情失败");
            })
            .onErrorResume(Exception.class, e -> {
                log.warn("cancelUserReaction failed: {}", e.getMessage(), e);
                return badRequest("取消表情失败");
            });
    }

    Mono<ServerResponse> getContentReactions(ServerRequest request) {
        var openid = resolveOpenid(request);
        var articleName = request.queryParam("articleName").orElse(null);
        var contentType = UserContentType.parse(request.queryParam("contentType").orElse(null));
        return userActivityService.getReactionStatus(openid, articleName, contentType)
            .flatMap(response -> jsonOk(response))
            .onErrorResume(WxAuthException.class, e -> badRequest(e.getReason()))
            .onErrorResume(Exception.class, e -> {
                log.warn("getContentReactions failed: {}", e.getMessage());
                return badRequest("查询表情失败，请稍后重试");
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

    private static String resolveErrorMessage(Throwable e) {
        if (e instanceof ExtensionException extensionException
            && StringUtils.hasText(extensionException.getReason())) {
            return extensionException.getReason();
        }
        if (e instanceof ResponseStatusException responseStatusException
            && StringUtils.hasText(responseStatusException.getReason())) {
            return responseStatusException.getReason();
        }
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : "二维码生成失败，请稍后重试";
    }

    private static Mono<ServerResponse> jsonOk(Object body) {
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
