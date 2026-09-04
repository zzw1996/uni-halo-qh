package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.dto.CommentCreatedVO;
import pro.uhalo.uni.dto.CommentDeletedVO;
import pro.uhalo.uni.dto.ReplyCreatedVO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxCommentProxyService;
import pro.uhalo.uni.utils.WxCommentOwnershipVerifier;
import pro.uhalo.uni.utils.WxCommentResponseMapper;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemSetting;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WxCommentProxyServiceImpl implements WxCommentProxyService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public Mono<CommentCreatedVO> createComment(byte[] body, WxUser wxUser, String openid,
                                                ServerWebExchange exchange) {
        return parseBody(body)
            .flatMap(request -> fetchCommentSetting()
                .flatMap(setting -> {
                    assertCommentEnabled(setting);
                    assertNotSystemUserOnly(setting);
                    var comment = buildComment(request, wxUser, openid, exchange, setting);
                    return client.create(comment)
                        .map(WxCommentResponseMapper::toCommentCreatedVO);
                }));
    }

    @Override
    public Mono<ReplyCreatedVO> createReply(String commentName, byte[] body, WxUser wxUser, String openid,
                                            ServerWebExchange exchange) {
        if (!StringUtils.hasText(commentName)) {
            return Mono.error(new WxAuthException("缺少评论名称"));
        }
        return parseBody(body)
            .flatMap(request -> client.get(Comment.class, commentName.trim())
                .switchIfEmpty(Mono.error(new WxAuthException("评论不存在")))
                .flatMap(comment -> {
                    if (!Boolean.TRUE.equals(comment.getSpec().getApproved())) {
                        return Mono.error(new WxAuthException("评论正在审核中，暂不能回复"));
                    }
                    var reply = buildReply(commentName.trim(), request, wxUser, openid, exchange);
                    var quotedReply = reply.getSpec().getQuoteReply();
                    if (!StringUtils.hasText(quotedReply)) {
                        return client.create(reply)
                            .map(WxCommentResponseMapper::toReplyCreatedVO);
                    }
                    return client.get(Reply.class, quotedReply.trim())
                        .switchIfEmpty(Mono.error(new WxAuthException("引用的回复不存在")))
                        .flatMap(quote -> {
                            if (!Boolean.TRUE.equals(quote.getSpec().getApproved())) {
                                return Mono.error(new WxAuthException("引用的回复正在审核中，暂不能回复"));
                            }
                            return client.create(reply)
                                .map(WxCommentResponseMapper::toReplyCreatedVO);
                        });
                }));
    }

    @Override
    public Mono<CommentDeletedVO> deleteComment(String commentName, String openid) {
        if (!StringUtils.hasText(commentName)) {
            return Mono.error(new WxAuthException("缺少评论名称"));
        }
        var normalizedName = commentName.trim();
        return client.get(Comment.class, normalizedName)
            .switchIfEmpty(Mono.error(new WxAuthException("评论不存在")))
            .flatMap(comment -> {
                WxCommentOwnershipVerifier.assertOwnedByOpenid(comment, openid);
                return client.delete(comment)
                    .thenReturn(CommentDeletedVO.builder()
                        .success(true)
                        .name(normalizedName)
                        .build());
            });
    }

    private Mono<JsonNode> parseBody(byte[] body) {
        return Mono.fromCallable(() -> {
            if (body == null || body.length == 0) {
                throw new WxAuthException("评论内容不能为空");
            }
            return OBJECT_MAPPER.readTree(body);
        }).onErrorMap(IOException.class, ex -> new WxAuthException("评论请求体格式错误"));
    }

    private Mono<SystemSetting.Comment> fetchCommentSetting() {
        return settingFetcher.fetch(SystemSetting.Comment.GROUP, SystemSetting.Comment.class)
            .defaultIfEmpty(new SystemSetting.Comment());
    }

    private Comment buildComment(JsonNode request, WxUser wxUser, String openid,
                                 ServerWebExchange exchange, SystemSetting.Comment setting) {
        var subjectRef = parseSubjectRef(request);
        var raw = requiredText(request, "raw", "评论内容不能为空");
        var content = optionalText(request.get("content"));
        if (!StringUtils.hasText(content)) {
            content = raw;
        }

        var comment = new Comment();
        comment.setMetadata(buildMetadata(openid, "comment-"));

        var spec = new Comment.CommentSpec();
        spec.setSubjectRef(subjectRef);
        spec.setRaw(raw);
        spec.setContent(content);
        spec.setOwner(buildOwner(wxUser, openid));
        spec.setTop(false);
        spec.setPriority(0);
        spec.setHidden(request.path("hidden").asBoolean(false));
        spec.setAllowNotification(!request.has("allowNotification")
            || request.path("allowNotification").asBoolean(true));
        spec.setApproved(Boolean.FALSE.equals(setting.getRequireReviewForNew()));
        if (Boolean.TRUE.equals(spec.getApproved())) {
            spec.setApprovedTime(Instant.now());
        }
        spec.setCreationTime(Instant.now());
        applyRequestContext(spec, exchange);
        comment.setSpec(spec);
        return comment;
    }

    private Reply buildReply(String commentName, JsonNode request, WxUser wxUser, String openid,
                             ServerWebExchange exchange) {
        var raw = requiredText(request, "raw", "回复内容不能为空");
        var content = optionalText(request.get("content"));
        if (!StringUtils.hasText(content)) {
            content = raw;
        }

        var reply = new Reply();
        reply.setMetadata(buildMetadata(openid, "reply-"));

        var spec = new Reply.ReplySpec();
        spec.setCommentName(commentName);
        spec.setRaw(raw);
        spec.setContent(content);
        spec.setOwner(buildOwner(wxUser, openid));
        spec.setTop(false);
        spec.setPriority(0);
        spec.setApproved(false);
        spec.setCreationTime(Instant.now());
        spec.setQuoteReply(optionalText(request.get("quoteReply")));
        spec.setAllowNotification(!request.has("allowNotification")
            || request.path("allowNotification").asBoolean(true));
        applyRequestContext(spec, exchange);
        reply.setSpec(spec);
        return reply;
    }

    private Metadata buildMetadata(String openid, String generateName) {
        var metadata = new Metadata();
        metadata.setGenerateName(generateName);
        var annotations = new HashMap<String, String>();
        annotations.put(Constants.UNIHALO_OPENID_ANNOTATION, openid);
        metadata.setAnnotations(annotations);
        return metadata;
    }

    private Comment.CommentOwner buildOwner(WxUser wxUser, String openid) {
        var email = Constants.buildMiniCommentEmail(openid);
        var owner = new Comment.CommentOwner();
        owner.setKind(Comment.CommentOwner.KIND_EMAIL);
        owner.setName(email);
        owner.setDisplayName(resolveDisplayName(wxUser));
        var annotations = new HashMap<String, String>();
        annotations.put(Comment.CommentOwner.AVATAR_ANNO, resolveAvatar(wxUser));
        annotations.put(Comment.CommentOwner.WEBSITE_ANNO, "");
        owner.setAnnotations(annotations);
        return owner;
    }

    private Ref parseSubjectRef(JsonNode request) {
        var subjectRefNode = request.path("subjectRef");
        if (subjectRefNode.isMissingNode() || subjectRefNode.isNull()) {
            throw new WxAuthException("缺少 subjectRef");
        }
        var group = requiredText(subjectRefNode, "group", "subjectRef.group 不能为空");
        var version = requiredText(subjectRefNode, "version", "subjectRef.version 不能为空");
        var kind = requiredText(subjectRefNode, "kind", "subjectRef.kind 不能为空");
        var name = requiredText(subjectRefNode, "name", "subjectRef.name 不能为空");

        var subjectRef = new Ref();
        subjectRef.setGroup(group);
        subjectRef.setVersion(version);
        subjectRef.setKind(kind);
        subjectRef.setName(name);
        return subjectRef;
    }

    private void applyRequestContext(Comment.BaseCommentSpec spec, ServerWebExchange exchange) {
        var userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
        if (StringUtils.hasText(userAgent)) {
            spec.setUserAgent(userAgent);
        }
        Optional.ofNullable(exchange.getRequest().getRemoteAddress())
            .map(InetSocketAddress::getAddress)
            .ifPresent(address -> spec.setIpAddress(address.getHostAddress()));
    }

    private static void assertCommentEnabled(SystemSetting.Comment setting) {
        if (Boolean.FALSE.equals(setting.getEnable())) {
            throw new WxAuthException("评论功能已关闭");
        }
    }

    private static void assertNotSystemUserOnly(SystemSetting.Comment setting) {
        if (Boolean.TRUE.equals(setting.getSystemUserOnly())) {
            throw new WxAuthException("当前站点仅允许登录用户评论");
        }
    }

    private String resolveDisplayName(WxUser wxUser) {
        if (wxUser != null && StringUtils.hasText(wxUser.getNickname())) {
            return wxUser.getNickname().trim();
        }
        return Constants.DEFAULT_WX_DISPLAY_NAME;
    }

    private String resolveAvatar(WxUser wxUser) {
        if (wxUser == null || !StringUtils.hasText(wxUser.getAvatarUrl())) {
            return "";
        }
        return externalLinkProcessor.processLink(wxUser.getAvatarUrl().trim());
    }

    private static String requiredText(JsonNode node, String field, String message) {
        var value = optionalText(node.get(field));
        if (!StringUtils.hasText(value)) {
            throw new WxAuthException(message);
        }
        return value;
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        var value = node.asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
