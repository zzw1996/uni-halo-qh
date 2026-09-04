package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxForbiddenException;
import pro.uhalo.uni.services.impl.WxCommentEnrichServiceImpl;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Metadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WxCommentOwnershipVerifierTest {

    @Test
    void assertOwnedByOpenidShouldPassWhenAnnotationMatches() {
        var comment = commentWithOwner("other@example.com", null);
        comment.getMetadata().setAnnotations(Map.of(Constants.UNIHALO_OPENID_ANNOTATION, "openid-123"));

        assertThatCode(() -> WxCommentOwnershipVerifier.assertOwnedByOpenid(comment, "openid-123"))
            .doesNotThrowAnyException();
    }

    @Test
    void assertOwnedByOpenidShouldPassWhenOwnerEmailMatches() {
        var email = Constants.buildMiniCommentEmail("openid-123");
        var comment = commentWithOwner(email, null);

        assertThatCode(() -> WxCommentOwnershipVerifier.assertOwnedByOpenid(comment, "openid-123"))
            .doesNotThrowAnyException();
    }

    @Test
    void assertOwnedByOpenidShouldRejectOtherUserComment() {
        var comment = commentWithOwner("other-openid@mini.unihalo.local", null);
        comment.getMetadata().setAnnotations(Map.of(Constants.UNIHALO_OPENID_ANNOTATION, "other-openid"));

        assertThatThrownBy(() -> WxCommentOwnershipVerifier.assertOwnedByOpenid(comment, "openid-123"))
            .isInstanceOf(WxForbiddenException.class)
            .hasMessage("无权删除该评论");
    }

    @Test
    void matchesOwnerEmailShouldIgnoreNonEmailOwnerKind() {
        var owner = new Comment.CommentOwner();
        owner.setKind("User");
        owner.setName("admin");

        var spec = new Comment.CommentSpec();
        spec.setOwner(owner);
        var comment = new Comment();
        comment.setSpec(spec);

        assertThat(WxCommentOwnershipVerifier.matchesOwnerEmail(comment, "openid-123")).isFalse();
    }

    private static Comment commentWithOwner(String ownerName, Map<String, String> annotations) {
        var owner = new Comment.CommentOwner();
        owner.setKind(Comment.CommentOwner.KIND_EMAIL);
        owner.setName(ownerName);

        var spec = new Comment.CommentSpec();
        spec.setOwner(owner);

        var metadata = new Metadata();
        if (annotations != null) {
            metadata.setAnnotations(annotations);
        }

        var comment = new Comment();
        comment.setMetadata(metadata);
        comment.setSpec(spec);
        return comment;
    }
}
