package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.CreatePayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.TokenManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 虚拟支付合规边界测试。
 *
 * <p>个人主体小程序仅允许「道具直购」，微信官方文档明确要求「只做道具直购，
 * 不引入代币等额外功能」。本测试锁定该边界：现金不得经由商品配置转化为
 * 可自由消费的积分余额（即不存在积分充值通道）。</p>
 */
@ExtendWith(MockitoExtension.class)
class VirtualPayServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private PayOrderPersistence payOrderPersistence;

    @Mock
    private PointsPersistence pointsPersistence;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private TokenManager tokenManager;

    @InjectMocks
    private VirtualPayServiceImpl virtualPayService;

    private void stubPayConfig(String json) throws Exception {
        when(settingFetcher.get("payConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    @Test
    void shouldOnlyExposeDirectPurchaseGoods() throws Exception {
        // 样例必须用当前在售类型（content_ticket/outfit）：
        // publish_ticket 已下架，配了也会被过滤掉，断言就失去意义了
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":2},
              {"goodsId":"g_outfit","name":"头像框","price":900,"type":"outfit","style":"avatar_gold"}
            ]}
            """);
        // 装扮商品的 style 是装扮 key，必须在装扮商店登记，否则会被按「配置错误」隐藏
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.isEnabled()).isTrue();
                assertThat(config.getGoods()).hasSize(2);
                assertThat(config.getGoods())
                    .extracting("goodsId")
                    .containsExactly("g_ticket", "g_outfit");
            })
            .verifyComplete();
    }

    /**
     * 发布券已下架，不得再作为可购买商品下发。
     *
     * <p>下架原因：该券没有任何消费点——瞬间发布仅博主可用，普通用户买了也用不上。
     * 注意 {@code grantGoods} 仍保留 publish_ticket 发货分支，
     * 用于履约下架前「已支付未发货」的历史订单，二者并不矛盾。</p>
     */
    @Test
    void shouldNotExposePublishTicketAsPurchasableGoods() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_publish","name":"发布券","price":300,"type":"publish_ticket","value":1},
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":1}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> assertThat(config.getGoods())
                .extracting("goodsId")
                .doesNotContain("g_publish")
                .containsExactly("g_ticket"))
            .verifyComplete();
    }

    /**
     * 数据看板高级版已降级为纯积分兑换权益（见 mallConfig.exchangeItems 的
     * dashboard_advanced），不再作为付费道具售卖。
     *
     * <p>即使站长仍配了 type=dashboard，也不得下发为可购买商品，否则用户仍能花钱购买，
     * 降级就等于没生效。注意：grantGoods 仍保留 dashboard 发货分支，
     * 用于履约升级前「已支付未发货」的历史订单，二者并不矛盾。</p>
     */
    @Test
    void shouldNotExposeDashboardAsPurchasableGoods() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_dashboard","name":"数据看板高级版","price":600,"type":"dashboard"},
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":1}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.getGoods())
                    .extracting("goodsId")
                    .doesNotContain("g_dashboard")
                    .containsExactly("g_ticket");
            })
            .verifyComplete();
    }

    /** type=points 属于现金充值积分（代币式），个人主体不支持，必须被过滤掉。 */
    @Test
    void shouldRejectPointsRechargeGoods() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_points","name":"100积分","price":100,"type":"points","points":100},
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":1}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.getGoods())
                    .extracting("goodsId")
                    .containsExactly("g_ticket")
                    .doesNotContain("g_points");
            })
            .verifyComplete();
    }

    /**
     * type 留空时旧逻辑会回落为积分充值，是充值通道的隐蔽入口，
     * 即使配了 points 数值也必须被拒绝。
     */
    @Test
    void shouldRejectGoodsWithMissingType() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_legacy","name":"旧商品","price":100,"points":100}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> assertThat(config.getGoods()).isEmpty())
            .verifyComplete();
    }

    /** outfit 缺少 style（装扮 key）时无法发货，不得下发为可购买商品。 */
    @Test
    void shouldRejectOutfitWithoutStyle() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_outfit","name":"头像框","price":900,"type":"outfit"}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> assertThat(config.getGoods()).isEmpty())
            .verifyComplete();
    }

    /** 未配置 payConfig 时应为关闭状态且不下发任何商品。 */
    @Test
    void shouldBeDisabledWithoutConfig() {
        when(settingFetcher.get("payConfig")).thenReturn(Mono.empty());

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.isEnabled()).isFalse();
                assertThat(config.getGoods()).isEmpty();
            })
            .verifyComplete();
    }

    /**
     * enabled=false 时即使配置了合法道具也不下发。
     *
     * <p>样例必须用<b>当前在售</b>的合法类型（content_ticket）：若用已被过滤的
     * dashboard 或 publish_ticket，则即使 enabled 闸门失效、商品也仍会被类型过滤掉，
     * 测试会「假通过」，无法真正验证开关是否生效。</p>
     */
    @Test
    void shouldNotExposeGoodsWhenDisabled() throws Exception {
        stubPayConfig("""
            {"enabled":false,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":1}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.isEnabled()).isFalse();
                assertThat(config.getGoods()).isEmpty();
            })
            .verifyComplete();
    }

    // ==================== query_order 订单状态判定 ====================
    //
    // 官方枚举（虚拟支付 - 查询创建的订单 Res.order.status）：
    //   0 订单初始化 / 1 订单创建成功 / 2 已支付待发货 / 3 发货中 / 4 已发货
    //   5 已退款 / 6 已关闭 / 7 退款失败 / 8 用户退款完成
    //   9 回收广告金完成 / 10 分账回退完成
    // 仅 2/3/4 允许发货。旧实现用 status >= 2 && != 5 && != 6 的宽泛判定，
    // 会把 7/8/9/10 误判为已支付并发货，造成资损，以下用例锁定该防线。

    @Test
    void shouldDeliverOnlyForPaidStatuses() {
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(2, "MP1")).isTrue();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(3, "MP1")).isTrue();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(4, "MP1")).isTrue();
    }

    /** 0/1 为未支付状态，不得发货。 */
    @Test
    void shouldNotDeliverForUnpaidStatuses() {
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(0, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(1, "MP1")).isFalse();
    }

    /** 5/6 为已退款、已关闭，不得发货。 */
    @Test
    void shouldNotDeliverForRefundedOrClosedStatuses() {
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(5, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(6, "MP1")).isFalse();
    }

    /**
     * 8 = 用户退款完成，是最危险的漏判点：钱已退给用户，若仍发货即为纯资损。
     * 7/9/10 同样属于非可发货终态。
     */
    @Test
    void shouldNotDeliverForRefundCompletedAndOtherTerminalStatuses() {
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(7, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(8, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(9, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(10, "MP1")).isFalse();
    }

    /** 官方枚举之外的未知状态一律保守不发货，避免未来新增状态被默认放行。 */
    @Test
    void shouldNotDeliverForUnknownStatuses() {
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(-1, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(11, "MP1")).isFalse();
        assertThat(VirtualPayServiceImpl.isDeliverableStatus(99, "MP1")).isFalse();
    }

    // ==================== 装扮商品：下单前校验（单一装扮定义源） ====================
    //
    // 装扮商品的 style 存的是「装扮商店的 key」。站长把 key 填错、或该装扮已被删除时，
    // 身份中心渲染不出这一行（装扮列表以配置为数据源），用户就是「付了钱看不到」。
    // 因此 key 必须在下单前校验已登记；已拥有的装扮也必须拦在付款之前。

    private static final String OUTFIT_PAY_CONFIG = """
        {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
          {"goodsId":"g_outfit","name":"金色头像框","price":600,"type":"outfit","value":1,"style":"avatar_gold"}
        ]}
        """;

    private static final String OUTFIT_LEVEL_CONFIG = """
        {"enabled":true,"levels":[],"titles":[],
         "outfits":[
           {"key":"avatar_gold","name":"金色头像框","type":"avatar_frame","points":200,"durationDays":0,"style":"gold"}
         ]}
        """;

    private void stubLevelConfig(String json) throws Exception {
        when(settingFetcher.get("levelConfig")).thenReturn(Mono.just(MAPPER.readTree(json)));
    }

    private static CreatePayOrderRequest buyRequest(String goodsId) {
        var request = new CreatePayOrderRequest();
        request.setGoodsId(goodsId);
        return request;
    }

    private static PayOrder outfitOrder(String outfitKey) {
        var order = PayOrderPersistence.newOrder("o1", "wx-user-1", "OT1234567890abcdefg",
            "g_outfit", "金色头像框", 600L, 1, 0, "OT1234567890abcdefg");
        order.setGoodsType("outfit");
        order.setGoodsStyle(outfitKey);
        return order;
    }

    private static UserPoints accountWithOwned(String outfitOwnedJson) {
        var account = new UserPoints();
        account.setOutfitOwned(outfitOwnedJson);
        return account;
    }

    /** key 未在装扮商店登记：必须在付款前拦下，否则用户付钱后装扮列表里根本不会出现这一行。 */
    @Test
    void shouldRejectUnregisteredOutfitGoodsOnCreateOrder() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_outfit","name":"森林绿主题","price":600,"type":"outfit","value":1,"style":"ghost_skin"}
            ]}
            """);
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);

        StepVerifier.create(virtualPayService.createOrder("o1", buyRequest("g_outfit")))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(ex).hasMessageContaining("装扮配置错误");
            })
            .verify();
    }

    /** 装扮商品未登记时也会从商品列表里隐藏，避免用户点进去才看到报错。 */
    @Test
    void shouldHideUnregisteredOutfitGoodsFromPublicConfig() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_outfit","name":"森林绿主题","price":600,"type":"outfit","value":1,"style":"ghost_skin"},
              {"goodsId":"g_ticket","name":"阅读券","price":300,"type":"content_ticket","value":1}
            ]}
            """);
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> assertThat(config.getGoods())
                .extracting("goodsId")
                .containsExactly("g_ticket"))
            .verifyComplete();
    }

    /** 已拥有（有效期内）不得重复购买：钱照收但权益不会重复发，属于纯客诉。 */
    @Test
    void shouldRejectDuplicateOutfitPurchaseOnCreateOrder() throws Exception {
        stubPayConfig(OUTFIT_PAY_CONFIG);
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);
        when(pointsPersistence.getOrCreateAccount("o1"))
            .thenReturn(Mono.just(accountWithOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":1,\"expireAt\":null}]")));

        StepVerifier.create(virtualPayService.createOrder("o1", buyRequest("g_outfit")))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(ex).hasMessageContaining("已拥有");
            })
            .verify();
    }

    /** 限时装扮过期后应视为未拥有，可以重新购买（不能拦成「已拥有」）。 */
    @Test
    void shouldAllowRepurchaseWhenPreviousOutfitExpired() throws Exception {
        stubPayConfig(OUTFIT_PAY_CONFIG);
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);
        var expiredAt = Instant.now().getEpochSecond() - 60;
        when(pointsPersistence.getOrCreateAccount("o1"))
            .thenReturn(Mono.just(accountWithOwned(
                "[{\"key\":\"avatar_gold\",\"boughtAt\":1,\"expireAt\":" + expiredAt + "}]")));
        // 走到「取微信用户」这一步即说明拥有校验已放行
        when(wxUserService.fetchByOpenid("o1")).thenReturn(Mono.empty());

        StepVerifier.create(virtualPayService.createOrder("o1", buyRequest("g_outfit")))
            .expectErrorSatisfies(ex -> assertThat(ex)
                .isInstanceOf(WxAuthException.class)
                .hasMessageContaining("用户不存在"))
            .verify();
    }

    // ==================== 重复购买的终态处理（资损/客诉防线） ====================
    //
    // 用户已付款、但权益已拥有（并发下单的竞态）时，发货不能成功也不能一直重试：
    // 旧实现直接抛异常且抛在幂等流水写入之前，订单永久停留 pending，
    // 被 5 分钟兜底查单无限重试，用户既拿不到权益也没有退款入口。
    // 现改为置终态 needs_refund + 备注 + ERROR 日志，由站长人工退款。

    @Test
    void deliverShouldMarkOrderNeedsRefundWhenOutfitAlreadyOwned() throws Exception {
        stubLevelConfig(OUTFIT_LEVEL_CONFIG);
        when(pointsPersistence.getOrCreateAccount("o1"))
            .thenReturn(Mono.just(accountWithOwned("[{\"key\":\"avatar_gold\",\"boughtAt\":1,\"expireAt\":null}]")));
        when(pointsPersistence.findRecordByEarnKey(anyString())).thenReturn(Mono.empty());
        when(payOrderPersistence.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var remote = new VirtualPayServiceImpl.RemoteOrderStatus(true, 2, Instant.now(),
            "wx-order-1", "tx-1", "mch-1");

        StepVerifier.create(virtualPayService.doDeliver(outfitOrder("avatar_gold"), remote))
            .assertNext(updated -> {
                // 关键：不能停留在 pending（否则会被兜底查单无限重试）
                assertThat(updated.getStatus()).isNotEqualTo("pending");
                assertThat(updated.getStatus()).isEqualTo("needs_refund");
                assertThat(updated.getDelivered()).isFalse();
                assertThat(updated.getRemark()).contains("人工退款");
                // 微信平台单号必须落库：人工退款要凭它操作
                assertThat(updated.getWxOrderId()).isEqualTo("wx-order-1");
            })
            .verifyComplete();

        // 未发放任何权益，因此不应写发货流水
        verify(pointsPersistence, never()).appendRecord(any());
    }

    /** 终态订单要能被前端读懂：查单响应必须带上明确原因，而不是让用户对着超时提示猜。 */
    @Test
    void queryOrderShouldExplainNeedsRefundStatus() throws Exception {
        var order = outfitOrder("avatar_gold");
        order.setStatus("needs_refund");
        when(payOrderPersistence.findByOutTradeNo("OT1234567890abcdefg")).thenReturn(Mono.just(order));

        var request = new QueryPayOrderRequest();
        request.setOutTradeNo("OT1234567890abcdefg");

        StepVerifier.create(virtualPayService.queryOrder("o1", request))
            .assertNext(response -> {
                assertThat(response.getDelivered()).isFalse();
                assertThat(response.getStatus()).isEqualTo("needs_refund");
                assertThat(response.getMessage()).contains("退款");
            })
            .verifyComplete();
    }

    /** 限时装扮：发货时必须按配置的 durationDays 写入到期时间（0=永久）。 */
    @Test
    void grantOutfitShouldRecordExpireAtForLimitedOutfit() throws Exception {
        stubLevelConfig("""
            {"enabled":true,"levels":[],"titles":[],
             "outfits":[
               {"key":"avatar_limited","name":"限时头像框","type":"avatar_frame","points":200,"durationDays":7,"style":"gold"}
             ]}
            """);
        var account = new UserPoints();
        when(pointsPersistence.getOrCreateAccount("o1")).thenReturn(Mono.just(account));
        when(pointsPersistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(pointsPersistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(virtualPayService.grantOutfit(outfitOrder("avatar_limited"), "ek-1",
            "wx-user-1", "avatar_limited"))
            .verifyComplete();

        var owned = OwnedOutfitParser.parse(account.getOutfitOwned());
        assertThat(owned).hasSize(1);
        assertThat(owned.get(0).key()).isEqualTo("avatar_limited");
        assertThat(owned.get(0).expireAt()).isNotNull();
        var expected = Instant.now().getEpochSecond() + 7 * 86400L;
        assertThat(owned.get(0).expireAt()).isBetween(expected - 60, expected + 60);
    }
}
