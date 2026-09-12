package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.services.QRCodeInfoService;
import pro.uhalo.uni.utils.TokenManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 配置下发脱敏测试。
 *
 * <p>{@code GET /getConfigs} 与 {@code GET /getConfigs/{groupName}} 均为匿名可访问接口，
 * 小程序端会把响应整体写入本地存储。虚拟支付的 appKey（用于计算 paySig 签名）与 offerId
 * 只在服务端使用，一旦下发到客户端，任何人抓包即可伪造签名下单，因此必须在下发前剥离。</p>
 */
@ExtendWith(MockitoExtension.class)
class UniHaloServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FAKE_APP_KEY = "super-secret-app-key";
    private static final String FAKE_OFFER_ID = "offer-12345";

    @Mock
    private QRCodeInfoService qrCodeInfoService;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private TokenManager tokenManager;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private ExternalLinkProcessor externalLinkProcessor;

    @InjectMocks
    private UniHaloServiceImpl uniHaloService;

    private static ObjectNode payConfigNode() {
        var node = MAPPER.createObjectNode();
        node.put("enabled", true);
        node.put("offerId", FAKE_OFFER_ID);
        node.put("appKey", FAKE_APP_KEY);
        node.put("env", 0);
        node.put("zoneId", 1);
        var goods = node.putArray("goods");
        var item = goods.addObject();
        item.put("goodsId", "g_dashboard");
        item.put("name", "数据看板高级版");
        item.put("price", 600);
        item.put("type", "dashboard");
        return node;
    }

    @Test
    void getAllConfigsShouldStripPaySecrets() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("payConfig", payConfigNode());
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var pay = (ObjectNode) result.get("payConfig");
                // 密钥必须被剥离
                assertThat(pay.has("appKey")).isFalse();
                assertThat(pay.has("offerId")).isFalse();
                assertThat(pay.toString()).doesNotContain(FAKE_APP_KEY);
                assertThat(pay.toString()).doesNotContain(FAKE_OFFER_ID);
                // 小程序展示所需字段必须保留
                assertThat(pay.get("enabled").asBoolean()).isTrue();
                assertThat(pay.get("goods")).hasSize(1);
                assertThat(pay.get("goods").get(0).get("goodsId").asText()).isEqualTo("g_dashboard");
                assertThat(pay.get("goods").get(0).get("price").asInt()).isEqualTo(600);
            })
            .verifyComplete();
    }

    @Test
    void getAllConfigsShouldNotMutateSourceConfig() {
        var original = payConfigNode();
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("payConfig", original);
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> assertThat(result.get("payConfig").has("appKey")).isFalse())
            .verifyComplete();

        // 服务端自身仍需读到完整配置用于签名，源对象不得被破坏
        assertThat(original.has("appKey")).isTrue();
        assertThat(original.get("appKey").asText()).isEqualTo(FAKE_APP_KEY);
        assertThat(original.has("offerId")).isTrue();
    }

    @Test
    void getAllConfigsShouldKeepOtherGroupsUntouched() {
        var other = MAPPER.createObjectNode();
        other.put("enabled", true);
        other.put("rewardPoints", 30);
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("inviteConfig", other);
        configs.put("payConfig", payConfigNode());
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                assertThat(result).hasSize(2);
                assertThat(result.get("inviteConfig").get("rewardPoints").asInt()).isEqualTo(30);
            })
            .verifyComplete();
    }

    @Test
    void getAllConfigsShouldWorkWithoutPayConfig() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("basicConfig", MAPPER.createObjectNode());
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                assertThat(result).hasSize(1);
                assertThat(result).doesNotContainKey("payConfig");
            })
            .verifyComplete();
    }

    @Test
    void getByGroupNameShouldStripPaySecrets() {
        when(settingFetcher.get("payConfig")).thenReturn(Mono.just(payConfigNode()));

        StepVerifier.create(uniHaloService.getAppConfigsByGroupName("payConfig"))
            .assertNext(node -> {
                var pay = (ObjectNode) node;
                assertThat(pay.has("appKey")).isFalse();
                assertThat(pay.has("offerId")).isFalse();
                assertThat(pay.get("enabled").asBoolean()).isTrue();
                assertThat(pay.get("goods")).hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void getByGroupNameShouldReturnOtherGroupsUnchanged() {
        var wx = MAPPER.createObjectNode();
        wx.put("appId", "wx-app-id");
        when(settingFetcher.get("wxAppConfig")).thenReturn(Mono.just(wx));

        StepVerifier.create(uniHaloService.getAppConfigsByGroupName("wxAppConfig"))
            .assertNext(node -> assertThat(node.get("appId").asText()).isEqualTo("wx-app-id"))
            .verifyComplete();
    }

    @Test
    void getByGroupNameShouldHandleMissingConfig() {
        when(settingFetcher.get("payConfig")).thenReturn(Mono.empty());

        StepVerifier.create(uniHaloService.getAppConfigsByGroupName("payConfig"))
            .verifyComplete();
    }
}
