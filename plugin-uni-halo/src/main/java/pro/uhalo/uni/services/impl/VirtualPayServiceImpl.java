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
import pro.uhalo.uni.dto.AdminPayOrderItemDTO;
import pro.uhalo.uni.dto.AdminPayOrderListResponse;
import pro.uhalo.uni.dto.CreatePayOrderRequest;
import pro.uhalo.uni.dto.CreatePayOrderResponse;
import pro.uhalo.uni.dto.LevelConfigDTO;
import pro.uhalo.uni.dto.OutfitItemDTO;
import pro.uhalo.uni.dto.PayDataDTO;
import pro.uhalo.uni.dto.PayGoodsDTO;
import pro.uhalo.uni.dto.PayPublicConfigDTO;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderResponse;
import pro.uhalo.uni.exception.NonDeliverableOrderException;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.VirtualPayService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.TokenManager;
import pro.uhalo.uni.utils.WxRequestParser;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import pro.uhalo.uni.utils.XPaySigner;
import pro.uhalo.uni.utils.XmlMessageParser;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 虚拟支付核心服务（道具直购）。
 *
 * <p>实现依据（官方文档）：</p>
 * <ul>
 *   <li>下单：服务器生成 signData + paySig + signature（mode=short 道具直购），前端调用基础库 wx.requestVirtualPayment</li>
 *   <li>发货：以 xpay_goods_deliver_notify 推送为主，按 earnKey 幂等，返回 &lt;xml&gt;&lt;ErrCode&gt;0&lt;/ErrCode&gt;&lt;/xml&gt;</li>
 *   <li>道具类型：dashboard=数据看板当日高级版；publish_ticket=发布券；outfit=装扮解锁</li>
 *   <li>合规边界：个人主体仅支持道具直购，不提供 points（现金充值积分）类型</li>
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
    /** 签名用 uri：官方要求 B 端 /xpay/* 接口以实际请求路径参与 pay_sig 计算 */
    private static final String QUERY_ORDER_API_PATH = "/xpay/query_order";
    /**
     * 查单实际请求地址，必须与 {@link #QUERY_ORDER_API_PATH} 保持一致。
     *
     * <p>官方文档（虚拟支付 - 查询创建的订单）：
     * POST https://api.weixin.qq.com/xpay/query_order?access_token=xx&amp;pay_sig=xx
     * 若两者路径不一致，微信按实际路径验签会返回 268490003 签名错误，
     * 导致发货前验真与兜底查单全部失败。</p>
     */
    private static final String QUERY_ORDER_URL = "https://api.weixin.qq.com/xpay/query_order";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 支付类型：short_series_goods = 道具直购（官方合法值，见 wx.requestVirtualPayment 文档） */
    private static final String MODE_SHORT_SERIES_GOODS = "short_series_goods";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_DELIVERED = "delivered";
    private static final String STATUS_CLOSED = "closed";
    /**
     * 终态：已支付但权益不可发放（重复购买），需站长人工退款。
     *
     * <p>与 {@link #STATUS_PENDING} 分开是为了让兜底查单（按 status=pending 筛选）
     * 不再拾取该订单，避免每 5 分钟无限重试；同时该状态可被后台退款清单直接筛选出来。</p>
     */
    private static final String STATUS_NEEDS_REFUND = "needs_refund";
    private static final String NEEDS_REFUND_REMARK_PREFIX = "重复购买：权益已拥有，未重复发放，需人工退款";

    /** 后台订单列表每页最大条数（防止一次拉爆内存） */
    private static final int ADMIN_MAX_SIZE = 200;
    private static final String EVENT_DELIVER_NOTIFY = "xpay_goods_deliver_notify";

    /**
     * 官方 query_order 中允许发货的订单状态白名单：
     * 2=已支付待发货，3=发货中，4=已发货。
     *
     * <p>其余状态（0 初始化、1 创建成功、5 已退款、6 已关闭、7 退款失败、
     * 8 用户退款完成、9 回收广告金完成、10 分账回退完成）一律不发货。</p>
     */
    private static final java.util.Set<Integer> DELIVERABLE_ORDER_STATUSES = java.util.Set.of(2, 3, 4);

    private static final int DEFAULT_ENV = 0;
    private static final int DELIVERY_RETRY_INTERVAL_SECONDS = 30;

    private final ReactiveSettingFetcher settingFetcher;
    private final PayOrderPersistence payOrderPersistence;
    private final PointsPersistence pointsPersistence;
    private final WxUserService wxUserService;
    private final TokenManager tokenManager;

    /**
     * 同一订单的在途发货任务。
     *
     * <p>用 {@code Mono.cache()} 合并在途请求：并发到达的重复推送/轮询会共享同一个
     * 已订阅的 Mono，底层发货动作只执行一次，后到者直接拿到同一结果。</p>
     */
    private final ConcurrentHashMap<String, Mono<?>> deliveryInFlight = new ConcurrentHashMap<>();

    @Override
    public Mono<PayPublicConfigDTO> getPublicConfig() {
        return loadConfig()
            .flatMap(config -> {
                if (!config.enabled()) {
                    return Mono.just(PayPublicConfigDTO.builder()
                        .enabled(false)
                        .goods(List.of())
                        .build());
                }
                return filterUnregisteredOutfits(config.goods())
                    .map(goods -> PayPublicConfigDTO.builder()
                        .enabled(true)
                        .goods(goods)
                        .build());
            });
    }

    /**
     * 过滤未在装扮商店登记的装扮商品，并留下告警日志。
     *
     * <p>站长删除装扮商店条目、或把 goods.style 填错时，该商品点进去也买不成
     * （{@link #createOrder} 会拒绝）。与其让用户点了才看到报错，不如直接不下发；
     * 告警日志让站长能定位到是哪一条商品配置需要修。</p>
     */
    private Mono<List<PayGoodsDTO>> filterUnregisteredOutfits(List<PayGoodsDTO> goods) {
        var hasOutfitGoods = goods.stream().anyMatch(item -> "outfit".equals(item.getType()));
        if (!hasOutfitGoods) {
            return Mono.just(goods);
        }
        return loadLevelConfig().map(levelConfig -> {
            var registeredKeys = levelConfig.getOutfits() == null ? Set.<String>of()
                : levelConfig.getOutfits().stream()
                    .map(OutfitItemDTO::getKey)
                    .collect(Collectors.toSet());
            var kept = new ArrayList<PayGoodsDTO>(goods.size());
            for (var item : goods) {
                if ("outfit".equals(item.getType()) && !registeredKeys.contains(item.getStyle())) {
                    log.warn("虚拟支付装扮商品未在装扮商店登记，已隐藏：goodsId={}, style={}"
                            + "（请先在「等级与称号装扮 → 装扮商店」新增同 key 的装扮）",
                        item.getGoodsId(), item.getStyle());
                    continue;
                }
                kept.add(item);
            }
            return List.copyOf(kept);
        });
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
                // 装扮商品下单前校验（单一装扮定义源：key 必须存在于装扮商店配置）：
                // 1) key 不存在 → 拒绝下单（配置错误，防止用户付钱拿不到权益）
                // 2) 用户已拥有（未过期）→ 拒绝下单（防止重复购买白花钱）
                var preCheck = Mono.just(goods);
                if ("outfit".equals(goods.getType())) {
                    preCheck = settingFetcher.get(LevelConfigHelper.groupName())
                        .map(LevelConfigHelper::parse)
                        .defaultIfEmpty(LevelConfigHelper.defaults())
                        .flatMap(levelConfig -> {
                            var key = goods.getStyle();
                            var outfit = levelConfig.getOutfits().stream()
                                .filter(item -> key != null && key.equals(item.getKey()))
                                .findFirst()
                                .orElse(null);
                            if (outfit == null) {
                                return Mono.error(new WxAuthException(
                                    "装扮配置错误：该装扮商品未在装扮商店登记，请联系站长"));
                            }
                            var keyLabel = outfit.getName();
                            return pointsPersistence.getOrCreateAccount(normalizedOpenid)
                                .flatMap(account -> {
                                    var actives = OwnedOutfitParser.parse(account.getOutfitOwned());
                                    var effective = actives.stream()
                                        .filter(entry -> entry.isStillValid())
                                        .anyMatch(entry -> key.equals(entry.key()));
                                    if (effective) {
                                        return Mono.error(new WxAuthException(
                                            "你已拥有该装扮（" + keyLabel + "），请勿重复购买"));
                                    }
                                    // 装扮商品名称以装扮商店配置为准（单一装扮定义源）。
                                    var resolved = PayGoodsDTO.builder()
                                        .goodsId(goods.getGoodsId())
                                        .name(outfit.getName())
                                        .price(goods.getPrice())
                                        .type(goods.getType())
                                        .value(goods.getValue())
                                        .style(goods.getStyle())
                                        .build();
                                    return Mono.just(resolved);
                                });
                        });
                }
                return preCheck
                    .flatMap(checked -> wxUserService.fetchByOpenid(normalizedOpenid)
                        .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                        .flatMap(wxUser -> {
                            if (!StringUtils.hasText(wxUser.getSessionKey())) {
                                return Mono.error(new WxAuthException("登录态已失效，请重新登录"));
                            }
                            return createOrderRecord(config, checked, normalizedOpenid, wxUser);
                        }));
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
            goods.getGoodsId(), goods.getName(), goods.getPrice(),
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
        return withDeliveryLock(order.getOutTradeNo(), () ->
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
                    return withDeliveryLock(outTradeNo, () ->
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
        return withDeliveryLock(order.getOutTradeNo(), () ->
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

    /**
     * 合并同一订单的在途发货动作。
     *
     * <p>原实现用 {@code synchronized (lock)} 包裹 {@code action.apply()}，存在两个致命缺陷：</p>
     * <ol>
     *   <li>{@code synchronized} 只保护 Mono 的<b>装配</b>阶段，真正的异步执行发生在锁释放之后，
     *       因此完全没有互斥效果；</li>
     *   <li>{@code doFinally} 会移除锁对象，下一个线程 {@code computeIfAbsent} 拿到的是<b>全新对象</b>，
     *       连装配阶段的伪互斥也不复存在；同时移除的是无条件 {@code remove(key)}，
     *       会误删其他线程刚放入的新锁。</li>
     * </ol>
     *
     * <p>改为 {@code Mono.cache()} 合并在途请求：同一订单的并发调用共享同一个已订阅的 Mono，
     * 底层动作只执行一次，后到者直接复用结果。移除时使用双参 {@code remove(key, value)}，
     * 只在映射仍指向本实例时才移除，避免误删。</p>
     *
     * <p>该机制仅在单 JVM 内生效，多实例部署仍需依赖 {@code earnKey} 流水唯一约束兜底
     * （见 grantXxx 系列方法「先写幂等流水，再改账户」的顺序）。</p>
     */
    @SuppressWarnings("unchecked")
    private <T> Mono<T> withDeliveryLock(String lockKey, java.util.function.Supplier<Mono<T>> action) {
        return Mono.defer(() -> {
            var cached = (Mono<T>) deliveryInFlight.computeIfAbsent(lockKey,
                key -> action.get().cache());
            return cached.doFinally(signal -> deliveryInFlight.remove(lockKey, cached));
        });
    }

    /**
     * 执行发货（推送/主动查单/兜底查单三条路径共用）。
     *
     * <p>包级可见：便于同包测试直接验证「重复购买 → needs_refund 终态」这一分支，
     * 该分支此前只能靠端到端验证，而它正是「付了钱什么都没发生」的缺陷所在。</p>
     */
    Mono<PayOrder> doDeliver(PayOrder order, RemoteOrderStatus remote) {
        var earnKey = order.getOpenid() + ":purchase:" + order.getOutTradeNo();
        return resolveWxUserId(order)
            .flatMap(wxUserId -> {
                var updated = copyForDeliver(order, remote, wxUserId);
                // 以流水 earnKey 为幂等锚点：先查流水，已存在则视为已发放（不重复入账），
                // 订单状态更新失败时下次重试也会跳过入账，保证恰好一次发放。
                return pointsPersistence.findRecordByEarnKey(earnKey)
                    .flatMap(existing -> markDelivered(updated))
                    .switchIfEmpty(grantGoods(order, earnKey, wxUserId)
                        .then(markDelivered(updated)))
                    // 权益不可发放（重复购买已拥有）：转终态交人工退款。
                    // 绝不向上抛——否则订单永久停留 pending，被兜底查单无限重试。
                    .onErrorResume(NonDeliverableOrderException.class,
                        ex -> markNeedsRefund(order, remote, wxUserId, ex));
            });
    }

    /**
     * 已支付但权益不可发放的订单转终态（{@link #STATUS_NEEDS_REFUND}）。
     *
     * <p>重复购买这类情况重试不会成功：用户已付款、但权益已拥有不能再发一遍。
     * 原实现直接抛业务异常，错误又发生在幂等流水写入之前，导致订单永久 pending、
     * 每 5 分钟被兜底查单重试一次，用户既拿不到权益也无人知道。</p>
     *
     * <p>现在改为置终态 + 备注 + ERROR 日志：兜底查单（按 status=pending 筛选）不再拾取，
     * 站长可凭日志或后台退款清单（status=needs_refund）在微信侧人工退款。</p>
     */
    private Mono<PayOrder> markNeedsRefund(PayOrder order, RemoteOrderStatus remote, String wxUserId,
        NonDeliverableOrderException ex) {
        log.error("虚拟支付订单已支付但无法发货，需人工退款：outTradeNo={}, wxOrderId={}, openid={}, "
                + "商品={}, 金额={}分, 原因={}",
            order.getOutTradeNo(), remote.wxOrderId(), order.getOpenid(),
            order.getGoodsName(), order.getPrice(), ex.getReason());
        var updated = copyForDeliver(order, remote, wxUserId);
        updated.setStatus(STATUS_NEEDS_REFUND);
        updated.setDelivered(false);
        // 未发放任何权益，不写发货时间
        updated.setDeliverTime(null);
        updated.setRemark(NEEDS_REFUND_REMARK_PREFIX + "（" + ex.getReason() + "）");
        updated.setUpdateTime(Instant.now());
        return payOrderPersistence.update(updated)
            .onErrorResume(err -> {
                // 落库失败时订单仍是 pending，下次兜底查单会重新进入本方法重试更新，
                // 不产生资金损失；返回内存对象让调用方（推送/轮询）正常收尾。
                log.warn("虚拟支付待退款订单状态更新失败（下次兜底重试）：{}",
                    order.getOutTradeNo(), err);
                return Mono.just(updated);
            });
    }

    /**
     * 道具直购发放：按商品类型分发到具体权益。
     *
     * <p>个人主体小程序仅允许道具直购，官方明确「只做道具直购，不引入代币等额外功能」，
     * 因此不提供 points（现金充值积分）分支；type 缺失或未知时直接判为发货失败，
     * 不做任何积分入账，避免形成代币式充值通道。</p>
     *
     * <p>可配置类型：content_ticket（阅读券）、outfit（装扮）。
     * 以下分支仅为**历史订单履约**保留，{@link #parseConfig(JsonNode)} 已不再接受这些类型，
     * 新订单无法购买，但升级前「已支付未发货」的存量订单仍需正常发放，避免损害已付款用户：</p>
     * <ul>
     *   <li>dashboard —— 数据看板高级版已调整为纯积分兑换权益</li>
     *   <li>publish_ticket —— 发布券已下架：普通用户不能发表瞬间（仅博主可发），
     *       该券没有任何消费点，卖出去用户也用不上</li>
     * </ul>
     */
    private Mono<Void> grantGoods(PayOrder order, String earnKey, String wxUserId) {
        var type = StringUtils.hasText(order.getGoodsType())
            ? order.getGoodsType().trim().toLowerCase() : "";
        return switch (type) {
            case "dashboard" -> grantDashboardAccess(order, earnKey);
            case "publish_ticket" -> grantPublishTickets(order, earnKey, wxUserId);
            case "content_ticket" -> grantContentTickets(order, earnKey, wxUserId);
            case "outfit" -> grantOutfit(order, earnKey, wxUserId, order.getGoodsStyle());
            default -> {
                log.error("虚拟支付发货跳过：订单 {} 道具类型非法或缺失：{}（可配置类型为 "
                        + "content_ticket/outfit；dashboard 与 publish_ticket 已下架，仅用于历史订单履约）",
                    order.getOutTradeNo(), type.isEmpty() ? "(未填写)" : type);
                yield Mono.error(new WxAuthException("未知的道具类型，发货失败"));
            }
        };
    }

    /**
     * 数据看板高级版（当日）：写入一条 purchase_dashboard 记录。
     *
     * <p><b>仅供历史订单履约</b>：该权益已调整为纯积分兑换（见 mallConfig.exchangeItems 的
     * dashboard_advanced 项），不再作为付费道具售卖，新订单无法进入此分支。</p>
     *
     * <p>动作码与积分兑换的 exchange_dashboard 区分，使「积分明细」如实显示该权益是
     * 现金购买而非积分兑换。当日开通判定已由
     * {@link PointsServiceImpl#exchangeActionsOf(String)} 合并计入两种动作码，
     * 因此历史现金购买订单同样能解锁看板高级版。</p>
     */
    private Mono<Void> grantDashboardAccess(PayOrder order, String earnKey) {
        // 该方法本身只写流水、不改账户，create 的唯一资源名即是幂等闸门，无需调整顺序
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> pointsPersistence.appendRecord(pointsPersistence.newRecord(
                order.getOpenid(), PointsServiceImpl.PURCHASE_DASHBOARD_ACTION, 0, safeBalance(account),
                "道具直购 · " + (StringUtils.hasText(order.getGoodsName())
                    ? order.getGoodsName() : "数据看板高级版"),
                order.getGoodsId(), earnKey, order.getWxUserId())))
            .then();
    }

    /**
     * 发布券：发放到 UserPoints.publishTickets。
     *
     * <p><b>仅供历史订单履约</b>：发布券已下架，{@link #parseConfig(JsonNode)} 不再接受
     * {@code publish_ticket} 类型，新订单无法购买。下架原因是该券<b>没有任何消费点</b>——
     * 瞬间发布权限由 {@code momentPublish.js} 按 personalToken 身份匹配判定，
     * 仅博主本人可发，从不检查 publishTickets 余额，普通用户买了也无法使用。</p>
     *
     * <p>顺序要求：**先写幂等流水，成功后再改账户**。流水资源名为
     * {@code "pr-" + sha256(earnKey)}，earnKey 含订单号故全局唯一，
     * 并发发放时只有一个 create 能成功，另一个冲突失败即视为已发放。
     * 若反过来先改账户，两个并发线程都会读到旧值并各自累加，
     * 导致同一笔订单重复发放发布券。</p>
     */
    private Mono<Void> grantPublishTickets(PayOrder order, String earnKey, String wxUserId) {
        var count = Math.max(1, order.getGoodsValue() == null ? 1 : order.getGoodsValue());
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> pointsPersistence.appendRecord(pointsPersistence.newRecord(
                order.getOpenid(), "purchase_publish_ticket", 0, safeBalance(account),
                "道具直购 · 发布券 x" + count, order.getGoodsId(), earnKey, wxUserId))
                // 流水已写入（或冲突即已发放），此时才允许修改账户
                .then(pointsPersistence.getOrCreateAccount(order.getOpenid()))
                .flatMap(latest -> {
                    if (StringUtils.hasText(wxUserId) && !wxUserId.equals(latest.getWxUserId())) {
                        latest.setWxUserId(wxUserId);
                    }
                    var current = latest.getPublishTickets() == null ? 0 : latest.getPublishTickets();
                    latest.setPublishTickets(current + count);
                    return pointsPersistence.saveAccount(latest).then();
                })
                .onErrorResume(ex -> {
                    if (isDuplicateNameError(ex)) {
                        log.info("虚拟支付发布券流水已存在，跳过重复发放：earnKey={}", earnKey);
                        return Mono.empty();
                    }
                    return Mono.error(ex);
                }));
    }

    /**
     * 阅读券：发放到 UserPoints.contentTickets，用于解锁付费文章。
     *
     * <p>顺序与 {@link #grantPublishTickets} 完全一致：**先写幂等流水，成功后再改账户**。
     * 流水资源名由 earnKey（含订单号）派生故全局唯一，并发发放时只有一个 create 能成功，
     * 另一个冲突失败即视为已发放并跳过账户修改，避免同一笔订单重复发券。</p>
     *
     * <p>动作码使用 {@code purchase_content_ticket} 而非 {@code exchange_content_ticket}，
     * 使「积分明细」能区分该券是现金购买还是积分兑换所得。</p>
     */
    private Mono<Void> grantContentTickets(PayOrder order, String earnKey, String wxUserId) {
        var count = Math.max(1, order.getGoodsValue() == null ? 1 : order.getGoodsValue());
        return pointsPersistence.getOrCreateAccount(order.getOpenid())
            .flatMap(account -> pointsPersistence.appendRecord(pointsPersistence.newRecord(
                order.getOpenid(), ContentTicketServiceImpl.PURCHASE_ACTION_CONTENT_TICKET,
                0, safeBalance(account),
                "道具直购 · 阅读券 x" + count, order.getGoodsId(), earnKey, wxUserId))
                // 流水已写入（或冲突即已发放），此时才允许修改账户
                .then(pointsPersistence.getOrCreateAccount(order.getOpenid()))
                .flatMap(latest -> {
                    if (StringUtils.hasText(wxUserId) && !wxUserId.equals(latest.getWxUserId())) {
                        latest.setWxUserId(wxUserId);
                    }
                    var current = safeTickets(latest.getContentTickets());
                    latest.setContentTickets(current + count);
                    return pointsPersistence.saveAccount(latest).then();
                })
                .onErrorResume(ex -> {
                    if (isDuplicateNameError(ex)) {
                        log.info("虚拟支付阅读券流水已存在，跳过重复发放：earnKey={}", earnKey);
                        return Mono.empty();
                    }
                    return Mono.error(ex);
                }));
    }

    /**
     * 装扮：直购即拥有（outfitOwned 追加），佩戴仍由用户在身份中心操作。
     *
     * <p>同样遵循「先写幂等流水，再改账户」的顺序，避免并发下重复追加。
     * 包级可见以便同包测试覆盖限时到期写入与重复购买分支。</p>
     */
    Mono<Void> grantOutfit(PayOrder order, String earnKey, String wxUserId, String outfitKey) {
        if (!StringUtils.hasText(outfitKey)) {
            return Mono.error(new WxAuthException("装扮商品未配置 key"));
        }
        var key = outfitKey.trim();
        // 先取装扮商店配置（限时装扮的到期时间由 durationDays 决定），再改账户。
        // 原实现在账户链路里用 .block() 同步读配置，事件循环线程上会抛异常且被 catch 吞掉，
        // 结果是限时装扮被静默当作永久发放。
        return loadLevelConfig()
            .flatMap(levelConfig -> pointsPersistence.getOrCreateAccount(order.getOpenid())
                .flatMap(account -> {
                    var owned = OwnedOutfitParser.parse(account.getOutfitOwned());
                    // 已拥有且在有效期内：不可发放（前端下单前已拦截，此处为防御性二次拦截）。
                    // 抛专用异常，由 doDeliver 转终态（needs_refund）交人工退款，而不是无限重试。
                    if (OwnedOutfitParser.isOwnedAndValid(owned, key)) {
                        return Mono.error(new NonDeliverableOrderException("你已拥有该装扮，请勿重复购买"));
                    }
                    var description = "道具直购 · 解锁装扮";
                    // 幂等闸门：先写流水，成功后改账户（与单 JVM 锁配合保证恰好一次发放）。
                    return pointsPersistence.appendRecord(pointsPersistence.newRecord(
                        order.getOpenid(), "purchase_outfit", 0, safeBalance(account),
                        description, key, earnKey, wxUserId))
                        .then(pointsPersistence.getOrCreateAccount(order.getOpenid()))
                        .flatMap(latest -> {
                            if (StringUtils.hasText(wxUserId) && !wxUserId.equals(latest.getWxUserId())) {
                                latest.setWxUserId(wxUserId);
                            }
                            var latestOwned = new ArrayList<>(OwnedOutfitParser.parse(latest.getOutfitOwned()));
                            if (!OwnedOutfitParser.isOwnedAndValid(latestOwned, key)) {
                                // 限时装扮：购买时按配置的 durationDays 计算到期时间（0=永久）。
                                var expireAt = expireAtOf(levelConfig, key);
                                latestOwned.add(new OwnedOutfitParser.OwnedOutfit(
                                    key, Instant.now().getEpochSecond(), expireAt));
                                latest.setOutfitOwned(OwnedOutfitParser.write(latestOwned));
                                return pointsPersistence.saveAccount(latest).then();
                            }
                            return Mono.empty();
                        });
                }))
            .onErrorResume(ex -> {
                if (isDuplicateNameError(ex)) {
                    log.info("虚拟支付装扮流水已存在，跳过重复发放：earnKey={}", earnKey);
                    return Mono.empty();
                }
                return Mono.error(ex);
            });
    }

    /** 读取装扮商店配置；读取失败按内置默认值处理（缺配置时按永久，宁可多给不可少给）。 */
    private Mono<LevelConfigDTO> loadLevelConfig() {
        return settingFetcher.get(LevelConfigHelper.groupName())
            .map(LevelConfigHelper::parse)
            .defaultIfEmpty(LevelConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("读取装扮商店配置失败（按默认值处理）：{}", ex.getMessage());
                return Mono.just(LevelConfigHelper.defaults());
            });
    }

    /** 依据装扮商店配置解析到期时间：key 未登记或 durationDays<=0 视为永久（null）。 */
    private static Long expireAtOf(LevelConfigDTO config, String key) {
        var outfits = config == null || config.getOutfits() == null ? List.<OutfitItemDTO>of()
            : config.getOutfits();
        var outfit = outfits.stream()
            .filter(item -> key.equals(item.getKey()))
            .findFirst()
            .orElse(null);
        if (outfit == null || outfit.getDurationDays() <= 0) {
            return null;
        }
        return Instant.now().getEpochSecond() + outfit.getDurationDays() * 86400L;
    }

    private static long safeBalance(pro.uhalo.uni.scheme.UserPoints account) {
        return account.getBalance() == null ? 0L : Math.max(0L, account.getBalance());
    }

    /**
     * 券数量兜底：null 与负数一律按 0 处理。
     *
     * <p>阅读券、发布券均为可空 Integer 字段，历史账户在该字段加入前已存在，
     * 读到的值必然为 null；若不兜底会在累加时抛 NPE，导致发货失败。</p>
     */
    private static int safeTickets(Integer tickets) {
        return tickets == null ? 0 : Math.max(0, tickets);
    }

    private Mono<PayOrder> markDelivered(PayOrder updated) {
        return payOrderPersistence.update(updated)
            .doOnSuccess(saved -> log.info("虚拟支付发货成功：outTradeNo={}, wxOrderId={}, 权益已发放",
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
        // points 仅用于保留历史订单原值（旧积分充值商品），新订单该字段恒为空，不参与发货
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
            // 道具直购不再依赖 points 字段判定发货状态（该字段仅历史积分订单有值），
            // 否则新订单 points 恒为空会导致前端轮询永远拿不到「已到账」。
            if (!delivered) {
                return Mono.just(QueryPayOrderResponse.builder()
                    .outTradeNo(order.getOutTradeNo())
                    .status(order.getStatus())
                    .delivered(false)
                    .message(terminalStatusMessage(order))
                    .build());
            }
            return pointsPersistence.getOrCreateAccount(order.getOpenid())
                .map(account -> QueryPayOrderResponse.builder()
                    .outTradeNo(order.getOutTradeNo())
                    .status(order.getStatus())
                    .delivered(true)
                    .goodsType(order.getGoodsType())
                    .goodsName(order.getGoodsName())
                    .balance(account.getBalance())
                    .build());
        });
    }

    /**
     * 终态订单给前端的明确文案（其余状态返回 null，前端按原有轮询逻辑处理）。
     *
     * <p>重复购买已拥有的装扮时，订单不会变成「已到账」也不会退款成功，
     * 若不给出说明，前端只会轮询到超时并提示「到账确认超时」，用户无从判断发生了什么。</p>
     */
    private static String terminalStatusMessage(PayOrder order) {
        if (STATUS_NEEDS_REFUND.equals(order.getStatus())) {
            return "该权益你已拥有，本次购买未重复发放，请联系站长退款";
        }
        return null;
    }

    /**
     * 后台订单列表：默认只看 {@link #STATUS_NEEDS_REFUND}（需人工退款的重复购买单）。
     *
     * <p>站长从该列表拿走 outTradeNo / wxOrderId / 金额，在微信侧逐一退款；
     * 与 ERROR 日志互为兜底——日志可能被轮转，订单状态是持久化的。</p>
     */
    @Override
    public Mono<AdminPayOrderListResponse> listOrdersForAdmin(String status, int page, int size) {
        var normalizedStatus = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT)
            : STATUS_NEEDS_REFUND;
        var normalizedPage = Math.max(page, 0);
        var normalizedSize = Math.min(Math.max(size, 1), ADMIN_MAX_SIZE);
        return payOrderPersistence.listAll()
            .filter(order -> normalizedStatus.isEmpty() || normalizedStatus.equals(order.getStatus()))
            .collectList()
            .flatMap(orders -> {
                var sorted = orders.stream()
                    .sorted(Comparator.comparing(PayOrder::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
                return wxUserService.loadUserLookup()
                    .map(lookup -> toAdminOrderPage(sorted, lookup, normalizedPage, normalizedSize))
                    .onErrorResume(ex -> {
                        // 昵称只是展示项，查不到不影响退款操作本身
                        log.warn("后台订单列表加载用户信息失败（按无昵称展示）：{}", ex.getMessage());
                        return Mono.just(toAdminOrderPage(sorted, null, normalizedPage, normalizedSize));
                    });
            });
    }

    private static AdminPayOrderListResponse toAdminOrderPage(List<PayOrder> sorted, WxUserLookup lookup,
        int page, int size) {
        var total = sorted.size();
        var from = Math.min(page * size, total);
        var to = Math.min(from + size, total);
        var items = sorted.subList(from, to).stream()
            .map(order -> toAdminOrderItem(order, lookup))
            .toList();
        return AdminPayOrderListResponse.builder()
            .items(items)
            .total(total)
            .hasNext(to < total)
            .page(page)
            .size(size)
            .build();
    }

    private static AdminPayOrderItemDTO toAdminOrderItem(PayOrder order, WxUserLookup lookup) {
        var wxUser = lookup == null ? null : lookup.resolve(order.getOpenid(), order.getWxUserId());
        return AdminPayOrderItemDTO.builder()
            .id(order.getMetadata() == null ? null : order.getMetadata().getName())
            .outTradeNo(order.getOutTradeNo())
            .wxOrderId(order.getWxOrderId())
            .openid(order.getOpenid())
            .nickname(wxUser == null || !StringUtils.hasText(wxUser.getNickname())
                ? "" : wxUser.getNickname())
            .goodsId(order.getGoodsId())
            .goodsName(order.getGoodsName())
            .goodsType(order.getGoodsType())
            .goodsStyle(order.getGoodsStyle())
            .price(order.getPrice())
            .status(order.getStatus())
            .delivered(order.getDelivered())
            .createTime(order.getCreateTime())
            .paidTime(order.getPaidTime())
            .updateTime(order.getUpdateTime())
            .remark(order.getRemark())
            .build();
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
            var paid = isDeliverableStatus(status, order.path("order_id").asText(""));
            // 注意：paid_time 官方定义为「支付/退款时间」，退款单同样有值，
            // 因此只能用于记录时间，不能作为「已支付」的判定依据。
            var paidTime = paid && order.path("paid_time").asLong(0) > 0
                ? Instant.ofEpochSecond(order.path("paid_time").asLong(0))
                : null;
            return new RemoteOrderStatus(paid, status, paidTime,
                order.path("wx_order_id").asText(""), null, null);
        });
    }

    /**
     * 按官方 query_order 的 status 枚举判定订单是否可发货。
     *
     * <p>官方枚举（虚拟支付 - 查询创建的订单）：</p>
     * <pre>
     * 0  订单初始化（未创建成功，不可用于支付）
     * 1  订单创建成功
     * 2  订单已经支付，待发货   ← 可发货
     * 3  订单发货中             ← 可发货
     * 4  订单已发货             ← 可发货
     * 5  订单已经退款
     * 6  订单已经关闭（不可再使用）
     * 7  订单退款失败
     * 8  用户退款完成
     * 9  回收广告金完成
     * 10 分账回退完成
     * </pre>
     *
     * <p>仅 2/3/4 允许发货。原实现用 {@code status >= 2 && status != 5 && status != 6}
     * 的宽泛判定，会把 8（用户退款完成）、7、9、10 全部误判为已支付并发货，造成资损；
     * 且原实现还把 paid_time &gt; 0 作为并列判据，而 paid_time 在退款单上同样有值，
     * 使误判进一步固化。此处改为显式白名单，未知状态一律不发货并记录日志。</p>
     */
    static boolean isDeliverableStatus(int status, String orderId) {
        if (DELIVERABLE_ORDER_STATUSES.contains(status)) {
            return true;
        }
        if (status >= 5) {
            log.warn("虚拟支付查单：订单 {} 状态为 {}（退款/关闭类），不发货", orderId, status);
        } else {
            log.info("虚拟支付查单：订单 {} 状态为 {}（未支付或未知），不发货", orderId, status);
        }
        return false;
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
                var type = textValue(item, "type").toLowerCase();
                var value = item.path("value").asInt(0);
                var style = textValue(item, "style");
                var valid = StringUtils.hasText(goodsId) && StringUtils.hasText(name) && price > 0;
                // 个人主体小程序仅允许道具直购：type 必填且只接受以下两种权益，
                // 留空或 points（现金充值积分）一律不下发，避免形成代币式充值通道。
                //
                // 已下架但 grantGoods 仍保留发货分支的类型（仅用于历史订单履约，
                // 避免升级前「已支付未发货」的订单永久拿不到权益）：
                //   dashboard      数据看板高级版 —— 改为纯积分兑换权益
                //   publish_ticket 发布券 —— 普通用户不能发表瞬间（仅博主可发），
                //                            该券没有任何消费点，卖出去用户也用不上，故下架
                var isGoods = "content_ticket".equals(type) || "outfit".equals(type);
                if (valid && isGoods) {
                    var typeValid = !"outfit".equals(type) || StringUtils.hasText(style);
                    if (typeValid) {
                        goods.add(PayGoodsDTO.builder()
                            .goodsId(goodsId)
                            .name(name)
                            .price(price)
                            .type(type)
                            .value(value)
                            .style(style)
                            .build());
                    }
                } else if (valid) {
                    log.warn("虚拟支付商品被忽略：goodsId={}, type={}（可配置类型为 content_ticket/outfit；"
                            + "数据看板高级版已改为纯积分兑换权益，发布券已下架，且不支持积分充值）",
                        goodsId, type.isEmpty() ? "(未填写)" : type);
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

    /** 微信查单结果（包级可见：同包测试构造发货场景） */
    record RemoteOrderStatus(boolean paid, int status, Instant paidTime, String wxOrderId,
        String transactionId, String mchOrderNo) {
    }

    private record SyncResult(String orderNo, boolean paid) {
    }
}
