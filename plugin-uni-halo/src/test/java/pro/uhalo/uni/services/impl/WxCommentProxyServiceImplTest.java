package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.exception.WxForbiddenException;
import pro.uhalo.uni.scheme.WxUser;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemSetting;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxCommentProxyServiceImplTest {

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private ExternalLinkProcessor externalLinkProcessor;

    @InjectMocks
    private WxCommentProxyServiceImpl wxCommentProxyService;

    private WxUser wxUser;
    private SystemSetting.Comment commentSetting;

    @BeforeEach
    void setUp() {
        wxUser = new WxUser();
        wxUser.setOpenid("openid-123");
        wxUser.setNickname("测试用户");
        wxUser.setAvatarUrl("https://example.com/avatar.png");

        commentSetting = new SystemSetting.Comment();
        commentSetting.setEnable(true);
        commentSetting.setRequireReviewForNew(false);
        commentSetting.setSystemUserOnly(false);
    }

    @Test
    void createCommentShouldPersistViaOfficialClient() {
        when(settingFetcher.fetch(eq(SystemSetting.Comment.GROUP), eq(SystemSetting.Comment.class)))
            .thenReturn(Mono.just(commentSetting));
        when(externalLinkProcessor.processLink(anyString()))
            .thenReturn("https://cdn.example.com/avatar.png");
        when(client.create(any(Comment.class))).thenAnswer(invocation -> {
            var created = invocation.<Comment>getArgument(0);
            created.getMetadata().setName("comment-created-1");
            return Mono.just(created);
        });

        var body = """
            {
              "subjectRef": {
                "group": "content.halo.run",
                "version": "v1alpha1",
                "kind": "Post",
                "name": "post-1"
              },
              "raw": "hello",
              "content": "<p>hello</p>"
            }
            """.trim().getBytes();
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/apis/api.halo.run/v1alpha1/comments").build());

        StepVerifier.create(wxCommentProxyService.createComment(body, wxUser, "openid-123", exchange))
            .assertNext(vo -> {
                assertThat(vo.isSuccess()).isTrue();
                assertThat(vo.getMetadata().getName()).isEqualTo("comment-created-1");
                assertThat(vo.getSpec().getRaw()).isEqualTo("hello");
                assertThat(vo.getOwner().getDisplayName()).isEqualTo("测试用户");
                assertThat(vo.getOwner().getAvatar()).isEqualTo("https://cdn.example.com/avatar.png");
                assertThat(vo.getSpec().getCreationTime()).isNotBlank();
            })
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(Comment.class);
        verify(client).create(captor.capture());
        assertThat(captor.getValue().getSpec().getOwner().getAnnotation(Comment.CommentOwner.AVATAR_ANNO))
            .isEqualTo("https://cdn.example.com/avatar.png");
    }

    @Test
    void createCommentShouldRejectWhenCommentDisabled() {
        commentSetting.setEnable(false);
        when(settingFetcher.fetch(eq(SystemSetting.Comment.GROUP), eq(SystemSetting.Comment.class)))
            .thenReturn(Mono.just(commentSetting));

        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/apis/api.halo.run/v1alpha1/comments").build());

        StepVerifier.create(wxCommentProxyService.createComment("{}".getBytes(), wxUser, "openid-123", exchange))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessage("评论功能已关闭"))
            .verify();
    }

    @Test
    void createReplyShouldRequireApprovedComment() {
        var comment = new Comment();
        var spec = new Comment.CommentSpec();
        spec.setApproved(false);
        comment.setSpec(spec);

        when(client.get(Comment.class, "comment-1")).thenReturn(Mono.just(comment));

        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/apis/api.halo.run/v1alpha1/comments/comment-1/reply").build());

        StepVerifier.create(wxCommentProxyService.createReply(
                "comment-1", "{\"raw\":\"reply\"}".getBytes(), wxUser, "openid-123", exchange))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessage("评论正在审核中，暂不能回复"))
            .verify();
    }

    @Test
    void deleteCommentShouldDeleteOwnedCommentAndReturnDto() {
        var comment = ownedComment("comment-1", "openid-123");
        when(client.get(Comment.class, "comment-1")).thenReturn(Mono.just(comment));
        when(client.delete(comment)).thenReturn(Mono.just(comment));

        StepVerifier.create(wxCommentProxyService.deleteComment("comment-1", "openid-123"))
            .assertNext(vo -> {
                assertThat(vo.isSuccess()).isTrue();
                assertThat(vo.getName()).isEqualTo("comment-1");
            })
            .verifyComplete();

        verify(client).delete(comment);
    }

    @Test
    void deleteCommentShouldRejectWhenNotOwner() {
        var comment = ownedComment("comment-1", "other-openid");
        when(client.get(Comment.class, "comment-1")).thenReturn(Mono.just(comment));

        StepVerifier.create(wxCommentProxyService.deleteComment("comment-1", "openid-123"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxForbiddenException.class)
                .hasMessage("无权删除该评论"))
            .verify();

        verify(client, never()).delete(any());
    }

    @Test
    void deleteCommentShouldRejectWhenCommentMissing() {
        when(client.get(Comment.class, "missing")).thenReturn(Mono.empty());

        StepVerifier.create(wxCommentProxyService.deleteComment("missing", "openid-123"))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessage("评论不存在"))
            .verify();
    }

    private static Comment ownedComment(String name, String openid) {
        var owner = new Comment.CommentOwner();
        owner.setKind(Comment.CommentOwner.KIND_EMAIL);
        owner.setName(openid + Constants.MINI_COMMENT_EMAIL_SUFFIX);

        var spec = new Comment.CommentSpec();
        spec.setOwner(owner);

        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setAnnotations(Map.of(Constants.UNIHALO_OPENID_ANNOTATION, openid));

        var comment = new Comment();
        comment.setMetadata(metadata);
        comment.setSpec(spec);
        return comment;
    }
}
