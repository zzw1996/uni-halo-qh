package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxCommentEnrichService;
import run.halo.app.infra.ExternalLinkProcessor;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class WxCommentEnrichServiceImpl implements WxCommentEnrichService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public byte[] enrichCommentBody(byte[] rawBody, WxUser wxUser, String openid) {
        try {
            var root = rawBody != null && rawBody.length > 0
                ? (ObjectNode) OBJECT_MAPPER.readTree(rawBody)
                : OBJECT_MAPPER.createObjectNode();

            var email = Constants.buildMiniCommentEmail(openid);
            var displayName = resolveDisplayName(wxUser);
            var avatar = resolveAvatar(wxUser);

            fillOwner(root, displayName, avatar, email);
            fillOpenidAnnotation(root, openid);

            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (IOException ex) {
            throw new WxAuthException("评论请求体格式错误");
        }
    }

    private void fillOwner(ObjectNode root, String displayName, String avatar, String email) {
        var owner = ensureObjectNode(root, "owner");
        owner.put("displayName", displayName);
        owner.put("avatar", avatar);
        owner.put("email", email);
        owner.put("website", "");
    }

    private void fillOpenidAnnotation(ObjectNode root, String openid) {
        var metadata = ensureObjectNode(root, "metadata");
        var annotations = ensureObjectNode(metadata, "annotations");
        annotations.put(Constants.UNIHALO_OPENID_ANNOTATION, openid);
    }

    private static ObjectNode ensureObjectNode(ObjectNode parent, String fieldName) {
        var node = parent.get(fieldName);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        return parent.putObject(fieldName);
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

    static String buildCommentEmail(String openid) {
        return Constants.buildMiniCommentEmail(openid);
    }
}
