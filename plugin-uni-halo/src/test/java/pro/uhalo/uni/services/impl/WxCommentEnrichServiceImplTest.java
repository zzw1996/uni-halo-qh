package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.WxUser;
import run.halo.app.infra.ExternalLinkProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxCommentEnrichServiceImplTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ExternalLinkProcessor externalLinkProcessor;

    @InjectMocks
    private WxCommentEnrichServiceImpl wxCommentEnrichService;

    private WxUser wxUser;

    @BeforeEach
    void setUp() {
        wxUser = new WxUser();
        wxUser.setOpenid("openid-123");
        wxUser.setNickname("测试用户");
        wxUser.setAvatarUrl("https://example.com/avatar.png");
    }

    @Test
    void enrichCommentBodyShouldFillOwnerAndMetadata() throws Exception {
        when(externalLinkProcessor.processLink(anyString()))
            .thenReturn("https://cdn.example.com/avatar.png");

        var raw = """
            {"raw":"hello","content":"<p>hello</p>"}
            """.trim().getBytes();
        var enriched = wxCommentEnrichService.enrichCommentBody(raw, wxUser, "openid-123");
        var root = OBJECT_MAPPER.readTree(enriched);

        assertThat(root.path("raw").asText()).isEqualTo("hello");
        assertThat(root.path("owner").path("displayName").asText()).isEqualTo("测试用户");
        assertThat(root.path("owner").path("avatar").asText()).isEqualTo("https://cdn.example.com/avatar.png");
        assertThat(root.path("owner").path("email").asText())
            .isEqualTo("openid-123" + Constants.MINI_COMMENT_EMAIL_SUFFIX);
        assertThat(root.path("owner").path("website").asText()).isEmpty();
        assertThat(root.path("metadata").path("annotations")
            .path(Constants.UNIHALO_OPENID_ANNOTATION).asText()).isEqualTo("openid-123");
    }

    @Test
    void enrichCommentBodyShouldUseDefaultDisplayNameWhenNicknameMissing() throws Exception {
        wxUser.setNickname(null);
        wxUser.setAvatarUrl(null);

        var enriched = wxCommentEnrichService.enrichCommentBody(new byte[0], wxUser, "openid-123");
        var root = OBJECT_MAPPER.readTree(enriched);

        assertThat(root.path("owner").path("displayName").asText())
            .isEqualTo(Constants.DEFAULT_WX_DISPLAY_NAME);
        assertThat(root.path("owner").path("avatar").asText()).isEmpty();
    }

    @Test
    void enrichCommentBodyShouldPreserveExistingOwnerFieldsAndOverrideRequiredOnes() throws Exception {
        when(externalLinkProcessor.processLink(anyString()))
            .thenReturn("https://cdn.example.com/new.png");

        var raw = OBJECT_MAPPER.writeValueAsBytes(OBJECT_MAPPER.readTree("""
            {"owner":{"displayName":"旧昵称","avatar":"https://old.example.com/a.png","email":"old@example.com","website":"https://old.example.com"}}
            """));

        var enriched = wxCommentEnrichService.enrichCommentBody(raw, wxUser, "openid-123");
        var owner = OBJECT_MAPPER.readTree(enriched).path("owner");

        assertThat(owner.path("displayName").asText()).isEqualTo("测试用户");
        assertThat(owner.path("avatar").asText()).isEqualTo("https://cdn.example.com/new.png");
        assertThat(owner.path("email").asText())
            .isEqualTo("openid-123" + Constants.MINI_COMMENT_EMAIL_SUFFIX);
        assertThat(owner.path("website").asText()).isEmpty();
    }

    @Test
    void enrichCommentBodyShouldRejectInvalidJson() {
        assertThatThrownBy(() -> wxCommentEnrichService.enrichCommentBody("{".getBytes(), wxUser, "openid-123"))
            .isInstanceOf(WxAuthException.class)
            .hasMessage("评论请求体格式错误");
    }

    @Test
    void buildCommentEmailShouldAppendMiniDomain() {
        assertThat(Constants.buildMiniCommentEmail("openid-abc"))
            .isEqualTo("openid-abc" + Constants.MINI_COMMENT_EMAIL_SUFFIX);
    }
}
