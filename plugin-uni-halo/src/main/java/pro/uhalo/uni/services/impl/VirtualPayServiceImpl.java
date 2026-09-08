package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.dto.CreatePayOrderRequest;
import pro.uhalo.uni.dto.CreatePayOrderResponse;
import pro.uhalo.uni.dto.PayDataDTO;
import pro.uhalo.uni.dto.PayGoodsDTO;
import pro.uhalo.uni.dto.PayPublicConfigDTO;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderResponse;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.VirtualPayService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.TokenManager;
import pro.uhalo.uni.utils.WxRequestParser;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.XPaySigner;
import pro.uhalo.uni.utils.XmlMessageParser;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟支付核心服务（道具直购）。
 *
 * <p>实现依据（官方文档）：</p>
 * <ul>
 *   <li>下单：服务器生成 signData + paySig + signature（mode=short 道具直购），前端调用基础库 wx.requestVirtualPayment</li>
 *   <li>发货：以 xpay_goods_deliver_notify 推送为主，按 earnKey 幂等，返回 &lt;xml&gt;&lt;ErrCode&gt;0&lt;/ErrCode&gt;&lt;/xml&gt;</li>
 *   <li>道具类型：points=旧积分充值（兼容已配置商品）；dashboard=数据看板当日高级版；publish_ticket=发布券；outfit=装扮解锁</li>
 *   <li>兜底：推送丢失时定时调用 /xpay/query_order 查单补发</li>
 *   <li>签名：pay_sig = HMAC-SHA256(AppKey, uri + "&" + post_body)；signature = HMAC-SHA256(sessionKey, post_body)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VirtualPayServiceImpl implements VirtualPayService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final WebClient WEB_CLIENT = WebClient.builder().build();

    private static final String PAY_CONFIG_GROUP = "payConfig";
    private static final String WX_APP_CONFIG_GROUP = "wxAppConfig";
    private static final String APP_CONFIG_GROUP = "appConfig";

    private static final String C_D_REQUEST_URI = "requestVirtualPayment";
    private static final String QUERY_ORDER_API_PATH = "/xpay/query_order";
    private static final String QUERY_ORDER_URL = "https://api.weixin.qq.com/wxa/business/xpay/query_order";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 支付类型：short_series_goods = 道具直购（官方合法值，见 wx.requestVirtualPayment 文档） */
    private static final String MODE_SHORT_SERIES_GOODS = "short_series_goods";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_DELIVERED = "delivered";
    private static final String STATUS_CLOSED = "closed";
    private static final String EVENT_DELIVER_NOTIFY = "xpay_goods_deliver_notify";
    private static final String RECHARGE_ACTION = "recharge";

    private static final int DEFAULT_ENV = 0;
    private static final int DELIVERY_RETRY_INTERVAL_SECONDS = 30;

    private final ReactiveSettingFetcher settingFetcher;
    private final PayOrderPersistence payOrderPersistence;
    private final PointsPersistence pointsPersistence;
    private final WxUserService wxUserService;
    private final TokenManager tokenManager;

    private final ConcurrentHashMap<String, Object> deliveryLocks = new ConcurrentHashMap<>();

    @Override
    public Mono<PayPublicConfigDTO> getPublicConfig() {
        return loadConfig()
            .map(config -> PayPublicConfigDTO.builder()
                .enabled(config.enabled())
                .goods(config.enabled() ? config.goods() : List.of())
                .build());
    }

    @Override
    public Mono<CreatePayOrderResponse> createOrder(String openid, CreatePayOrderRequest request) {
        var normalizedOpenid = requireOpenid(openid);
        return loadConfig()
            .flatMap(config -> {
                ensurePayEnabled(config);
                var goodsId = resolveGoodsId(request);
                var goods = resolveGoods(config, goodsId);
                if (!StringUtils.hasText(config.offerId())) {
                    return Mono.error(new WxAuthException("虚拟支付 OfferID 未配置"));
                }
                if (!StringUtils.hasText(config.appKey())) {
                    return Mono.error(new WxAuthException("虚拟支付 AppKey 未配置"));
                }
                return wxUserService.fetchByOpenid(normalizedOpenid)
                    .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                    .flatMap(wxUser -> {
                        if (!StringUtils.hasText(wxUser.getSessionKey())) {
                            return Mono.error(new WxAuthException("登录态已失效，请重新登录"));
                        }
                        return createOrderRecord(config, goods, normalizedOpenid, wxUser);
                    });
            });
    }

    private Mono<CreatePayOrderResponse> createOrderRecord(PayConfigContext config, PayGoodsDTO goods,
        String openid, WxUser wxUser) {
        var quantity = 1;
        var env = config.env();
        return createOrderAttempt(config, goods, openid, wxUser, quantity, env, 0);
    }

    private Mono<CreatePayOrderResponse> createOrderAttempt(PayConfigContext config, PayGoodsDTO goods,
        String openid, WxUser wxUser, int quantity, int env, int attempt) {
        if (attempt >= 3) {
            return Mono.error(new WxAuthException("下单失败，请稍后重试"));
        }
        var outTradeNo = XPaySigner.nextOutTradeNo();
        var order = PayOrderPersistence.newOrder(openid, WxUserIds.resolveId(wxUser), outTradeNo,
            goods.getGoodsId(), goods.getName(), goods.getPrice(), goods.getPoints(),
            quantity, env, outTradeNo);
        order.setGoodsType(goods.getType());
        order.setGoodsValue(goods.getValue());
        order.setGoodsStyle(goods.getStyle());
        return payOrderPersistence.create(order)
            .flatMap(saved -> buildPayData(config, saved, wxUser.getSessionKey()))
            .onErrorResume(ex -> {
                if (isDuplicateNameError(ex) && attempt + 1 < 3) {
                    log.warn("虚拟支付订单号碰撞，将重试：outTradeNo={}", outTradeNo);
                    return createOrderAttempt(config, goods, openid, wxUser, quantity, env, attempt + 1);
                }
                log.error("虚拟支付下单失败：outTradeNo={}", outTradeNo, ex);
                return Mono.error(new WxAuthException("下单失败，请稍后重试"));
            });
    }

    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }

    private Mono<CreatePayOrderResponse> buildPayData(PayConfigContext config, PayOrder order,
        String sessionKey) {
        return Mono.fromCallable(() -> {
            var signData = buildSignData(config, order);
            var paySig = XPaySigner.calcPaySig(config.appKey(), C_D_REQUEST_URI, signData);
            var signature = XPaySigner.calcSignature(sessionKey, signData);
            return CreatePayOrderResponse.builder()
                .outTradeNo(order.getOutTradeNo())
                .status(order.getStatus())
                .payData(PayDataDTO.builder()
                    .signData(signData)
                    .paySig(paySig)
                    .signature(signature)
                    .mode(MODE_SHORT_SERIES_GOODS)
                    .build())
                .build();
        });
    }

    /**
     * signData 为固定键序的 JSON 字符串（与实际请求体完全一致，不格式化、不改键顺序）。
     */
    private static String buildSignData(PayConfigContext config, PayOrder order) {
        var data = new LinkedHashMap<String, Object>();
        data.put("offerId", config.offerId());
        data.put("buyQuantity", order.getQuantity());
        data.put("env", order.getEnv());
        data.put("currencyType", "CNY");
        data.put("productId", order.getGoodsId());
        data.put("goodsPrice", order.getPrice());
        data.put("outTradeNo", order.getOutTradeNo());
        data.put("attach", order.getAttach());
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("signData 序列化失败", ex);
        }
    }

    @Override
    public Mono<QueryPayOrderResponse> queryOrder(String openid, QueryPayOrderRequest request) {
        var normalizedOpenid = requireOpenid(openid);
        if (request == null || !StringUtils.hasText(request.getOutTradeNo())) {
            return Mono.error(new WxAuthException("outTradeNo 不能为空"));
        }
        var outTradeNo = request.getOutTradeNo().trim();
        return payOrderPersistence.findByOutTradeNo(outTradeNo)
            .switchIfEmpty(Mono.error(new WxAuthException("订单不存在")))
            .flatMap(order -> {
                if (!normalizedOpenid.equals(order.getOpenid())) {
                    return Mono.error(new WxAuthException("无权查询该订单"));
                }
                if (STATUS_PENDING.equals(order.getStatus())) {
                    // 前端轮询时顺带做一次查单兜底，推送丢失也能尽快到账
                    return syncAndDeliver(order)
                        .flatMap(delivered -> toQueryResponse(order, delivered));
                }
                return toQueryResponse(order, order.getDelivered() == Boolean.TRUE);
            });
    }

    private Mono<Boolean> syncAndDeliver(PayOrder order) {
        return withDeliveryLock(order, state ->
            payOrderPersistence.findByOutTradeNo(order.getOutTradeNo())
                .flatMap(current -> {
                    if (current.getDelivered() == Boolean.TRUE) {
                        return Mono.just(true);
                    }
                    return queryRemoteOrder(current.getOpenid(), current.getOutTradeNo())
                        .onErrorResume(ex -> {
                            log.warn("虚拟支付主动查单失败（保持待发货）：{}", order.getOutTradeNo(), ex);
                            return Mono.<RemoteOrderStatus>empty();
                        })
                        .flatMap(remote -> remote == null || !remote.paid()
                            ? Mono.just(false)
                            : doDeliver(current, remote).thenReturn(true));
                }));
    }

    @Override
    public Mono<String> handleDeliverNotify(String rawXml) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(rawXml)) {
                return Mono.just(xmlResponse(1, "empty body"));
            }
            var event = XmlMessageParser.value(rawXml, "Event");
            if (!EVENT_DELIVER_NOTIFY.equals(event)) {
                log.info("虚拟支付推送忽略非发货事件：{}", event);
                return Mono.just(xmlResponse(0, "ok"));
            }
            var outTradeNo = XmlMessageParser.value(rawXml, "OutTradeNo");
            // 平台单号 wx_order_id 位于 WeChatPayInfo.MchOrderNo 字段（官方推送字段，幂等去重以此为准）
            var wxOrderId = XmlMessageParser.valueFirstOf(rawXml, "MchOrderNo", "WxOrderId", "wx_order_id");
            var openid = XmlMessageParser.value(rawXml, "OpenId");
            if (!StringUtils.hasText(outTradeNo) || !StringUtils.hasText(openid)) {
                log.warn("虚拟支付发货推送缺少必要字段：outTradeNo={}, openid={}", outTradeNo, openid);
                return Mono.just(xmlResponse(1, "missing fields"));
            }
            var quantity = parseInt(XmlMessageParser.value(rawXml, "Quantity"), 1);
            var paidTime = parseEpochSecond(XmlMessageParser.valueFirstOf(rawXml, "PaidTime", "paid_time"));
            var transactionId = XmlMessageParser.valueFirstOf(rawXml, "TransactionId", "transaction_id");
            var mchOrderNo = XmlMessageParser.valueFirstOf(rawXml, "MchOrderNo", "mch_order_no");

            return payOrderPersistence.findByOutTradeNo(outTradeNo)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.error("虚拟支付发货推送：订单不存在 outTradeNo={}", outTradeNo);
                    return null;
                }))
                .flatMap(order -> {
                    if (order == null) {
                        return Mono.just(xmlResponse(1, "order not found"));
                    }
                    if (!openid.equals(order.getOpenid())) {
                        log.warn("虚拟支付发货推送 openid 不匹配：订单 {} 属于 {}, 推送为 {}", outTradeNo,
                            order.getOpenid(), openid);
                        return Mono.just(xmlResponse(1, "openid mismatch"));
                    }
                    return withDeliveryLock(order, state ->
                        payOrderPersistence.findByOutTradeNo(outTradeNo)
                            .flatMap(current -> {
                                if (current.getDelivered() == Boolean.TRUE) {
                                    return Mono.just(xmlResponse(0, "ok"));
                                }
                                return verifyPaidBeforeDeliver(current)
                                    .flatMap(paid -> {
                                        if (!paid) {
                                            return Mono.just(xmlResponse(1, "not paid yet"));
                                        }
                                        var remote = new RemoteOrderStatus(true, 2,
                                            paidTime, wxOrderId, transactionId, mchOrderNo);
                                        return doDeliver(current, remote)
                                            .thenReturn(xmlResponse(0, "ok"));
                                    });
                            }))
                        .defaultIfEmpty(xmlResponse(1, "order not found"));
                })
                .onErrorResume(ex -> {
                    log.error("虚拟支付发货推送处理异常：outTradeNo={}", outTradeNo, ex);
                    return Mono.just(xmlResponse(1, "internal error"));
                });
        });
    }

    private Mono<Boolean> verifyPaidBeforeDeliver(PayOrder order) {
        // 发货前必须调用官方 query_order 验真（服务端签名请求），防止伪造推送直接发货
        return queryRemoteOrder(order.getOpenid(), order.getOutTradeNo())
            .map(RemoteOrderStatus::paid)
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("虚拟支付发货前验真失败（不发货，等待微信重试）：{}", order.getOutTradeNo(), ex);
                return Mono.just(false);
            });
    }

    @Override
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void syncPendingOrders() {
        var threshold = Instant.now().minusSeconds(Math.max(120, DELIVERY_RETRY_INTERVAL_SECONDS * 2));
        payOrderPersistence.listPendingOlderThan(threshold)
            .flatMapIterable(orders -> orders.stream().limit(50).toList())
            .concatMap(this::syncAndDeliverSafe)
            .subscribe(syncResult -> log.info("虚拟支付兜底查单完成：订单={}，已支付={}", syncResult.orderNo(),
                    syncResult.paid()),
                ex -> log.warn("虚拟支付兜底查单异常", ex));
    }

    private Mono<SyncResult> syncAndDeliverSafe(PayOrder order) {
        return withDeliveryLock(order, state ->
            payOrderPersistence.findByOutTradeNo(order.getOutTradeNo())
                .flatMap(current -> {
                    if (current.getDelivered() == Boolean.TRUE) {
                        return Mono.just(new SyncResult(current.getOutTradeNo(), true));
                    }
                    return queryRemoteOrder(current.getOpenid(), current.getOutTradeNo())
                        .flatMap(remote -> remote == null || !remote.paid()
                            ? Mono.just(new SyncResult(current.getOutTradeNo(), false))
                            : doDeliver(current, remote).thenReturn(new SyncResult(current.getOutTradeNo(), true)))
                        .defaultIfEmpty(new SyncResult(current.getOutTradeNo(), false));
                }))
            .onErrorResume(ex -> {
                log.warn("虚拟支付兜底查单失败：{}", order.getOutTradeNo(), ex);
                return Mono.just(new SyncResult(order.getOutTradeNo(), false));
            });
    }

    private <T> Mono<T> withDeliveryLock(PayOrder order,
        java.util.function.Function<Object, Mono<T>> action) {
        var lock = deliveryLocks.computeIfAbsent(order.getMetadata().getName(), key -> new Object());
        return Mono.defer(() -> {
            synchronized (lock) {
                return action.apply(lock);
            }
        }).doFinally(signal -> deliveryLocks.remove(order.getMetadata().getName()));
    }

    private Mono<PayOrder> doDeliver(PayOrder order, RemoteOrderStatus remote) {
        var earnKey = order.getOpenid() + ":purchase:" + order.getOutTradeNo();
        return resolveWxUserId(order)
            .flatMap(wxUserId -> {
                var updated = copyForDeliver(order, remote, wxUserId);
                // 以流水 earnKey 为幂等锚点：先查流水，已存在则视为已发放（不重复入账），
                // 订单状态更新失败时下次重试也会跳过入账，保证恰好一次发放。
                return pointsPersistence.findRecordByEarnKey(earnKey)
                    .flatMap(existing -> markDelivered(updated))
                    .switchIfEmpty(grantGoods(order, earnKey, wxUserId)
                        .then(markDelivered(updated)));
            });
    }

    /** 道具直购发放：按商品类型分发，未配置 type 时保守兼容旧的积分充值逻辑 */
    private Mono<Void> grantGoods(PayOrder order, String earnKey, String wxUserId) {
        var type = StringUtils.hasText(order.getGoodsType())
            ? order.getGoodsType().trim().toLowerCase() : "";
        if (type.isEmpty() || "points".equals(type)) {
            var grant = (order.getPoints() == null ? 0 : order.getPoints())
                * (order.getQuantity() == null ? 1 : order.getQuantity());
            if (grant <= 0) {
                log.error("虚拟支付发货跳过：订单 {} 到账积分为 0", order.getOutTradeNo());
                return Mono.error(new WxAuthException("订单积分配置无效，发货失败"));
            }
            return grantPoints(order, earnKey, grant, wxUserId).then();
        }
        return switch (type) {
            case "dashboard" -> grantDashboardAccess(order, earnKey);
            case "publish_ticket" -> grantPublishTickets(order, earnKey, wxUserId);
            case "outfit" -> grantOutfit(order, earnKey, wxUserId, order.getGoodsStyle());
            default -> {
                log.error("虚拟支付发货跳过：订单 {} 道具类型未知：{}", order.getOutTradeNo(), type);
                yield Mono.error(new WxAuthException("未知的道具类型，发货失败"));
            }
        };
    }

    /** 数据看板高级版（当日）：写入一条 exchange_dashboard 记录，与积分兑换的当日开通判定完全兼容 */
    private Mono<Void> grantDashboardAccess(PayOrder order, String earnKey) {
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> pointsPersistence.appendRecord(pointsPersistence.newRecord(
                order.getOpenid(), "exchange_dashboard", 0, safeBalance(account),
                "道具直购 · " + (StringUtils.hasText(order.getGoodsName())
                    ? order.getGoodsName() : "数据看板高级版"),
                order.getGoodsId(), earnKey, order.getWxUserId())))
            .then();
    }

    /** 发布券：发放到 UserPoints.publishTickets */
    private Mono<Void> grantPublishTickets(PayOrder order, String earnKey, String wxUserId) {
        var count = Math.max(1, order.getGoodsValue() == null ? 1 : order.getGoodsValue());
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> {
                if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                    account.setWxUserId(wxUserId);
                }
                var current = account.getPublishTickets() == null ? 0 : account.getPublishTickets();
                account.setPublishTickets(current + count);
                return pointsPersistence.saveAccount(account)
                    .then(pointsPersistence.appendRecord(pointsPersistence.newRecord(
                        order.getOpenid(), "purchase_publish_ticket", 0, safeBalance(account),
                        "道具直购 · 发布券 x" + count, order.getGoodsId(), earnKey, wxUserId)))
                    .then();
            });
    }

    /** 装扮：直购即拥有（outfitOwned 追加），佩戴仍由用户在身份中心操作 */
    private Mono<Void> grantOutfit(PayOrder order, String earnKey, String wxUserId, String outfitKey) {
        if (!StringUtils.hasText(outfitKey)) {
            return Mono.error(new WxAuthException("装扮商品未配置 key"));
        }
        var key = outfitKey.trim();
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> {
                if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                    account.setWxUserId(wxUserId);
                }
                var owned = new ArrayList<>(parseOwned(account.getOutfitOwned()));
                if (owned.contains(key)) {
                    // 已拥有：仍落一条 0 分流水用于幂等标记
                    return pointsPersistence.appendRecord(pointsPersistence.newRecord(
                        order.getOpenid(), "purchase_outfit", 0, safeBalance(account),
                        "道具直购 · 已拥有装扮（重复购买）", key, earnKey, wxUserId))
                        .then();
                }
                owned.add(key);
                account.setOutfitOwned(writeOwned(owned));
                return pointsPersistence.saveAccount(account)
                    .then(pointsPersistence.appendRecord(pointsPersistence.newRecord(
                        order.getOpenid(), "purchase_outfit", 0, safeBalance(account),
                        "道具直购 · 解锁装扮", key, earnKey, wxUserId)))
                    .then();
            });
    }

    private static List<String> parseOwned(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            var parsed = OBJECT_MAPPER.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : new ArrayList<>(parsed);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private static String writeOwned(List<String> owned) {
        try {
            return OBJECT_MAPPER.writeValueAsString(owned);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static long safeBalance(pro.uhalo.uni.scheme.UserPoints account) {
        return account.getBalance() == null ? 0L : Math.max(0L, account.getBalance());
    }

    private Mono<PointsRecord> grantPoints(PayOrder order, String earnKey, int grant, String wxUserId) {
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> {
                var newBalance = (account.getBalance() == null ? 0L : account.getBalance()) + grant;
                account.setBalance(newBalance);
                return pointsPersistence.saveAccount(account)
                    .then(pointsPersistence.appendRecord(pointsPersistence.newRecord(
                        order.getOpenid(), RECHARGE_ACTION, grant, newBalance,
                        (StringUtils.hasText(order.getGoodsName()) ? order.getGoodsName() : "积分充值")
                            + " x" + (order.getQuantity() == null ? 1 : order.getQuantity()),
                        order.getGoodsId(), earnKey, wxUserId)));
            })
            .onErrorResume(ex -> {
                // 并发推送/查单同时发放时，唯一流水创建失败视为已发放
                if (isDuplicateNameError(ex)) {
                    log.info("虚拟支付积分流水已存在，跳过重复发放：earnKey={}", earnKey);
                    return pointsPersistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.<PointsRecord>just(existing));
                }
                return Mono.error(ex);
            });
    }

    private Mono<PayOrder> markDelivered(PayOrder updated) {
        return payOrderPersistence.update(updated)
            .doOnSuccess(saved -> log.info("虚拟支付发货成功：outTradeNo={}, wxOrderId={}, 积分已入账",
                saved.getOutTradeNo(), saved.getWxOrderId()))
            .onErrorResume(ex -> {
                log.warn("虚拟支付订单状态更新失败（重试时幂等补发）：{}", updated.getOutTradeNo(), ex);
                return payOrderPersistence.findByOutTradeNo(updated.getOutTradeNo());
            });
    }

    private static PayOrder copyForDeliver(PayOrder order, RemoteOrderStatus remote, String wxUserId) {
        var updated = new PayOrder();
        updated.setMetadata(order.getMetadata());
        updated.setOpenid(order.getOpenid());
        updated.setWxUserId(StringUtils.hasText(wxUserId) ? wxUserId : order.getWxUserId());
        updated.setOutTradeNo(order.getOutTradeNo());
        updated.setWxOrderId(remote.wxOrderId());
        updated.setGoodsId(order.getGoodsId());
        updated.setGoodsName(order.getGoodsName());
        updated.setPrice(order.getPrice());
        updated.setPoints(order.getPoints());
        updated.setGoodsType(order.getGoodsType());
        updated.setGoodsValue(order.getGoodsValue());
        updated.setGoodsStyle(order.getGoodsStyle());
        updated.setQuantity(order.getQuantity());
        updated.setEnv(order.getEnv());
        updated.setAttach(order.getAttach());
        updated.setStatus(STATUS_DELIVERED);
        updated.setDelivered(true);
        updated.setTransactionId(remote.transactionId());
        updated.setMchOrderNo(remote.mchOrderNo());
        updated.setPaidTime(remote.paidTime() != null ? remote.paidTime() : Instant.now());
        updated.setDeliverTime(Instant.now());
        updated.setCreateTime(order.getCreateTime());
        updated.setUpdateTime(Instant.now());
        updated.setRemark(order.getRemark());
        return updated;
    }

    private Mono<String> resolveWxUserId(PayOrder order) {
        if (StringUtils.hasText(order.getWxUserId())) {
            return Mono.just(order.getWxUserId().trim());
        }
        return wxUserService.fetchByOpenid(order.getOpenid())
            .map(WxUserIds::resolveId)
            .filter(StringUtils::hasText)
            .defaultIfEmpty("");
    }

    private Mono<QueryPayOrderResponse> toQueryResponse(PayOrder order, boolean delivered) {
        return Mono.defer(() -> {
            if (!delivered || order.getPoints() == null) {
                return Mono.just(QueryPayOrderResponse.builder()
                    .outTradeNo(order.getOutTradeNo())
                    .status(order.getStatus())
                    .delivered(false)
                    .build());
            }
            var grant = order.getPoints() * (order.getQuantity() == null ? 1 : order.getQuantity());
            return pointsPersistence.getOrCreateAccount(order.getOpenid())
                .map(account -> QueryPayOrderResponse.builder()
                    .outTradeNo(order.getOutTradeNo())
                    .status(order.getStatus())
                    .delivered(true)
                    .points(grant)
                    .balance(account.getBalance())
                    .build());
        });
    }

    /**
     * 服务端查询微信订单状态（兜底/验真）：POST /wxa/business/xpay/query_order。
     */
    private Mono<RemoteOrderStatus> queryRemoteOrder(String openid, String outTradeNo) {
        return ensureAccessToken()
            .flatMap(token -> loadConfig()
                .flatMap(config -> {
                    if (!StringUtils.hasText(config.appKey())) {
                        return Mono.error(new WxAuthException("虚拟支付 AppKey 未配置"));
                    }
                    var body = buildQueryOrderBody(openid, config.env(), outTradeNo);
                    var paySig = XPaySigner.calcPaySig(config.appKey(), QUERY_ORDER_API_PATH, body);
                    var uri = QUERY_ORDER_URL + "?access_token=" + token + "&pay_sig=" + paySig;
                    return WEB_CLIENT.post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .map(this::parseJsonNode)
                        .flatMap(this::parseRemoteOrderStatus);
                }));
    }

    private static String buildQueryOrderBody(String openid, int env, String outTradeNo) {
        var data = new LinkedHashMap<String, Object>();
        data.put("openid", openid);
        data.put("env", env);
        data.put("order_id", outTradeNo);
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("query_order body 序列化失败", ex);
        }
    }

    /** 微信接口响应：String → JsonNode（手动解析，避免 WebFlux JsonNode 编解码兼容问题） */
    private JsonNode parseJsonNode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WxAuthException("微信接口响应为空");
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new WxAuthException("微信接口响应解析失败");
        }
    }

    private Mono<RemoteOrderStatus> parseRemoteOrderStatus(JsonNode body) {
        return Mono.fromCallable(() -> {
            if (body == null || body.hasNonNull("errcode") && body.get("errcode").asInt() != 0) {
                var errcode = body == null ? -1 : body.path("errcode").asInt(-1);
                var errmsg = body == null ? "empty" : body.path("errmsg").asText("unknown");
                log.warn("虚拟支付 query_order 返回错误：{} {}", errcode, errmsg);
                throw new WxAuthException("微信订单查询失败(" + errcode + ")");
            }
            var order = body.path("order");
            if (order.isMissingNode() || order.isNull()) {
                return null;
            }
            var status = order.path("status").asInt(0);
            var paid = status >= 2 && status != 5 && status != 6
                || (order.path("paid_time").asLong(0) > 0 && status != 5 && status != 6);
            var paidTime = order.path("paid_time").asLong(0) > 0
                ? Instant.ofEpochSecond(order.path("paid_time").asLong(0))
                : null;
            return new RemoteOrderStatus(paid, status, paidTime,
                order.path("wx_order_id").asText(""), null, null);
        });
    }

    private Mono<String> ensureAccessToken() {
        var token = tokenManager.getToken();
        if (StringUtils.hasText(token)) {
            return Mono.just(token);
        }
        return loadWxAppConfig()
            .flatMap(config -> {
                if (!StringUtils.hasText(config.appId()) || !StringUtils.hasText(config.appSecret())) {
                    return Mono.error(new WxAuthException("微信小程序 AppID 或 AppSecret 未配置"));
                }
                var url = TOKEN_URL + "?grant_type=client_credential&appid=" + config.appId()
                    + "&secret=" + config.appSecret();
                // String + 手动解析，避开部分 Spring/Jackson 组合下 bodyToMono(JsonNode.class) 的
                // "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]" 崩溃
                return WEB_CLIENT.get().uri(url).retrieve().bodyToMono(String.class)
                    .map(this::parseJsonNode)
                    .flatMap(body -> {
                        if (body.hasNonNull("errcode") && body.get("errcode").asInt() != 0) {
                            return Mono.error(new WxAuthException("微信 access_token 获取失败："
                                + body.path("errmsg").asText("请检查 appId/AppSecret 配置")));
                        }
                        var accessToken = body.path("access_token").asText("");
                        if (!StringUtils.hasText(accessToken)) {
                            return Mono.error(new WxAuthException("微信 access_token 为空，请检查 appId/AppSecret 配置"));
                        }
                        tokenManager.setToken(accessToken, body.path("expires_in").asInt(7200));
                        return Mono.just(accessToken);
                    });
            });
    }

    private Mono<WxAppContext> loadWxAppConfig() {
        return settingFetcher.get(WX_APP_CONFIG_GROUP)
            .switchIfEmpty(Mono.just(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()))
            .flatMap(wxConfig -> settingFetcher.get(APP_CONFIG_GROUP)
                .switchIfEmpty(Mono.just(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()))
                .map(appConfig -> {
                    var appInfo = appConfig.path("appInfo");
                    return new WxAppContext(
                        firstNonEmpty(textValue(wxConfig, "appId"), textValue(appInfo, "appId")),
                        firstNonEmpty(textValue(wxConfig, "appSecret"), textValue(appInfo, "appSecret")));
                }));
    }

    private Mono<PayConfigContext> loadConfig() {
        return settingFetcher.get(PAY_CONFIG_GROUP)
            .map(this::parseConfig)
            .defaultIfEmpty(PayConfigContext.disabled());
    }

    private PayConfigContext parseConfig(JsonNode node) {
        var enabled = node.path("enabled").asBoolean(false);
        var offerId = textValue(node, "offerId");
        var appKey = textValue(node, "appKey");
        var zoneId = node.path("zoneId").asInt(1);
        var env = node.has("env") ? node.path("env").asInt(DEFAULT_ENV) : DEFAULT_ENV;
        var goods = new ArrayList<PayGoodsDTO>();
        var goodsNode = node.get("goods");
        if (goodsNode != null && goodsNode.isArray()) {
            for (var item : goodsNode) {
                var goodsId = textValue(item, "goodsId");
                var name = textValue(item, "name");
                var price = item.path("price").asLong(0);
                var points = item.path("points").asInt(0);
                var type = textValue(item, "type").toLowerCase();
                var value = item.path("value").asInt(0);
                var style = textValue(item, "style");
                var valid = StringUtils.hasText(goodsId) && StringUtils.hasText(name) && price > 0;
                var isPoints = type.isEmpty() || "points".equals(type);
                var isGoods = "dashboard".equals(type) || "publish_ticket".equals(type)
                    || "outfit".equals(type);
                var typeValid = isPoints ? points > 0
                    : isGoods && (!"outfit".equals(type) || StringUtils.hasText(style));
                if (valid && typeValid) {
                    goods.add(PayGoodsDTO.builder()
                        .goodsId(goodsId)
                        .name(name)
                        .price(price)
                        .points(points)
                        .type(type)
                        .value(value)
                        .style(style)
                        .build());
                }
            }
        }
        return new PayConfigContext(enabled, offerId, appKey, zoneId, env,
            List.copyOf(goods));
    }

    private void ensurePayEnabled(PayConfigContext config) {
        if (config == null || !config.enabled()) {
            throw new WxAuthException("虚拟支付功能未开启");
        }
    }

    private static String resolveGoodsId(CreatePayOrderRequest request) {
        var goodsId = request == null ? null : request.getGoodsId();
        if (!StringUtils.hasText(goodsId)) {
            throw new WxAuthException("goodsId 不能为空");
        }
        var normalized = goodsId.trim();
        if (normalized.length() > 64) {
            throw new WxAuthException("goodsId 参数过长");
        }
        for (var i = 0; i < normalized.length(); i++) {
            var ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)
                || (ch < 0x20) || (ch > 0x7E)) {
                throw new WxAuthException("goodsId 参数不合法");
            }
        }
        return normalized;
    }

    private static PayGoodsDTO resolveGoods(PayConfigContext config, String goodsId) {
        return config.goods().stream()
            .filter(goods -> goods.getGoodsId().equals(goodsId))
            .findFirst()
            .orElseThrow(() -> new WxAuthException("未找到对应商品"));
    }

    private static String requireOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new WxAuthException("缺少 Wechat-Session-Id");
        }
        return openid.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return StringUtils.hasText(value) ? Integer.parseInt(value.trim()) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Instant parseEpochSecond(String value) {
        var seconds = parseInt(value, 0);
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : null;
    }

    private static String xmlResponse(int errCode, String errMsg) {
        return "<xml><ErrCode>" + errCode + "</ErrCode><ErrMsg><![CDATA[" + errMsg + "]]></ErrMsg></xml>";
    }

    private static String textValue(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (var value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record PayConfigContext(boolean enabled, String offerId, String appKey, int zoneId, int env,
        List<PayGoodsDTO> goods) {
        static PayConfigContext disabled() {
            return new PayConfigContext(false, "", "", 1, DEFAULT_ENV, List.of());
        }
    }

    private record WxAppContext(String appId, String appSecret) {
    }

    private record RemoteOrderStatus(boolean paid, int status, Instant paidTime, String wxOrderId,
        String transactionId, String mchOrderNo) {
    }

    private record SyncResult(String orderNo, boolean paid) {
    }
}
