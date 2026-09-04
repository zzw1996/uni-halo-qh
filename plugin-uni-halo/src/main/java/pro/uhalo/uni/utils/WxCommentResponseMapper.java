package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.CommentCreatedVO;
import pro.uhalo.uni.dto.ReplyCreatedVO;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;

import java.time.Instant;

public final class WxCommentResponseMapper {

    private WxCommentResponseMapper() {
    }

    public static CommentCreatedVO toCommentCreatedVO(Comment comment) {
        if (comment == null) {
            return CommentCreatedVO.builder().success(true).build();
        }
        var spec = comment.getSpec();
        var owner = spec != null ? spec.getOwner() : null;
        return CommentCreatedVO.builder()
            .success(true)
            .metadata(CommentCreatedVO.MetadataVO.builder()
                .name(comment.getMetadata() != null ? comment.getMetadata().getName() : null)
                .build())
            .spec(CommentCreatedVO.SpecVO.builder()
                .raw(spec != null ? spec.getRaw() : null)
                .creationTime(formatInstant(spec != null ? spec.getCreationTime() : null))
                .build())
            .owner(CommentCreatedVO.OwnerVO.builder()
                .displayName(owner != null ? owner.getDisplayName() : null)
                .avatar(resolveAvatar(owner))
                .build())
            .build();
    }

    public static ReplyCreatedVO toReplyCreatedVO(Reply reply) {
        if (reply == null) {
            return ReplyCreatedVO.builder().success(true).build();
        }
        var spec = reply.getSpec();
        var owner = spec != null ? spec.getOwner() : null;
        return ReplyCreatedVO.builder()
            .success(true)
            .metadata(ReplyCreatedVO.MetadataVO.builder()
                .name(reply.getMetadata() != null ? reply.getMetadata().getName() : null)
                .build())
            .spec(ReplyCreatedVO.SpecVO.builder()
                .raw(spec != null ? spec.getRaw() : null)
                .creationTime(formatInstant(spec != null ? spec.getCreationTime() : null))
                .commentName(spec != null ? spec.getCommentName() : null)
                .build())
            .owner(ReplyCreatedVO.OwnerVO.builder()
                .displayName(owner != null ? owner.getDisplayName() : null)
                .avatar(resolveAvatar(owner))
                .build())
            .build();
    }

    private static String resolveAvatar(Comment.CommentOwner owner) {
        if (owner == null) {
            return null;
        }
        var avatar = owner.getAnnotation(Comment.CommentOwner.AVATAR_ANNO);
        return StringUtils.hasText(avatar) ? avatar : null;
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
