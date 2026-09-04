package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.CommentCreatedVO;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Metadata;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WxCommentResponseMapperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void toCommentCreatedVOShouldProduceSerializableJson() throws Exception {
        var metadata = new Metadata();
        metadata.setName("comment-abc");

        var owner = new Comment.CommentOwner();
        owner.setDisplayName("测试用户");
        owner.setAnnotations(Map.of(Comment.CommentOwner.AVATAR_ANNO, "https://cdn.example.com/a.png"));

        var spec = new Comment.CommentSpec();
        spec.setRaw("hello");
        spec.setCreationTime(Instant.parse("2026-07-01T10:00:00Z"));
        spec.setOwner(owner);

        var comment = new Comment();
        comment.setMetadata(metadata);
        comment.setSpec(spec);

        var vo = WxCommentResponseMapper.toCommentCreatedVO(comment);
        var json = OBJECT_MAPPER.writeValueAsString(vo);

        assertThat(vo.isSuccess()).isTrue();
        assertThat(vo.getMetadata().getName()).isEqualTo("comment-abc");
        assertThat(vo.getSpec().getRaw()).isEqualTo("hello");
        assertThat(vo.getOwner().getDisplayName()).isEqualTo("测试用户");
        assertThat(vo.getOwner().getAvatar()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(json).contains("\"success\":true")
            .contains("\"name\":\"comment-abc\"")
            .contains("\"raw\":\"hello\"")
            .contains("\"displayName\":\"测试用户\"");
    }

    @Test
    void toCommentCreatedVOShouldOmitEmptyAvatar() {
        var owner = new Comment.CommentOwner();
        owner.setDisplayName("微信用户");
        owner.setAnnotations(Map.of(Comment.CommentOwner.WEBSITE_ANNO, ""));

        var spec = new Comment.CommentSpec();
        spec.setOwner(owner);

        var vo = WxCommentResponseMapper.toCommentCreatedVO(new Comment() {{
            setSpec(spec);
        }});

        assertThat(vo.getOwner().getAvatar()).isNull();
    }
}
