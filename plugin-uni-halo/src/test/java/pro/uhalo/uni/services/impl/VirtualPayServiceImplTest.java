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
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.MemberConfigDTO;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.services.MembershipService;
import pro.uhalo.uni.services.TicketRewardService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.WxAccessTokenProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private WxAccessTokenProvider wxAccessTokenProvider;

    @Mock
    private TicketRewardService ticketRewardService;

    @Mock
    private MembershipService membershipService;

    @Mock
    private AiContentEntitlementService aiContentEntitlementService;

    @InjectMocks
    private VirtualPayServiceImpl virtualPayService;

    /** 赠券配置：默认按未配置处理（不赠券）；首单礼分支由专门用例覆盖。 */
    private void stubNoTicketReward() {
        org.mockito.Mockito.lenient().when(ticketRewardService.loadConfig()).thenReturn(Mono.empty());
    }

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

    /** 阅读券订单（首单礼挂在任意成功订单上，这里用券单验证）。 */
    private static PayOrder ticketOrder() {
        var order = PayOrderPersistence.newOrder("o1", "wx-user-1", "OT1234567890abcdefg",
            "g_ticket", "阅读券 x5", 600L, 1, 0, "OT1234567890abcdefg");
        order.setGoodsType("content_ticket");
        order.setGoodsValue(5);
        return order;
    }

    /** 赠券配置：开启首单礼赠送 2 张券。 */
    private void stubFirstPurchaseReward(int tickets) {
        when(ticketRewardService.loadConfig()).thenReturn(Mono.just(
            pro.uhalo.uni.dto.TicketRewardConfigDTO.builder()
                .firstPurchaseTickets(tickets).build()));
        when(ticketRewardService.grant(anyString(), anyInt(), anyString(), anyString(),
            anyString(), any(), any())).thenReturn(Mono.just(tickets));
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
    // ==================== 首单礼（转化漏斗最有效的一环） ====================

    /** 首单礼：第一笔成功订单在发货后额外赠券。 */
    @Test
    void deliverShouldGrantFirstPurchaseBonusAfterGoods() throws Exception {
        stubFirstPurchaseReward(2);
        when(pointsPersistence.findRecordByEarnKey(anyString())).thenReturn(Mono.empty());
        when(pointsPersistence.getOrCreateAccount("o1"))
            .thenReturn(Mono.just(new UserPoints()));
        when(pointsPersistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(pointsPersistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(payOrderPersistence.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var remote = new VirtualPayServiceImpl.RemoteOrderStatus(true, 2, Instant.now(),
            "wx-order-1", "tx-1", "mch-1");

        StepVerifier.create(virtualPayService.doDeliver(ticketOrder(), remote))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        // 首单礼的幂等键固定为 openid + 动作 + "first"，不依赖回查历史订单
        verify(ticketRewardService).grant(eq("o1"), eq(2), eq("ticket_first_purchase"),
            anyString(), eq("o1:ticket_first_purchase:first"), any(), any());
    }

    /** 首单礼配置为 0 时不应发起任何赠券调用。 */
    @Test
    void deliverShouldSkipFirstPurchaseBonusWhenNotConfigured() throws Exception {
        stubNoTicketReward();
        when(pointsPersistence.findRecordByEarnKey(anyString())).thenReturn(Mono.empty());
        when(pointsPersistence.getOrCreateAccount("o1"))
            .thenReturn(Mono.just(new UserPoints()));
        when(pointsPersistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(pointsPersistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(payOrderPersistence.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var remote = new VirtualPayServiceImpl.RemoteOrderStatus(true, 2, Instant.now(),
            "wx-order-1", "tx-1", "mch-1");

        StepVerifier.create(virtualPayService.doDeliver(ticketOrder(), remote))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        verify(ticketRewardService, never()).grant(anyString(), anyInt(), anyString(),
            anyString(), anyString(), any(), any());
    }
    // ==================== 会员卡（单次购买的有效期权益） ====================

    private static PayOrder membershipOrder(int days) {
        var order = PayOrderPersistence.newOrder("o1", "wx-user-1", "OT1234567890abcdefg",
            "g_member", "会员卡", 1900L, 1, 0, "OT1234567890abcdefg");
        order.setGoodsType("membership");
        order.setGoodsValue(days);
        return order;
    }

    /** 套餐：一条商品含阅读券 + 会员天数两个子项。 */
    private static PayOrder bundleOrder() {
        var order = PayOrderPersistence.newOrder("o1", "wx-user-1", "OT1234567890abcdefg",
            "g_bundle", "超值礼包", 1990L, 1, 0, "OT1234567890abcdefg");
        order.setGoodsType("bundle");
        order.setGoodsItems("[{\"type\":\"content_ticket\",\"value\":3},"
            + "{\"type\":\"membership\",\"value\":15}]");
        return order;
    }

    private void stubMemberConfig(int defaultDays) {
        when(membershipService.loadConfig()).thenReturn(Mono.just(MemberConfigDTO.builder()
            .enabled(true).defaultDays(defaultDays).freeContent(true).bonusTickets(0).build()));
    }

    /** 发货链路最小桩：订单可重试（幂等流水不存在）且终态可落库。 */
    private void stubDeliverBase() {
        when(pointsPersistence.findRecordByEarnKey(anyString())).thenReturn(Mono.empty());
        when(payOrderPersistence.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    /** 发货基础设施的公共桩：在最小桩之上再开放账户读写（成功发货路径用）。 */
    private UserPoints stubDelivery(UserPoints account) {
        stubDeliverBase();
        when(pointsPersistence.getOrCreateAccount("o1")).thenReturn(Mono.just(account));
        when(pointsPersistence.appendRecord(any())).thenReturn(Mono.just(new PointsRecord()));
        when(pointsPersistence.saveAccount(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        return account;
    }

    private static VirtualPayServiceImpl.RemoteOrderStatus remotePaid() {
        return new VirtualPayServiceImpl.RemoteOrderStatus(true, 2, Instant.now(),
            "wx-order-1", "tx-1", "mch-1");
    }

    /** 会员卡发货：按配置天数延长到期时间。 */
    @Test
    void deliverShouldExtendMembershipExpiry() throws Exception {
        stubNoTicketReward();
        stubMemberConfig(30);
        var account = stubDelivery(new UserPoints());

        StepVerifier.create(virtualPayService.doDeliver(membershipOrder(30), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        var expected = Instant.now().plus(30, ChronoUnit.DAYS);
        assertThat(account.getMemberExpireAt()).isNotNull();
        assertThat(account.getMemberExpireAt()).isBetween(
            expected.minusSeconds(60), expected.plusSeconds(60));
    }

    /**
     * 有效期内续费：必须在<b>当前到期时间</b>上叠加，而不是从当下重新计时。
     * 否则用户会白白损失剩余天数，是实打实的客诉来源。
     */
    @Test
    void deliverShouldStackMembershipOnExistingExpiry() throws Exception {
        stubNoTicketReward();
        stubMemberConfig(30);
        var account = new UserPoints();
        account.setMemberExpireAt(Instant.now().plus(10, ChronoUnit.DAYS));
        stubDelivery(account);

        StepVerifier.create(virtualPayService.doDeliver(membershipOrder(30), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        // 10 天剩余 + 30 天新购 = 约 40 天
        var expected = Instant.now().plus(40, ChronoUnit.DAYS);
        assertThat(account.getMemberExpireAt()).isBetween(
            expected.minusSeconds(60), expected.plusSeconds(60));
    }

    /** 会员卡配置未开启：宁可转人工退款，也不能让用户付了钱什么都没有。 */
    @Test
    void deliverShouldMarkNeedsRefundWhenMembershipDisabled() {
        stubNoTicketReward();
        when(membershipService.loadConfig()).thenReturn(Mono.just(
            MemberConfigDTO.builder().enabled(false).defaultDays(30).build()));
        // 只给最小桩：转终态的分支根本不读账户、不写发货流水，
        // 多桩会掩盖「它其实没碰账户」这一关键事实
        stubDeliverBase();

        StepVerifier.create(virtualPayService.doDeliver(membershipOrder(30), remotePaid()))
            .assertNext(updated -> {
                assertThat(updated.getStatus()).isEqualTo("needs_refund");
                assertThat(updated.getRemark()).contains("人工退款");
            })
            .verifyComplete();

        // 未发放任何权益，也不该写发货流水
        verify(pointsPersistence, never()).appendRecord(any());
    }

    // ==================== AI 定制内容（单次购买的有效期权益） ====================

    private static PayOrder aiContentOrder(int days) {
        var order = PayOrderPersistence.newOrder("o1", "wx-user-1", "OT1234567890abcdefg",
            "g_ai_content", "AI 定制内容", 990L, 1, 0, "OT1234567890abcdefg");
        order.setGoodsType("ai_content");
        order.setGoodsValue(days);
        return order;
    }

    private void stubAiContentConfig(boolean enabled) {
        when(aiContentEntitlementService.loadConfig()).thenReturn(Mono.just(
            AiContentConfigDTO.builder().enabled(enabled).aiIntroEnabled(true).build()));
    }

    /** 发货：按商品填的天数延长权益到期时间。 */
    @Test
    void deliverShouldExtendAiContentExpiry() throws Exception {
        stubNoTicketReward();
        stubAiContentConfig(true);
        var account = stubDelivery(new UserPoints());

        StepVerifier.create(virtualPayService.doDeliver(aiContentOrder(30), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        var expected = Instant.now().plus(30, ChronoUnit.DAYS);
        assertThat(account.getAiContentExpireAt()).isNotNull();
        assertThat(account.getAiContentExpireAt()).isBetween(
            expected.minusSeconds(60), expected.plusSeconds(60));
    }

    /** 续费叠加：与会员卡同一口径，不能白吃用户剩余天数。 */
    @Test
    void deliverShouldStackAiContentOnExistingExpiry() throws Exception {
        stubNoTicketReward();
        stubAiContentConfig(true);
        var account = new UserPoints();
        account.setAiContentExpireAt(Instant.now().plus(10, ChronoUnit.DAYS));
        stubDelivery(account);

        StepVerifier.create(virtualPayService.doDeliver(aiContentOrder(30), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        var expected = Instant.now().plus(40, ChronoUnit.DAYS);
        assertThat(account.getAiContentExpireAt()).isBetween(
            expected.minusSeconds(60), expected.plusSeconds(60));
    }

    /** 道具数量填 0 = 永久买断：用远期时间表示，权益判定与会员共用一套。 */
    @Test
    void deliverWithZeroDaysShouldGrantPermanentAiContent() throws Exception {
        stubNoTicketReward();
        stubAiContentConfig(true);
        var account = stubDelivery(new UserPoints());

        StepVerifier.create(virtualPayService.doDeliver(aiContentOrder(0), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        assertThat(account.getAiContentExpireAt())
            .isEqualTo(pro.uhalo.uni.utils.AiContentConfigHelper.PERMANENT_EXPIRE_AT);
    }

    /** 配置未开启：宁可转人工退款，也不能让用户付了钱什么都没有。 */
    @Test
    void deliverShouldMarkNeedsRefundWhenAiContentDisabled() {
        stubNoTicketReward();
        stubAiContentConfig(false);
        stubDeliverBase();

        StepVerifier.create(virtualPayService.doDeliver(aiContentOrder(30), remotePaid()))
            .assertNext(updated -> {
                assertThat(updated.getStatus()).isEqualTo("needs_refund");
                assertThat(updated.getRemark()).contains("人工退款");
            })
            .verifyComplete();

        verify(pointsPersistence, never()).appendRecord(any());
    }

    /** 幂等：流水已存在（重复推送/轮询）时不再叠加天数。 */
    @Test
    void deliverShouldBeIdempotentForAiContent() throws Exception {
        stubNoTicketReward();
        // 注意：switchIfEmpty 的参数是「先构造、后订阅」，所以发货分支的 Mono 一定会被构造，
        // 里面第一步读配置就必须有桩（真实 bean 不会返回 null，只有 mock 会）。
        stubAiContentConfig(true);
        when(pointsPersistence.findRecordByEarnKey(anyString()))
            .thenReturn(Mono.just(new PointsRecord()));
        when(payOrderPersistence.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(virtualPayService.doDeliver(aiContentOrder(30), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        verify(pointsPersistence, never()).saveAccount(any());
    }

    // ==================== 套餐（一条商品含多种权益） ====================

    /** 套餐发货：每个子项各自发放，券与会员天数都要到账。 */
    @Test
    void deliverShouldGrantEveryBundleItem() throws Exception {
        stubNoTicketReward();
        stubMemberConfig(30);
        var account = stubDelivery(new UserPoints());

        StepVerifier.create(virtualPayService.doDeliver(bundleOrder(), remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("delivered"))
            .verifyComplete();

        assertThat(account.getContentTickets()).isEqualTo(3);
        var expected = Instant.now().plus(15, ChronoUnit.DAYS);
        assertThat(account.getMemberExpireAt()).isBetween(
            expected.minusSeconds(60), expected.plusSeconds(60));

        // 子项 earnKey 带下标，因此各自幂等：重试不会重复发放已成功的子项
        var captor = org.mockito.ArgumentCaptor.forClass(PointsRecord.class);
        verify(pointsPersistence, org.mockito.Mockito.atLeast(2)).appendRecord(captor.capture());
        var earnKeys = captor.getAllValues().stream()
            .map(PointsRecord::getEarnKey)
            .filter(java.util.Objects::nonNull)
            .toList();
        // 主订单 earnKey 为「openid:purchase:订单号」，子项在其后加下标
        assertThat(earnKeys).contains("o1:purchase:OT1234567890abcdefg:0",
            "o1:purchase:OT1234567890abcdefg:1");
    }

    /** 套餐子项配置缺失：必须判为发货失败并转人工，绝不静默发放部分权益。 */
    @Test
    void deliverShouldMarkNeedsRefundWhenBundleItemsMissing() {
        stubNoTicketReward();
        var order = bundleOrder();
        order.setGoodsItems(null);
        stubDeliverBase();

        StepVerifier.create(virtualPayService.doDeliver(order, remotePaid()))
            .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo("needs_refund"))
            .verifyComplete();
    }
    // ==================== 限时截止时间解析（设置页日期时间选择器 → Unix 秒） ====================
    //
    // 这段解析一旦出错，表现是「活动神秘消失」：界面上配好了、前端什么都不显示，
    // 从 UI 完全看不出原因。因此把各取值形态都钉住。

    /** 选择器产出的是不带时区的本地时间，必须按东八区解释（按 UTC 会整体偏 8 小时）。 */
    @Test
    void promoEndsAtShouldInterpretLocalDateTimeAsShanghaiTime() throws Exception {
        var item = MAPPER.readTree("""
            {"goodsId":"g1","promoEndsAt":"2030-06-01T12:00"}
            """);
        var expected = java.time.LocalDateTime.parse("2030-06-01T12:00")
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();

        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(item)).isEqualTo(expected);
    }

    /** 带秒的本地时间同样支持（选择器可能带秒）。 */
    @Test
    void promoEndsAtShouldAcceptLocalDateTimeWithSeconds() throws Exception {
        var item = MAPPER.readTree("""
            {"goodsId":"g1","promoEndsAt":"2030-06-01T12:00:30"}
            """);
        var expected = java.time.LocalDateTime.parse("2030-06-01T12:00:30")
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();

        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(item)).isEqualTo(expected);
    }

    /** 带 Z 的完整时间戳：以取值自带的时区为准，不再按东八区偏移。 */
    @Test
    void promoEndsAtShouldRespectExplicitTimezone() throws Exception {
        var item = MAPPER.readTree("""
            {"goodsId":"g1","promoEndsAt":"2030-06-01T12:00:00.000Z"}
            """);
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(item))
            .isEqualTo(java.time.Instant.parse("2030-06-01T12:00:00Z").getEpochSecond());
    }

    /** 只填日期：按当天结束为止（23:59:59）。 */
    @Test
    void promoEndsAtShouldTreatDateOnlyAsEndOfDay() throws Exception {
        var item = MAPPER.readTree("""
            {"goodsId":"g1","promoEndsAt":"2030-06-01"}
            """);
        var expected = java.time.LocalDate.parse("2030-06-01").atTime(23, 59, 59)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();

        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(item)).isEqualTo(expected);
    }

    /** 兼容早期直接填 Unix 秒的配置：不能让已配好的活动失效。 */
    @Test
    void promoEndsAtShouldStillAcceptLegacyEpochSeconds() throws Exception {
        var future = java.time.Instant.now().plusSeconds(86400).getEpochSecond();
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":" + future + "}")))
            .isEqualTo(future);
        // 字符串形式的数字同样接受（配置序列化后可能变成字符串）
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":\"" + future + "\"}")))
            .isEqualTo(future);
    }

    /** 已过期、留空、无法识别的值都按「没有活动」处理，且不能抛异常。 */
    @Test
    void promoEndsAtShouldIgnoreExpiredOrUnparseableValues() throws Exception {
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":\"2020-01-01T00:00\"}")))
            .isNull();
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":\"\"}")))
            .isNull();
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":0}")))
            .isNull();
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\"}")))
            .isNull();
        assertThat(VirtualPayServiceImpl.resolvePromoEndsAt(
            MAPPER.readTree("{\"goodsId\":\"g1\",\"promoEndsAt\":\"下个月底\"}")))
            .isNull();
    }

    /** 端到端：配置里的日期时间字符串要一路走到下发的商品上。 */
    @Test
    void goodsShouldCarryParsedPromoEndFromHumanReadableConfig() throws Exception {
        stubPayConfig("""
            {"enabled":true,"offerId":"offer-1","appKey":"key-1","env":0,"goods":[
              {"goodsId":"g_ticket","name":"阅读券 x5","price":600,"type":"content_ticket",
               "value":5,"promoEndsAt":"2030-06-01T12:00","originalPrice":3000}
            ]}
            """);

        StepVerifier.create(virtualPayService.getPublicConfig())
            .assertNext(config -> {
                assertThat(config.isEnabled()).isTrue();
                assertThat(config.getGoods()).hasSize(1);
                var goods = config.getGoods().get(0);
                assertThat(goods.getPromoEndsAt()).isNotNull();
                assertThat(goods.getOriginalPrice()).isEqualTo(3000L);
                var expectedEnd = java.time.LocalDateTime.parse("2030-06-01T12:00")
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
                assertThat(goods.getPromoEndsAt()).isEqualTo(expectedEnd);
            })
            .verifyComplete();
    }

    // ==================== 待支付订单的有效期（15 分钟） ====================
    //
    // 用户侧「我的订单」此前会把很久以前没付款的测试单一直显示成「待支付」，
    // 看起来像一直挂着没完成。规则：超过有效期未付款即「已失效」。
    //
    // 关键设计：失效状态**只在下发时派生、不落库**——用户可能在有效期内付了款、
    // 而微信的发货推送迟到几分钟才到，把库里改成终态就可能让那笔权益发不出去。

    private static PayOrder order(String status, Instant createTime) {
        var order = new PayOrder();
        order.setOpenid("openid-1");
        order.setOutTradeNo("OT-1");
        order.setGoodsId("g_ticket");
        order.setGoodsName("阅读券 x5");
        order.setPrice(600L);
        order.setQuantity(1);
        order.setGoodsType("content_ticket");
        order.setStatus(status);
        order.setDelivered(false);
        order.setCreateTime(createTime);
        order.setUpdateTime(createTime);
        return order;
    }

    /** 刚下单 1 分钟、以及刚好在有效期内（第 14 分钟）的都还算待支付。 */
    @Test
    void pendingOrderShouldStayPendingWithinValidity() {
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("pending", Instant.now().minus(1, ChronoUnit.MINUTES)))).isFalse();
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("pending", Instant.now().minus(14, ChronoUnit.MINUTES)))).isFalse();
    }

    /** 超过有效期（第 16 分钟）即失效。 */
    @Test
    void pendingOrderShouldExpireAfterValidity() {
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("pending", Instant.now().minus(16, ChronoUnit.MINUTES)))).isTrue();
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("pending", Instant.now().minus(3, ChronoUnit.DAYS)))).isTrue();
    }

    /** 终态订单不受时间影响；没有创建时间的旧订单按「不失效」处理（不凭空空判死）。 */
    @Test
    void expiryShouldNotAffectTerminalOrLegacyOrders() {
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("delivered", Instant.now().minus(3, ChronoUnit.DAYS)))).isFalse();
        assertThat(VirtualPayServiceImpl.isPendingExpired(
            order("needs_refund", Instant.now().minus(3, ChronoUnit.DAYS)))).isFalse();
        assertThat(VirtualPayServiceImpl.isPendingExpired(order("pending", null))).isFalse();
        assertThat(VirtualPayServiceImpl.isPendingExpired(null)).isFalse();
    }

    /** 列表里：过期的待支付显示「已失效」，未过期的照旧「待支付」，已到账的不受影响。 */
    @Test
    void myOrdersShouldMarkStalePendingAsExpired() {
        when(payOrderPersistence.listByOpenid("openid-1")).thenReturn(Flux.just(
            order("pending", Instant.now().minus(30, ChronoUnit.MINUTES)),
            order("pending", Instant.now().minus(2, ChronoUnit.MINUTES)),
            order("delivered", Instant.now().minus(3, ChronoUnit.DAYS))));

        StepVerifier.create(virtualPayService.listMyOrders("openid-1", 0, 20))
            .assertNext(res -> {
                assertThat(res.getItems()).hasSize(3);
                // 按创建时间倒序：最新的在前
                assertThat(res.getItems().get(0).getStatus()).isEqualTo("pending");
                assertThat(res.getItems().get(0).getStatusLabel()).contains("15 分钟");
                assertThat(res.getItems().get(1).getStatus()).isEqualTo("expired");
                assertThat(res.getItems().get(1).getStatusLabel()).contains("已失效");
                assertThat(res.getItems().get(2).getStatus()).isEqualTo("delivered");
                assertThat(res.getItems().get(2).getStatusLabel()).isEqualTo("已到账");
            })
            .verifyComplete();
    }

    /**
     * 单笔查单遇到过期订单：直接返回已失效，且**不去微信查单**——
     * 那张单早已不能用，白跑一次接口还会让用户白等。
     */
    @Test
    void queryExpiredOrderShouldNotHitWechat() {
        var stale = order("pending", Instant.now().minus(30, ChronoUnit.MINUTES));
        when(payOrderPersistence.findByOutTradeNo("OT-1")).thenReturn(Mono.just(stale));

        var request = new QueryPayOrderRequest();
        request.setOutTradeNo("OT-1");

        StepVerifier.create(virtualPayService.queryOrder("openid-1", request))
            .assertNext(res -> {
                assertThat(res.getStatus()).isEqualTo("expired");
                assertThat(res.getMessage()).contains("已失效");
                assertThat(res.getDelivered()).isFalse();
                assertThat(res.getGoodsName()).isEqualTo("阅读券 x5");
            })
            .verifyComplete();

        // 完全没碰 token provider = 没走远程查单（查单第一步就要取 token）
        verifyNoInteractions(wxAccessTokenProvider);
    }
}
