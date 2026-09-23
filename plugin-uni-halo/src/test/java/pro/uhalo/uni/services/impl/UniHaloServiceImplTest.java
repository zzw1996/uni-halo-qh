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

    // ===== 底部导航（tabbarConfig）归一化 =====
    //
    // 微信原生底栏最多声明 5 项、少于 4 项又会退化成不像底栏的样子，而候选有 7 项
    // （首页/分类/瞬间/榜单/图库/小店/我的），所以「4~5 项」这条必须在下发配置时兜住：
    // 插件没有保存钩子，表单里也做不了跨字段校验。

    /** 造一份底部导航配置：不传的开关按「关」处理，方便每个用例只开需要的项 */
    private static ObjectNode tabbarConfigNode(boolean enabled, String... onSwitches) {
        var node = MAPPER.createObjectNode();
        node.put("enabled", enabled);
        var on = java.util.Set.of(onSwitches);
        for (var field : new String[] {"showCategory", "showMoments", "showRank", "showGallery", "showStore"}) {
            node.put(field, on.contains(field));
        }
        node.putObject("store").put("appId", "wx-store-app-id");
        return node;
    }

    private static java.util.List<String> itemKeys(com.fasterxml.jackson.databind.JsonNode tabbar) {
        var keys = new java.util.ArrayList<String>();
        tabbar.get("items").forEach(item -> keys.add(item.get("key").asText()));
        return keys;
    }

    @Test
    void tabbarShouldStayUntouchedWhenDisabled() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(false, "showCategory"));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var tabbar = result.get("tabbarConfig");
                // 没开启时小程序端不接管底栏，不该下发 items
                assertThat(tabbar.has("items")).isFalse();
                assertThat(tabbar.get("enabled").asBoolean()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void tabbarShouldTrimToFiveInFixedOrder() {
        // 5 个可选项全开 = 7 项，要裁到 5：先丢小店、再丢图库
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(true, "showCategory", "showMoments", "showRank",
            "showGallery", "showStore"));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var items = result.get("tabbarConfig").get("items");
                assertThat(itemKeys(result.get("tabbarConfig")))
                    .containsExactly("home", "category", "moments", "rank", "about");
                // 首页固定开头且是原生 tab 页
                assertThat(items.get(0).get("nativeTab").asBoolean()).isTrue();
                assertThat(items.get(0).get("path").asText()).isEqualTo("/pages/tabbar/home/home");
                // 图标路径由服务端拼好，前端不用猜
                assertThat(items.get(1).get("icon").asText()).isEqualTo("/static/tabbar/select_category.png");
                assertThat(items.get(1).get("activeIcon").asText())
                    .isEqualTo("/static/tabbar/select_category_active.png");
            })
            .verifyComplete();
    }

    @Test
    void tabbarShouldFillUpToThreeWhenAllOptionalOff() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(true));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> assertThat(itemKeys(result.get("tabbarConfig")))
                .containsExactly("home", "category", "about"))
            .verifyComplete();
    }

    @Test
    void tabbarShouldKeepOrderWhenOnlyStoreIsOn() {
        // 只开小店：首页 + 小店 + 我的正好 3 项，已达下限，不再补
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(true, "showStore"));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var items = result.get("tabbarConfig").get("items");
                assertThat(itemKeys(result.get("tabbarConfig")))
                    .containsExactly("home", "store", "about");
                // 小店挂在瞬间页上（原生页），靠 mode 切视图，因此能走 switchTab
                assertThat(items.get(1).get("nativeTab").asBoolean()).isTrue();
                assertThat(items.get(1).get("path").asText()).isEqualTo("/pages/tabbar/moments/moments");
                assertThat(items.get(1).get("mode").asText()).isEqualTo("store");
            })
            .verifyComplete();
    }

    @Test
    void tabbarShouldHostGalleryOnMomentsPage() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(true, "showGallery", "showRank"));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var tabbar = result.get("tabbarConfig");
                var items = tabbar.get("items");
                assertThat(itemKeys(tabbar)).containsExactly("home", "rank", "gallery", "about");
                // 图库挂在瞬间页上（原生页），靠 mode 区分视图
                assertThat(items.get(2).get("nativeTab").asBoolean()).isTrue();
                assertThat(items.get(2).get("path").asText()).isEqualTo("/pages/tabbar/moments/moments");
                assertThat(items.get(2).get("mode").asText()).isEqualTo("gallery");
                // 小店字段原样保留，供小店页判断开通状态
                assertThat(tabbar.get("store").get("appId").asText()).isEqualTo("wx-store-app-id");
            })
            .verifyComplete();
    }

    @Test
    void tabbarShouldNotMutateSourceConfig() {
        var original = tabbarConfigNode(true, "showStore");
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", original);
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> assertThat(result.get("tabbarConfig").has("items")).isTrue())
            .verifyComplete();

        assertThat(original.has("items")).isFalse();
    }

    @Test
    void getByGroupNameShouldNormalizeTabbar() {
        when(settingFetcher.get("tabbarConfig")).thenReturn(Mono.just(tabbarConfigNode(true, "showRank")));

        StepVerifier.create(uniHaloService.getAppConfigsByGroupName("tabbarConfig"))
            .assertNext(node -> assertThat(itemKeys(node)).containsExactly("home", "rank", "about"))
            .verifyComplete();
    }

    // ===== 候选清单（小程序端「系统设置」里用户自己勾选时用）=====
    //
    // items[] 是站长那份「归一化后的默认显示」；candidates[] 必须是全量 7 项，
    // 否则用户勾不出被站长关掉的那些（比如图库、小店）。

    @Test
    void tabbarCandidatesShouldCoverAllSevenInOrder() {
        var configs = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        configs.put("tabbarConfig", tabbarConfigNode(true, "showCategory", "showRank"));
        when(settingFetcher.getValues()).thenReturn(Mono.just(configs));

        StepVerifier.create(uniHaloService.getAppConfigs())
            .assertNext(result -> {
                var tabbar = result.get("tabbarConfig");
                var keys = new java.util.ArrayList<String>();
                tabbar.get("candidates").forEach(item -> keys.add(item.get("key").asText()));
                assertThat(keys).containsExactly("home", "category", "moments", "rank", "gallery", "store", "about");

                var byKey = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                tabbar.get("candidates").forEach(item -> byKey.put(item.get("key").asText(), item));

                // 固定项：locked=true，前端不给开关
                assertThat(byKey.get("home").get("locked").asBoolean()).isTrue();
                assertThat(byKey.get("about").get("locked").asBoolean()).isTrue();
                // 可选项：locked=false，并带上站长开关的原始值（用于「恢复站长默认」）
                assertThat(byKey.get("category").get("locked").asBoolean()).isFalse();
                assertThat(byKey.get("category").get("adminOn").asBoolean()).isTrue();
                assertThat(byKey.get("moments").get("adminOn").asBoolean()).isFalse();
                assertThat(byKey.get("gallery").get("adminOn").asBoolean()).isFalse();
                // 被裁掉/被关掉的项也必须出现在候选里，且带着可用的图标路径
                assertThat(byKey.get("store").get("path").asText()).isEqualTo("/pages/tabbar/moments/moments");
                assertThat(byKey.get("store").get("mode").asText()).isEqualTo("store");
                assertThat(byKey.get("store").get("nativeTab").asBoolean()).isTrue();
                // 普通页面不带 mode
                assertThat(byKey.get("category").has("mode")).isFalse();
                assertThat(byKey.get("gallery").get("icon").asText())
                    .isEqualTo("/static/tabbar/select_picture.png");
                assertThat(byKey.get("store").get("activeIcon").asText())
                    .isEqualTo("/static/tabbar/select_shop_active.png");
            })
            .verifyComplete();
    }
}
