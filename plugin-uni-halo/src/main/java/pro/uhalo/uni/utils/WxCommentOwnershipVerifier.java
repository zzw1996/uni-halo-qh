package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxForbiddenException;
import run.halo.app.core.extension.content.Comment;

public final class WxCommentOwnershipVerifier {

    private WxCommentOwnershipVerifier() {
    }

    public static void assertOwnedByOpenid(Comment comment, String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new WxForbiddenException("无权删除该评论");
        }
        if (matchesOpenidAnnotation(comment, openid.trim())
            || matchesOwnerEmail(comment, openid.trim())) {
            return;
        }
        throw new WxForbiddenException("无权删除该评论");
    }

    static boolean matchesOpenidAnnotation(Comment comment, String openid) {
        if (comment == null || comment.getMetadata() == null) {
            return false;
        }
        var annotations = comment.getMetadata().getAnnotations();
        if (annotations == null) {
            return false;
        }
        return openid.equals(annotations.get(Constants.UNIHALO_OPENID_ANNOTATION));
    }

    static boolean matchesOwnerEmail(Comment comment, String openid) {
        if (comment == null || comment.getSpec() == null || comment.getSpec().getOwner() == null) {
            return false;
        }
        var owner = comment.getSpec().getOwner();
        if (!Comment.CommentOwner.KIND_EMAIL.equals(owner.getKind())) {
            return false;
        }
        var expectedEmail = Constants.buildMiniCommentEmail(openid);
        return expectedEmail.equals(owner.getName());
    }
}
