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
import pro.uhalo.uni.dto.MemberConfigDTO;
import pro.uhalo.uni.dto.MembershipStatusDTO;
import pro.uhalo.uni.dto.MyPayOrderItemDTO;
import pro.uhalo.uni.dto.MyPayOrderListResponse;
import pro.uhalo.uni.dto.OutfitItemDTO;
import pro.uhalo.uni.dto.PayDataDTO;
import pro.uhalo.uni.dto.PayGoodsDTO;
import pro.uhalo.uni.dto.PayGoodsItemDTO;
import pro.uhalo.uni.dto.PayPublicConfigDTO;
import pro.uhalo.uni.dto.QueryPayOrderRequest;
import pro.uhalo.uni.dto.QueryPayOrderResponse;
import pro.uhalo.uni.exception.NonDeliverableOrderException;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PayOrderPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PayOrder;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.services.MembershipService;
import pro.uhalo.uni.services.TicketRewardService;
import pro.uhalo.uni.services.VirtualPayService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.WxAccessTokenProvider;
import pro.uhalo.uni.utils.WxRequestParser;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import pro.uhalo.uni.utils.XPaySigner;
import pro.uhalo.uni.utils.XmlMessageParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    /** 道具图片 URL 长度上限：正常的图床地址远用不了 512 字符，超限基本是贴错了内容 */
    static final int GOODS_IMAGE_MAX_LENGTH = 512;
    /**
     * 失效状态：待支付订单超过有效期后的展示状态。
     *
     * <p><b>只在下发时派生，不落库</b>：用户可能在有效期最后一分钟付款、而微信的发货推送
     * 迟到几分钟才到——如果把订单在库里改成终态，那笔钱对应的权益就可能发不出去。
     * 派生状态不会影响发货链路（发货只按 outTradeNo 找订单，不看这个展示状态），
     * 迟到的推送照样能把订单补成「已到账」。</p>
     */
    static final String STATUS_EXPIRED = "expired";

    /** 待支付订单的有效期：超过这个时间未付款即视为已失效（前端文案与此保持一致） */
    static final Duration PENDING_EXPIRE = Duration.ofMinutes(15);

    /**
     * 终态：已支付但权益不可发放（重复购买），需站长人工退款。
     *
     * <p>与 {@link #STATUS_PENDING} 分开是为了让兜底查单（按 status=pending 筛选）
     * 不再拾取该订单，避免每 5 分钟无限重试；同时该状态可被后台退款清单直接筛选出来。</p>
     */
    private static final String STATUS_NEEDS_REFUND = "needs_refund";

    /** 会员卡发货的流水动作码：与 purchase_content_ticket 区分，便于对账时识别会员收入 */
    static final String PURCHASE_MEMBERSHIP_ACTION = "purchase_membership";

    /** AI 定制内容发货的流水动作码（同样是为了对账时能按收入类型拆开） */
    static final String PURCHASE_AI_CONTENT_ACTION = "purchase_ai_content";
    private static final String NEEDS_REFUND_REMARK_PREFIX = "重复购买：权益已拥有，未重复发放，需人工退款";

    /** 后台订单列表每页最大条数（防止一次拉爆内存） */
    private static final int ADMIN_MAX_SIZE = 200;

    /** 用户侧「我的订单」每页最大条数（手机屏幕不需要更多） */
    private static final int MY_ORDER_MAX_SIZE = 50;

    /** 插件内统一使用东八区：订单时间展示、活动截止时间解释都走它 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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
    private final WxAccessTokenProvider wxAccessTokenProvider;
    private final TicketRewardService ticketRewardService;
    private final MembershipService membershipService;
    private final AiContentEntitlementService aiContentEntitlementService;
    /**
     * 相对链接 → 站点外链。附件库「本地存储」的附件是相对路径（/upload/…），
     * 小程序 image 组件解析不了，必须拼上外部访问地址再下发。
     * 用 ExternalLinkProcessor 而不是自己拼 ExternalUrlSupplier：getConfigs 通道
     * （UniHaloServiceImpl）用的就是它且线上已验证可用，两条通道必须同一套口径。
     */
    private final ExternalLinkProcessor externalLinkProcessor;

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
                // 套餐里的装扮子项同样要过这一关，否则会「付了套餐钱、装扮那部分变成退款纠纷」
                var preCheck = validateOutfitsBeforeOrder(goods, normalizedOpenid);
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

    /**
     * 下单前的装扮校验：key 必须已登记、且用户尚未拥有。
     *
     * <p>单装扮商品与套餐中的装扮子项走同一套判定。单装扮商品还会把名称换成
     * 装扮商店里的名称（装扮商店是装扮的唯一定义源，避免两处名称不一致）。</p>
     */
    private Mono<PayGoodsDTO> validateOutfitsBeforeOrder(PayGoodsDTO goods, String openid) {
        var outfitKeys = outfitKeysOf(goods);
        if (outfitKeys.isEmpty()) {
            return Mono.just(goods);
        }
        return settingFetcher.get(LevelConfigHelper.groupName())
            .map(LevelConfigHelper::parse)
            .defaultIfEmpty(LevelConfigHelper.defaults())
            .flatMap(levelConfig -> {
                // 先做纯内存的配置校验：key 未登记属于站长配置错误，直接失败即可，
                // 不必再去读一次账户（也让这类错误不再依赖数据库可用性）
                for (var key : outfitKeys) {
                    if (findOutfit(levelConfig, key) == null) {
                        return Mono.error(new WxAuthException(
                            "装扮配置错误：该装扮商品未在装扮商店登记，请联系站长"));
                    }
                }
                return pointsPersistence.getOrCreateAccount(openid)
                    .flatMap(account -> {
                        var owned = OwnedOutfitParser.parse(account.getOutfitOwned());
                        for (var key : outfitKeys) {
                            if (OwnedOutfitParser.isOwnedAndValid(owned, key)) {
                                return Mono.error(new WxAuthException("你已拥有该装扮（"
                                    + findOutfit(levelConfig, key).getName() + "），请勿重复购买"));
                            }
                        }
                        // 单装扮商品：名称以装扮商店配置为准（单一装扮定义源）。
                        // 套餐保留自己的名称——套餐名是站长对整包的定价描述，不该被子项名称覆盖。
                        if ("outfit".equals(goods.getType())) {
                            var matched = findOutfit(levelConfig, goods.getStyle());
                            if (matched != null) {
                                return Mono.just(PayGoodsDTO.builder()
                                    .goodsId(goods.getGoodsId())
                                    .name(matched.getName())
                                    .price(goods.getPrice())
                                    .type(goods.getType())
                                    .value(goods.getValue())
                                    .style(goods.getStyle())
                                    .originalPrice(goods.getOriginalPrice())
                                    .promoEndsAt(goods.getPromoEndsAt())
                                    .image(goods.getImage())
                                    .build());
                            }
                        }
                        return Mono.just(goods);
                    });
            });
    }

    private static OutfitItemDTO findOutfit(LevelConfigDTO levelConfig, String key) {
        if (levelConfig == null || levelConfig.getOutfits() == null || !StringUtils.hasText(key)) {
            return null;
        }
        return levelConfig.getOutfits().stream()
            .filter(item -> key.equals(item.getKey()))
            .findFirst()
            .orElse(null);
    }

    /** 商品涉及的装扮 key：单装扮取 style，套餐取其中的 outfit 子项。 */
    private static List<String> outfitKeysOf(PayGoodsDTO goods) {
        if ("outfit".equals(goods.getType())) {
            return StringUtils.hasText(goods.getStyle()) ? List.of(goods.getStyle().trim()) : List.of();
        }
        if (!"bundle".equals(goods.getType()) || goods.getItems() == null) {
            return List.of();
        }
        return goods.getItems().stream()
            .filter(item -> "outfit".equals(item.getType()))
            .map(PayGoodsItemDTO::getStyle)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    /** 套餐子项序列化为 JSON 落库；非套餐返回 null。 */
    private static String serializeGoodsItems(PayGoodsDTO goods) {
        if (!"bundle".equals(goods.getType()) || goods.getItems() == null
            || goods.getItems().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(goods.getItems());
        } catch (Exception ex) {
            // 序列化失败必须抛：静默返回 null 会让订单在发货时找不到子项，
            // 结果是用户付了钱拿不到任何权益，还难以定位
            throw new WxAuthException("套餐配置序列化失败，无法下单");
        }
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
        // 套餐子项必须随订单落库：发货发生在另一次请求里，届时读不到下单时的商品配置
        order.setGoodsItems(serializeGoodsItems(goods));
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
                if (isPendingExpired(order)) {
                    // 已过有效期：不必再去微信查单（那张单早已不能用），直接告诉前端已失效。
                    // 注意：这只是展示口径——若用户其实在有效期内付了款、发货推送迟到，
                    // 推送链路仍会把订单补成「已到账」（发货只看 outTradeNo，不看这个状态）。
                    return Mono.just(QueryPayOrderResponse.builder()
                        .outTradeNo(order.getOutTradeNo())
                        .status(STATUS_EXPIRED)
                        .delivered(false)
                        .goodsType(order.getGoodsType())
                        .goodsName(order.getGoodsName())
                        .message("订单已失效（超过 " + PENDING_EXPIRE.toMinutes() + " 分钟未支付）")
                        .build());
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
                        // 用 defer 包一层：否则赠券配置会在发货之前就被读取一次，
                        // 而且发货失败（如重复购买的终态分支）时这次读取是白费的
                        .then(Mono.defer(() -> grantFirstPurchaseBonus(order.getOpenid(), wxUserId)))
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
            case "membership" -> grantMembership(order, earnKey, wxUserId);
            case "ai_content" -> grantAiContent(order, earnKey, wxUserId);
            case "bundle" -> grantBundle(order, earnKey, wxUserId);
            default -> {
                log.error("虚拟支付发货跳过：订单 {} 道具类型非法或缺失：{}（可配置类型为 "
                        + "content_ticket/outfit/membership/ai_content/bundle；dashboard 与 "
                        + "publish_ticket 已下架，仅用于历史订单履约）",
                    order.getOutTradeNo(), type.isEmpty() ? "(未填写)" : type);
                // 未知类型同样属于「重试不会成功」，转终态而不是无限重试
                yield Mono.error(new NonDeliverableOrderException(
                    "未知的道具类型（" + (type.isEmpty() ? "未填写" : type) + "），发货失败"));
            }
        };
    }

    /**
     * 会员卡：一次性付款，延长会员到期时间。
     *
     * <p><b>合规边界</b>：这是「单次购买的有效期权益」，不是自动续费订阅。
     * 微信虚拟支付的适用场景包含「会员订阅：VIP 会员、专属权益包」，
     * 道具直购（short_series_goods）本身就支持「会员、课程等单品，支持单次购买」，
     * 因此与插件里已在跑的限时装扮结构一致。自动续费订阅需单独签约、
     * 协议报审与交易保证金，本插件不提供。</p>
     *
     * <p>到期时间<b>叠加</b>而不是重置：在有效期内续费时从当前到期时间往后加天数，
     * 否则用户会白白损失剩余天数。</p>
     */
    private Mono<Void> grantMembership(PayOrder order, String earnKey, String wxUserId) {
        return membershipService.loadConfig()
            .flatMap(memberConfig -> {
                if (!memberConfig.isEnabled()) {
                    log.error("虚拟支付会员卡发货失败：订单 {} 使用了 membership 类型，"
                            + "但「会员卡」配置未开启（请先在插件设置中开启，否则用户付钱后拿不到权益）",
                        order.getOutTradeNo());
                    // 用 NonDeliverableOrderException 而不是普通业务异常：
                    // 这个原因重试多少次都不会成功，留在 pending 只会被兜底查单无限重试，
                    // 用户既拿不到会员也没有退款入口
                    return Mono.error(new NonDeliverableOrderException(
                        "会员卡功能未开启：请在插件设置中开启「会员卡」后重试发货"));
                }
                var days = order.getGoodsValue() == null || order.getGoodsValue() <= 0
                    ? memberConfig.getDefaultDays() : order.getGoodsValue();
                return pointsPersistence.getOrCreateAccount(order.getOpenid())
                    .flatMap(account -> {
                        var record = pointsPersistence.newRecord(order.getOpenid(),
                            PURCHASE_MEMBERSHIP_ACTION, 0, safeBalance(account),
                            "道具直购 · 会员 " + days + " 天", order.getGoodsId(), earnKey, wxUserId);
                        // 幂等闸门：先写流水，成功后改账户（与其它发货分支同一顺序）
                        return pointsPersistence.appendRecord(record)
                            .then(pointsPersistence.getOrCreateAccount(order.getOpenid()))
                            .flatMap(latest -> {
                                if (StringUtils.hasText(wxUserId)
                                    && !wxUserId.equals(latest.getWxUserId())) {
                                    latest.setWxUserId(wxUserId);
                                }
                                var base = MemberConfigHelper.isActive(latest.getMemberExpireAt())
                                    ? latest.getMemberExpireAt() : Instant.now();
                                latest.setMemberExpireAt(base.plus(days, ChronoUnit.DAYS));
                                return pointsPersistence.saveAccount(latest).then();
                            })
                            .onErrorResume(ex -> {
                                if (isDuplicateNameError(ex)) {
                                    log.info("虚拟支付会员流水已存在，跳过重复发放：earnKey={}", earnKey);
                                    return Mono.empty();
                                }
                                return Mono.error(ex);
                            });
                    })
                    // 会员附赠券：会员权益本身已发放成功，赠券失败不回滚主权益
                    .then(grantMembershipBonusTickets(order, memberConfig));
            });
    }

    /** 会员附赠券：与订单绑定的一次性赠券，不影响主权益的发放结果。 */
    private Mono<Void> grantMembershipBonusTickets(PayOrder order, MemberConfigDTO memberConfig) {
        var count = memberConfig.getBonusTickets();
        if (count <= 0) {
            return Mono.empty();
        }
        var earnKey = order.getOpenid() + ":purchase:" + order.getOutTradeNo() + ":member_bonus";
        return ticketRewardService.grant(order.getOpenid(), count,
                TicketRewardService.ACTION_MEMBER_BONUS,
                "会员开通礼 · 阅读券 x" + count, earnKey, order.getGoodsId(), order.getWxUserId())
            .doOnNext(granted -> {
                if (granted > 0) {
                    log.info("会员开通礼已发放：openid={}, 阅读券 x{}", order.getOpenid(), granted);
                }
            })
            .onErrorResume(ex -> {
                log.warn("会员开通礼发放失败（不影响会员权益）：outTradeNo={}, {}",
                    order.getOutTradeNo(), ex.getMessage());
                return Mono.just(0);
            })
            .then();
    }

    /**
     * AI 定制内容：一次性买断 + 有效期（与会员卡同构）。
     *
     * <p>发货只做一件事——延长 {@code UserPoints.aiContentExpireAt}：用户能配什么、
     * 简报怎么生成，全部由他自己的偏好与站长配置决定，不在这里写死。
     * 天数取商品「道具数量」，填 0 表示永久买断（用远期时间表示，见
     * {@code AiContentConfigHelper.PERMANENT_EXPIRE_AT}）。</p>
     */
    private Mono<Void> grantAiContent(PayOrder order, String earnKey, String wxUserId) {
        return aiContentEntitlementService.loadConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    log.error("AI 定制内容发货失败：订单 {} 使用了 ai_content 类型，"
                            + "但「AI 定制内容」配置未开启（请先在插件设置中开启，"
                            + "否则用户付钱后拿不到权益）",
                        order.getOutTradeNo());
                    return Mono.error(new NonDeliverableOrderException(
                        "AI 定制内容功能未开启：请在插件设置中开启后重试发货"));
                }
                var days = order.getGoodsValue() == null ? 0 : order.getGoodsValue();
                return pointsPersistence.getOrCreateAccount(order.getOpenid())
                    .flatMap(account -> {
                        var record = pointsPersistence.newRecord(order.getOpenid(),
                            PURCHASE_AI_CONTENT_ACTION, 0, safeBalance(account),
                            "道具直购 · AI 定制内容 " + (days > 0 ? days + " 天" : "永久"),
                            order.getGoodsId(), earnKey, wxUserId);
                        // 幂等闸门：先写流水，成功后改账户（与其它发货分支同一顺序）
                        return pointsPersistence.appendRecord(record)
                            .then(pointsPersistence.getOrCreateAccount(order.getOpenid()))
                            .flatMap(latest -> {
                                if (StringUtils.hasText(wxUserId)
                                    && !wxUserId.equals(latest.getWxUserId())) {
                                    latest.setWxUserId(wxUserId);
                                }
                                latest.setAiContentExpireAt(
                                    AiContentConfigHelper.extend(latest.getAiContentExpireAt(),
                                        days));
                                // 同时发放生成次数（天数 × 每天次数，上限见配置）：
                                // 与积分兑换通道同一条口径
                                var added = AiContentConfigHelper.creditsForDays(days, config);
                                latest.setAiContentCredits(
                                    AiContentEntitlementService.creditsOf(latest) + added);
                                return pointsPersistence.saveAccount(latest)
                                    .doOnNext(saved -> log.info(
                                        "AI 定制内容权益已发放：outTradeNo={}, 购买 {} 天, 到期={}",
                                        order.getOutTradeNo(),
                                        days > 0 ? String.valueOf(days) : "永久",
                                        AiContentConfigHelper.formatExpireAt(
                                            saved.getAiContentExpireAt())))
                                    .then();
                            })
                            .onErrorResume(ex -> {
                                if (isDuplicateNameError(ex)) {
                                    log.info("虚拟支付 AI 定制内容流水已存在，跳过重复发放：earnKey={}",
                                        earnKey);
                                    return Mono.empty();
                                }
                                return Mono.error(ex);
                            });
                    });
            });
    }

    /**
     * 套餐（礼包）：一条商品包含多种权益，逐项发放。
     *
     * <p>做法是给每个子项复制一份订单再调用已有的发货分支，而不是在发货逻辑里加一层分支判断——
     * 这样「先写幂等流水、成功后再改账户」的顺序、以及各权益自身的校验（装扮是否已拥有、
     * 限时到期时间等）全部原样复用，不必在套餐路径上重新实现一遍最容易出错的部分。</p>
     *
     * <p>子项的 earnKey 在主订单 earnKey 后加下标，因此每个子项各自幂等：
     * 中途失败重试时已发放的子项会命中「流水已存在」而跳过，不会重复发放。</p>
     */
    private Mono<Void> grantBundle(PayOrder order, String earnKey, String wxUserId) {
        var items = parseBundleItems(order.getGoodsItems());
        if (items.isEmpty()) {
            log.error("虚拟支付套餐发货失败：订单 {} 没有可用的子项配置（goodsItems 为空或解析失败）",
                order.getOutTradeNo());
            // 同上：配置缺失属于「重试也不会成功」，必须转终态交人工处理
            return Mono.error(new NonDeliverableOrderException(
                "套餐子项配置缺失：请检查该商品的套餐配置后重试发货"));
        }
        return Flux.fromIterable(items)
            .concatMap(item -> {
                var itemOrder = copyForBundleItem(order, item);
                return grantGoods(itemOrder, earnKey + ":" + item.index(), wxUserId);
            })
            .then();
    }

    /** 套餐子项：订单副本（只替换权益相关字段），让既有发货分支可直接复用。 */
    private static PayOrder copyForBundleItem(PayOrder order, BundleItem item) {
        var copy = new PayOrder();
        copy.setOpenid(order.getOpenid());
        copy.setWxUserId(order.getWxUserId());
        copy.setOutTradeNo(order.getOutTradeNo());
        copy.setGoodsId(order.getGoodsId());
        copy.setGoodsName(order.getGoodsName());
        copy.setPrice(order.getPrice());
        copy.setQuantity(order.getQuantity());
        copy.setGoodsType(item.type());
        copy.setGoodsValue(item.value());
        copy.setGoodsStyle(item.style());
        return copy;
    }

    /** 解析订单里的套餐子项 JSON。解析失败视为配置缺失，交由调用方转失败处理。 */
    private static List<BundleItem> parseBundleItems(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            var node = OBJECT_MAPPER.readTree(rawJson);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            var items = new ArrayList<BundleItem>();
            var index = 0;
            for (var itemNode : node) {
                var type = textValue(itemNode, "type");
                if (!StringUtils.hasText(type)) {
                    index++;
                    continue;
                }
                items.add(new BundleItem(index++,
                    type.trim().toLowerCase(),
                    itemNode.path("value").asInt(1),
                    textValue(itemNode, "style")));
            }
            return List.copyOf(items);
        } catch (Exception ex) {
            log.warn("解析套餐子项失败：{}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 套餐子项。
     *
     * @param index 在主订单里的下标，用于派生各子项独立的幂等键——中途失败重试时
     *              已发放的子项会命中「流水已存在」而跳过
     */
    private record BundleItem(int index, String type, int value, String style) {
    }


    /**
     * 首单礼：用户在本站的<b>第一笔</b>成功订单额外赠送阅读券。
     *
     * <p>转化漏斗上最有效的一环——「第一次付钱」的心理门槛远高于第二次。
     * 首购加送一张券，等于让用户用同一笔钱拿到两篇内容，付过一次之后复购才可能发生。</p>
     *
     * <p>幂等靠固定的 earnKey（{@code openid + ":ticket_first_purchase:first"}）：
     * 只有第一笔走到这里的订单能写成功流水，后续订单一律命中「已存在」而跳过，
     * 因此无需回查该用户的历史订单即可判定「是否首单」。</p>
     *
     * <p>赠券失败不影响发货结果：券是额外奖励，不能因为送券出错让已付款用户拿不到主权益。</p>
     */
    private Mono<Void> grantFirstPurchaseBonus(String openid, String wxUserId) {
        return ticketRewardService.loadConfig()
            .flatMap(config -> {
                var count = config.getFirstPurchaseTickets();
                if (count <= 0) {
                    return Mono.just(0);
                }
                var earnKey = openid + ":" + TicketRewardService.ACTION_FIRST_PURCHASE + ":first";
                return ticketRewardService.grant(openid, count,
                    TicketRewardService.ACTION_FIRST_PURCHASE,
                    "首单礼 · 阅读券 x" + count, earnKey, "首单礼", wxUserId);
            })
            .doOnNext(granted -> {
                if (granted > 0) {
                    log.info("虚拟支付首单礼已发放：openid={}, 阅读券 x{}", openid, granted);
                }
            })
            .onErrorResume(ex -> {
                log.warn("虚拟支付首单礼发放失败（不影响发货）：openid={}, {}", openid, ex.getMessage());
                return Mono.just(0);
            })
            .then();
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
            .flatMap(account -> {
                var record = pointsPersistence.newRecord(
                    order.getOpenid(), ContentTicketServiceImpl.PURCHASE_ACTION_CONTENT_TICKET,
                    0, safeBalance(account),
                    "道具直购 · 阅读券 x" + count, order.getGoodsId(), earnKey, wxUserId);
                // 券变动量单独记账：后台看板据此区分「现金买券」与「免费赠券」的发放量
                record.setTickets(count);
                return pointsPersistence.appendRecord(record)
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
                    });
            });
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

    /**
     * 用户侧「我的订单」：按 openid 列出自己的订单，创建时间倒序分页。
     *
     * <p>此前用户付完钱只能靠 {@code outTradeNo} 单查，页面一旦丢掉单号就无从查证；
     * 订单落到「需人工退款」终态时前端只能弹一句「联系站长」，这是最伤信任的路径。
     * 有了这个列表，待支付、已到账、需退款都能自助查。</p>
     *
     * <p>安全边界：只按 openid 过滤，绝不接受调用方传入的 openid；
     * 微信平台单号不下发（见 {@link MyPayOrderItemDTO}）。</p>
     */
    @Override
    public Mono<MyPayOrderListResponse> listMyOrders(String openid, int page, int size) {
        var normalizedOpenid = requireOpenid(openid);
        var normalizedPage = Math.max(page, 0);
        var normalizedSize = Math.min(Math.max(size, 1), MY_ORDER_MAX_SIZE);
        return payOrderPersistence.listByOpenid(normalizedOpenid)
            .collectList()
            .map(orders -> {
                var sorted = orders.stream()
                    .sorted(Comparator.comparing(PayOrder::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
                var total = sorted.size();
                var from = Math.min(normalizedPage * normalizedSize, total);
                var to = Math.min(from + normalizedSize, total);
                return MyPayOrderListResponse.builder()
                    .items(sorted.subList(from, to).stream()
                        .map(VirtualPayServiceImpl::toMyOrderItem)
                        .toList())
                    .total(total)
                    .page(normalizedPage)
                    .size(normalizedSize)
                    .hasMore(to < total)
                    .build();
            });
    }

    private static MyPayOrderItemDTO toMyOrderItem(PayOrder order) {
        var quantity = order.getQuantity() == null ? 1 : Math.max(1, order.getQuantity());
        var price = order.getPrice() == null ? 0L : Math.max(0L, order.getPrice());
        // 待支付超过有效期的订单，在下发时改写成「已失效」（库里保持 pending，见 STATUS_EXPIRED 注释）
        var expired = isPendingExpired(order);
        return MyPayOrderItemDTO.builder()
            .outTradeNo(order.getOutTradeNo())
            .goodsName(StringUtils.hasText(order.getGoodsName()) ? order.getGoodsName() : "权益道具")
            .goodsType(order.getGoodsType())
            .goodsTypeLabel(goodsTypeLabelOf(order.getGoodsType()))
            .price(price)
            .quantity(quantity)
            .amount(price * quantity)
            .status(expired ? STATUS_EXPIRED : order.getStatus())
            .statusLabel(statusLabelOf(order))
            .paidTime(formatOrderTime(order.getPaidTime()))
            .createTime(formatOrderTime(order.getCreateTime()))
            .deliverTime(formatOrderTime(order.getDeliverTime()))
            // 备注里含「需人工退款」等站长侧口径，仅在需人工处理时给出
            .remark(STATUS_NEEDS_REFUND.equals(order.getStatus()) ? order.getRemark() : null)
            .build();
    }

    /**
     * 用户可读的状态说明。
     *
     * <p>文案取向：告诉用户「现在该怎么办」，而不是只报一个状态名。
     * 尤其 pending 必须说清「已扣款会补发」——小程序支付回调丢失时用户最慌的就是这个。</p>
     */
    /**
     * 待支付订单是否已过有效期。
     *
     * <p>只对 pending 生效：已到账/需退款/已关闭都是终态，不受时间影响。
     * 没有创建时间的旧订单按「不失效」处理——宁可多显示一条待支付，也不要凭空空判死一笔订单。</p>
     */
    static boolean isPendingExpired(PayOrder order) {
        if (order == null || !STATUS_PENDING.equals(order.getStatus())) {
            return false;
        }
        var createTime = order.getCreateTime();
        return createTime != null && createTime.plus(PENDING_EXPIRE).isBefore(Instant.now());
    }

    private static String statusLabelOf(PayOrder order) {
        if (isPendingExpired(order)) {
            return "已失效（超过 " + PENDING_EXPIRE.toMinutes() + " 分钟未支付，可重新下单）";
        }
        var status = order.getStatus() == null ? "" : order.getStatus();
        return switch (status) {
            case STATUS_PENDING -> "待支付（" + PENDING_EXPIRE.toMinutes() + " 分钟内有效；若已扣款请稍候，系统会自动补发到账）";
            case STATUS_DELIVERED -> "已到账";
            case STATUS_CLOSED -> "已关闭";
            case STATUS_NEEDS_REFUND -> "需人工处理（该笔权益未发放，请联系站长退款）";
            default -> StringUtils.hasText(status) ? status : "未知状态";
        };
    }

    private static String goodsTypeLabelOf(String goodsType) {
        var type = goodsType == null ? "" : goodsType.trim().toLowerCase();
        return switch (type) {
            case "content_ticket" -> "阅读券";
            case "outfit" -> "装扮";
            case "dashboard" -> "数据看板高级版";
            case "publish_ticket" -> "发布券";
            default -> "权益道具";
        };
    }

    private static String formatOrderTime(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).format(ORDER_TIME_FMT);
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

    /**
     * 取 access_token。
     *
     * <p>缓存与换取交给 {@link WxAccessTokenProvider}（全插件共用一份，避免多处缓存互相挤掉）；
     * 错误类型保持 {@code WxAuthException}，支付接口对外的错误语义不变。</p>
     */
    private Mono<String> ensureAccessToken() {
        return wxAccessTokenProvider.token()
            .onErrorMap(IllegalStateException.class,
                ex -> new WxAuthException(ex.getMessage()));
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
                // 个人主体小程序仅允许道具直购：type 必填且只接受以下权益，
                // 留空或 points（现金充值积分）一律不下发，避免形成代币式充值通道。
                //
                //   content_ticket 阅读券（解锁付费文章）
                //   outfit         装扮（一次性拥有或限时）
                //   membership     会员卡（单次购买的有效期权益，官方适用场景明确包含）
                //   ai_content     AI 定制内容（单次购买的有效期权益：用户自配偏好 → 每日简报）
                //   bundle         套餐（一条商品含多种权益，子项逐个发放）
                //
                // 已下架但 grantGoods 仍保留发货分支的类型（仅用于历史订单履约，
                // 避免升级前「已支付未发货」的订单永久拿不到权益）：
                //   dashboard      数据看板高级版 —— 改为纯积分兑换权益
                //   publish_ticket 发布券 —— 普通用户不能发表瞬间（仅博主可发），
                //                            该券没有任何消费点，卖出去用户也用不上，故下架
                var isGoods = "content_ticket".equals(type) || "outfit".equals(type)
                    || "membership".equals(type) || "ai_content".equals(type)
                    || "bundle".equals(type);
                if (valid && isGoods) {
                    var items = "bundle".equals(type) ? parseGoodsItems(item) : List.<PayGoodsItemDTO>of();
                    var typeValid = switch (type) {
                        // 装扮必须给 key，否则用户付钱后列表里不会出现这一行
                        case "outfit" -> StringUtils.hasText(style);
                        // 空套餐（一个子项都没有）会被忽略告警：卖出去只会产生退款纠纷
                        case "bundle" -> !items.isEmpty();
                        default -> true;
                    };
                    if (typeValid) {
                        goods.add(PayGoodsDTO.builder()
                            .goodsId(goodsId)
                            .name(name)
                            .price(price)
                            .type(type)
                            .value(value)
                            .style(style)
                            .items(items)
                            .originalPrice(resolveOriginalPrice(item, price))
                            .promoEndsAt(resolvePromoEndsAt(item))
                            .image(sanitizeGoodsImage(textValue(item, "image"), goodsId,
                                externalLinkProcessor::processLink))
                            .build());
                    } else {
                        log.warn("虚拟支付商品被忽略（配置不完整）：goodsId={}, type={}"
                                + "（装扮需填 style；套餐需至少一个有效子项）", goodsId, type);
                    }
                } else if (valid) {
                    log.warn("虚拟支付商品被忽略：goodsId={}, type={}（可配置类型为 "
                            + "content_ticket/outfit/membership/ai_content/bundle；"
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

    /**
     * 解析套餐（bundle）的子项。
     *
     * <p>只接受三种可发权益：content_ticket / outfit / membership。
     * 装扮子项必须给 style，且 value 恒为 1（装扮是唯一的，数量没有意义）；
     * 其余子项 value 至少为 1，否则「0 张券 / 0 天会员」这种子项会静默不发放，
     * 用户按套餐价付了钱却少拿权益。</p>
     *
     * <p>非法子项一律丢弃并告警，而不是让整条套餐不下发——站长配错一个子项
     * 就让已上架的套餐从商城消失，比少发一个子项更难排查。</p>
     */
    private static List<PayGoodsItemDTO> parseGoodsItems(JsonNode goodsNode) {
        var itemsNode = goodsNode.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return List.of();
        }
        var items = new ArrayList<PayGoodsItemDTO>();
        for (var itemNode : itemsNode) {
            var type = textValue(itemNode, "type");
            var normalized = type == null ? "" : type.trim().toLowerCase();
            if (!"content_ticket".equals(normalized) && !"outfit".equals(normalized)
                && !"membership".equals(normalized)) {
                log.warn("虚拟支付套餐子项被忽略：goodsId={}, 子项类型={}（可配置 "
                        + "content_ticket/outfit/membership）",
                    textValue(goodsNode, "goodsId"), normalized.isEmpty() ? "(未填写)" : normalized);
                continue;
            }
            var style = textValue(itemNode, "style");
            if ("outfit".equals(normalized) && !StringUtils.hasText(style)) {
                log.warn("虚拟支付套餐子项被忽略：goodsId={}, 装扮子项未填 style（用户付钱后拿不到该装扮）",
                    textValue(goodsNode, "goodsId"));
                continue;
            }
            var value = Math.max(1, itemNode.path("value").asInt(1));
            items.add(PayGoodsItemDTO.builder()
                .type(normalized)
                .value("outfit".equals(normalized) ? 1 : value)
                .style(style)
                .build());
        }
        return List.copyOf(items);
    }

    /**
     * 解析划线原价：必须高于现价才生效，否则丢弃。     *
     * <p>原价倒挂或等于现价都不构成「优惠」，展示出来只会让用户觉得被套路；
     * 站长把原价填得比现价低（例如现价改了活动价却忘了改原价）时，
     * 这里静默丢弃并留下告警，避免前端出现「￥6 ~~￥5~~」这种明显错误。</p>
     */
    private static Long resolveOriginalPrice(JsonNode item, long price) {
        var original = item.path("originalPrice").asLong(0);
        if (original <= price) {
            if (original > 0) {
                log.warn("虚拟支付商品划线原价无效已忽略：goodsId={}, 原价={}分, 现价={}分"
                        + "（原价必须高于现价）",
                    textValue(item, "goodsId"), original, price);
            }
            return null;
        }
        return original;
    }

    /**
     * 校验道具图片 URL，非法值丢弃并告警（返回 null，前端回落到类型图标）。
     *
     * <p>Halo 附件库里「本地存储」的附件给的是相对路径（如 {@code /upload/ai.png}），
     * 小程序的 image 组件解析不了，这里经 {@code relativeLinkResolver}
     * （{@link ExternalLinkProcessor#processLink}，与 getConfigs 通道同一实现）
     * 拼上站点外部访问地址后再下发；S3 等对象存储的附件本身就是完整 URL，原样通过。
     * 其它取值（相对文件名、data URI 等）一律丢弃——小程序的 image 既不支持相对路径，
     * 也不该把内联内容塞进下发给全量用户的配置。</p>
     *
     * <p>图片域名还需加入小程序后台的 downloadFile 合法域名，否则真机加载不出
     * （开发工具里看不出来）；站点自己的域名通常已在白名单里。</p>
     */
    static String sanitizeGoodsImage(String raw, String goodsId,
        java.util.function.UnaryOperator<String> relativeLinkResolver) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        var value = raw.trim();
        var lowered = value.toLowerCase();
        if (lowered.startsWith("https://") || lowered.startsWith("http://")) {
            return checkGoodsImageLength(value, goodsId);
        }
        if (value.startsWith("/") && !value.startsWith("//") && relativeLinkResolver != null) {
            String resolved = null;
            try {
                resolved = relativeLinkResolver.apply(value);
            } catch (Exception ex) {
                log.warn("虚拟支付商品图片外链解析失败已忽略：goodsId={}, image={}, error={}",
                    goodsId, value, ex.getMessage());
            }
            if (!StringUtils.hasText(resolved)) {
                log.warn("虚拟支付商品图片相对路径解析结果为空已忽略：goodsId={}, image={}"
                        + "（请检查 Halo 的外部访问地址设置）", goodsId, value);
                return null;
            }
            var resolvedLower = resolved.toLowerCase();
            if (resolvedLower.startsWith("https://") || resolvedLower.startsWith("http://")) {
                return checkGoodsImageLength(resolved, goodsId);
            }
            log.warn("虚拟支付商品图片解析后仍不是绝对地址已忽略：goodsId={}, image={}"
                    + "（请检查 Halo 的外部访问地址设置）", goodsId, resolved);
            return null;
        }
        log.warn("虚拟支付商品图片无效已忽略：goodsId={}（应为 http(s):// 完整地址"
            + "或 Halo 附件库选择的链接）", goodsId);
        return null;
    }

    private static String checkGoodsImageLength(String value, String goodsId) {
        if (value.length() > GOODS_IMAGE_MAX_LENGTH) {
            log.warn("虚拟支付商品图片 URL 过长已忽略：goodsId={}, 长度={}（上限 {}）",
                goodsId, value.length(), GOODS_IMAGE_MAX_LENGTH);
            return null;
        }
        return value;
    }

    /**
     * 解析限时截止时间，返回 Unix 秒；无活动或已过期返回 null。
     *
     * <p>兼容两类取值：</p>
     * <ul>
     *   <li><b>日期时间字符串</b>（设置页 datetime-local 选择器产出，如 {@code 2026-12-31T23:59}）。
     *       这种值<b>不带时区</b>，必须按 Asia/Shanghai 解释——站长在选择器里看到的就是本地时间，
     *       若按 UTC 解释会整体偏 8 小时，活动会提前一天结束。</li>
     *   <li><b>纯数字</b>：兼容早期直接填 Unix 秒的配置，不能让已配好的活动失效。</li>
     * </ul>
     *
     * <p>只填日期不填时间时取当天 23:59:59——「截止到某天」的自然含义是那天结束为止。</p>
     *
     * <p>无法识别的值会记 WARNING 后按「无活动」处理。必须留日志：
     * 静默失效会让站长以为活动已经配好，实际前端什么都不显示。</p>
     *
     * <p>包级可见以便单测直接覆盖各种取值形态——这段解析一旦出错，
     * 表现是「活动神秘消失」，从界面上完全看不出原因。</p>
     */
    static Long resolvePromoEndsAt(JsonNode item) {
        var epoch = toEpochSecond(item.path("promoEndsAt"), textValue(item, "goodsId"));
        if (epoch == null || epoch <= Instant.now().getEpochSecond()) {
            return null;
        }
        return epoch;
    }

    /** 把选择器的取值或历史时间戳统一换算成 Unix 秒；无法识别返回 null。 */
    private static Long toEpochSecond(JsonNode node, String goodsId) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            var raw = node.asLong(0);
            return raw > 0 ? raw : null;
        }
        var raw = node.asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            try {
                var value = Long.parseLong(raw);
                return value > 0 ? value : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        // 归一化空格分隔（2026-12-31 23:59）为 ISO 的 T 分隔
        var normalized = raw.indexOf(' ') > 0 ? raw.replace(' ', 'T') : raw;
        try {
            // 带 Z 或带时区偏移的完整时间戳，此时以取值自带的时区为准
            return Instant.parse(normalized).getEpochSecond();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它形态
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant().getEpochSecond();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它形态
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZONE).toInstant().getEpochSecond();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它形态
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                .atTime(23, 59, 59).atZone(ZONE).toInstant().getEpochSecond();
        } catch (DateTimeParseException ignored) {
            // 全部形态都不匹配
        }
        log.warn("虚拟支付商品的限时截止时间无法识别，已按「无活动」处理：goodsId={}, promoEndsAt={}"
            + "（应填 2026-12-31T23:59 这类日期时间）", goodsId, raw);
        return null;
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
