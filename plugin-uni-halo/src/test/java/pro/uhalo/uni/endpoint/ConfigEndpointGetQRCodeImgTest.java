package pro.uhalo.uni.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerRequest;
import pro.uhalo.uni.services.UniHaloService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.exception.ExtensionException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigEndpointGetQRCodeImgTest {

    @Mock
    private UniHaloService uniHaloService;

    @Mock
    private run.halo.app.extension.ReactiveExtensionClient client;

    @Mock
    private pro.uhalo.uni.services.WxAuthService wxAuthService;

    @Mock
    private pro.uhalo.uni.services.UserActivityService userActivityService;

    @InjectMocks
    private ConfigEndpoint configEndpoint;

    @Test
    void getQRCodeImgShouldReturn400WhenExtensionException() {
        when(uniHaloService.resolveQRCodeImageUrl(anyString()))
            .thenReturn(Mono.error(new ExtensionException("二维码数据操作失败：Forbidden")));

        ServerRequest request = MockServerRequest.builder()
            .pathVariable("postId", "post-1")
            .build();

        StepVerifier.create(configEndpoint.getQRCodeImg(request))
            .expectNextMatches(response -> response.statusCode().value() == 400)
            .verifyComplete();
    }

    @Test
    void getQRCodeImgShouldReturn400WhenBusinessError() {
        when(uniHaloService.resolveQRCodeImageUrl(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("请先在插件配置中填写小程序 appId 和 appSecret")));

        ServerRequest request = MockServerRequest.builder()
            .pathVariable("postId", "post-1")
            .build();

        StepVerifier.create(configEndpoint.getQRCodeImg(request))
            .expectNextMatches(response -> response.statusCode().value() == 400)
            .verifyComplete();
    }

    @Test
    void getQRCodeImgShouldReturn200WhenSuccess() {
        when(uniHaloService.resolveQRCodeImageUrl(anyString()))
            .thenReturn(Mono.just("https://example.com/qrcode.png"));

        ServerRequest request = MockServerRequest.builder()
            .pathVariable("postId", "post-1")
            .build();

        StepVerifier.create(configEndpoint.getQRCodeImg(request))
            .expectNextMatches(response -> response.statusCode().value() == 200)
            .verifyComplete();
    }
}
