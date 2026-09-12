package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminPointsRecordItemDTO;
import pro.uhalo.uni.dto.ExchangeItemDTO;
import pro.uhalo.uni.dto.AdminPointsRecordListResponse;
import pro.uhalo.uni.dto.DrawPrizeDTO;
import pro.uhalo.uni.dto.PointsActionRuleDTO;
import pro.uhalo.uni.dto.PointsConfigDTO;
import pro.uhalo.uni.dto.PointsDrawResponse;
import pro.uhalo.uni.dto.PointsDrawRuleDTO;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.PointsEarnResponse;
import pro.uhalo.uni.dto.PointsEarnStatusResponse;
import pro.uhalo.uni.dto.PointsExchangeResponse;
import pro.uhalo.uni.dto.PointsHistoryItemDTO;
import pro.uhalo.uni.dto.PointsHistoryResponse;
import pro.uhalo.uni.dto.PointsOverviewResponse;
import pro.uhalo.uni.dto.PointsSignInStatusDTO;
import pro.uhalo.uni.enums.PointsAction;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PointsEvidenceService;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.PointsConfigHelper;
import pro.uhalo.uni.utils.PointsRecordHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointsServiceImpl implements PointsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter OFFSET_TIME_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String EARN_KEY_SEP = ":";
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;
    private static final int ADMIN_MAX_SIZE = 100;
    private static final int EARN_RATE_LIMIT_PER_MINUTE = 20;
    private static final int SIGN_IN_RATE_LIMIT_PER_MINUTE = 10;
    private static final int MAX_REF_KEY_LENGTH = 150;
    private static final String NEW_USER_ACTION = "new_user";
    /** 榜单奖励（周榜结算发放），幂等 ref 为「年-周」 */
    static final String RANK_REWARD_ACTION = "rank_reward";
    private static final String DRAW_ACTION = "draw";
    private static final String DRAW_COST_ACTION = "draw_cost";
    private static final String EXCHANGE_CONFIG_GROUP = "exchangeConfig";
    private static final String EXCHANGE_DASHBOARD = "dashboard_advanced";
    private static final String EXCHANGE_DASHBOARD_ACTION = "exchange_dashboard";
    /**
     * 虚拟支付道具直购发放数据看板高级版时写入的动作码。
     *
     * <p>与积分兑换的 {@code exchange_dashboard} 区分，使「积分明细」能如实显示
     * 该权益是花钱买的还是积分兑换的；但两者在「当日是否已开通」判定中合并计数，
     * 见 {@code exchangeActionsOf}。</p>
     */
    static final String PURCHASE_DASHBOARD_ACTION = "purchase_dashboard";

    /**
     * 积分商城里属于「券」类的兑换项 key。
     *
     * <p>这些 key 兑换成功后必须真正给账户加券，否则用户扣了积分却拿不到券。
     * 此前 {@code exchange()} 只扣积分写流水、从不修改券字段，
     * 券类兑换等于「花了积分什么都没拿到」。
     * key 与虚拟支付道具类型同名，便于站长理解「同一个东西既能买也能换」。</p>
     */
    private static final String EXCHANGE_PUBLISH_TICKET = "publish_ticket";
    private static final String EXCHANGE_CONTENT_TICKET = "content_ticket";

    /**
     * 每次兑换券类权益发放的张数。
     *
     * <p>兑换项 repeater 目前没有「数量」字段，固定发 1 张。
     * 站长若要一次发多张，需要新增字段（见方案文档的后续优化项）。</p>
     */
    private static final int TICKETS_PER_EXCHANGE = 1;

    private static final SecureRandom PRIZE_RANDOM = new SecureRandom();

    private final PointsPersistence persistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;
    private final PointsEvidenceService pointsEvidenceService;
    private final RateLimiter rateLimiter;

    @Override
    public Mono<PointsOverviewResponse> getOverview(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> getOrCreateAccount(id)
                .flatMap(account -> buildSignInStatus(account, id)
                    .flatMap(signInStatus -> sumTodayEarned(id)
                        .map(todayEarned -> PointsOverviewResponse.builder()
                            .balance(safeBalance(account))
                            .todayEarned(todayEarned)
                            .signInStatus(signInStatus)
                            .build()))));
    }

    @Override
    public Mono<PointsEarnResponse> earn(String openid, PointsEarnRequest request) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "earn", EARN_RATE_LIMIT_PER_MINUTE, "领取积分过于频繁，请稍后再试");
                return loadConfig()
                    .flatMap(config -> {
                        ensureEnabled(config);
                        var action = PointsAction.parseEarnAction(request.getAction());
                        var refKey = resolveRefKey(request);
                        if (!StringUtils.hasText(refKey)) {
                            return Mono.error(new WxAuthException("articleName 或 refId 不能为空"));
                        }
                        validateRefKey(refKey);
                        var earnKey = buildDailyEarnKey(id, action.value(), refKey);
                        return persistence.findRecordByEarnKey(earnKey)
                            .flatMap(existing -> buildDuplicateEarnResponse(id))
                            .switchIfEmpty(Mono.defer(() -> requireEvidence(id, action.value(), refKey)
                                .flatMap(ready -> ready
                                    ? processEarn(id, config, action, request, refKey, earnKey)
                                    : Mono.error(new WxAuthException(
                                        PointsEvidenceService.missingEvidenceMessage(action.value()))))));
                    });
            });
    }

    @Override
    public Mono<PointsEarnStatusResponse> getEarnStatus(String openid, String action, String articleName) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> {
                    ensureEnabled(config);
                    var pointsAction = PointsAction.parseEarnAction(action);
                    if (!StringUtils.hasText(articleName)) {
                        return Mono.error(new WxAuthException("articleName 不能为空"));
                    }
                    var refKey = articleName.trim();
                    validateRefKey(refKey);
                    var rule = PointsConfigHelper.ruleFor(config, pointsAction.value());
                    var earnKey = buildDailyEarnKey(id, pointsAction.value(), refKey);
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.just(PointsEarnStatusResponse.builder()
                            .earnedToday(true)
                            .earned(0)
                            .capped(true)
                            .message(alreadyEarnedStatusMessage(pointsAction.value()))
                            .build()))
                        .switchIfEmpty(Mono.defer(() -> requireEvidence(id, pointsAction.value(), refKey)
                            .flatMap(ready -> ready
                                ? evaluateEarnAvailability(id, pointsAction.value(), rule)
                                : Mono.just(PointsEarnStatusResponse.builder()
                                    .earnedToday(false)
                                    .earned(0)
                                    .capped(false)
                                    .message(PointsEvidenceService.missingEvidenceMessage(
                                        pointsAction.value()))
                                    .build()))));
                }));
    }

    @Override
    public Mono<PointsEarnResponse> signIn(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "sign-in", SIGN_IN_RATE_LIMIT_PER_MINUTE, "签到过于频繁，请稍后再试");
                return loadConfig()
                    .flatMap(config -> {
                        ensureEnabled(config);
                        return getOrCreateAccount(id)
                            .flatMap(account -> {
                                var today = today();
                                if (today.equals(account.getLastSignInDate())) {
                                    return buildSignInStatus(account, id)
                                        .flatMap(status -> sumTodayEarned(id)
                                            .map(todayEarned -> PointsEarnResponse.builder()
                                                .earned(0)
                                                .balance(safeBalance(account))
                                                .todayEarned(todayEarned)
                                                .signedToday(true)
                                                .message("今日已签到")
                                                .signInStatus(status)
                                                .build()));
                                }
                                return applySignIn(id, account, config, today);
                            });
                    });
            });
    }

    @Override
    public Mono<PointsEarnResponse> claimNewUserGift(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> {
                    ensureEnabled(config);
                    var giftRule = config.getRules() == null ? null : config.getRules().getNewUserGift();
                    if (giftRule == null || giftRule.getPoints() <= 0) {
                        return Mono.just(PointsEarnResponse.builder()
                            .earned(0)
                            .message("新人礼未开启")
                            .build());
                    }
                    var earnKey = buildEarnKey(id, NEW_USER_ACTION, "gift");
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.just(PointsEarnResponse.builder()
                            .earned(0)
                            .balance(existing.getBalance())
                            .message("新人礼已领取")
                            .build()))
                        .switchIfEmpty(Mono.defer(() -> grantNewUserGift(id, giftRule.getPoints(), earnKey)));
                }));
    }

    @Override
    public Mono<PointsEarnResponse> grantRankReward(String openid, int points, String weekKey,
        String description) {
        if (points <= 0) {
            return Mono.just(PointsEarnResponse.builder()
                .earned(0)
                .balance(0)
                .message("该名次无奖励")
                .build());
        }
        return requireOpenid(openid)
            .flatMap(id -> {
                var earnKey = buildEarnKey(id, RANK_REWARD_ACTION, weekKey);
                return persistence.findRecordByEarnKey(earnKey)
                    .flatMap(existing -> Mono.just(PointsEarnResponse.builder()
                        .earned(0)
                        .balance(existing.getBalance())
                        .message("该周奖励已发放")
                        .build()))
                    .switchIfEmpty(Mono.defer(() -> grantPoints(id, RANK_REWARD_ACTION, points,
                        description, weekKey, earnKey, "该周奖励已发放")));
            });
    }

    private Mono<PointsEarnResponse> grantNewUserGift(String openid, int points, String earnKey) {
        return grantPoints(openid, NEW_USER_ACTION, points, "新用户见面礼", "新人礼", earnKey,
            "新人礼已领取");
    }

    /**
     * 直接发放积分（不走每日上限校验，用于「新人礼 / 榜单奖励」这类一次性奖励）。
     *
     * <p>{@code earnKey} 是唯一的幂等锚点：同一用户同一锚点只产生一条流水，
     * 并发发放时唯一约束失败按「已发放」处理。</p>
     */
    private Mono<PointsEarnResponse> grantPoints(String openid, String action, int points,
        String description, String refName, String earnKey, String duplicateMessage) {
        return persistence.getOrCreateAccount(openid)
            .flatMap(account -> resolveWxUserId(openid, account)
                .flatMap(wxUserId -> {
                    var newBalance = safeBalance(account) + points;
                    account.setBalance(newBalance);
                    if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                        account.setWxUserId(wxUserId);
                    }
                    var record = persistence.newRecord(openid, action, points, newBalance,
                        description, refName, earnKey, wxUserId);
                    return persistence.saveAccount(account)
                        .then(persistence.appendRecord(record))
                        .thenReturn(PointsEarnResponse.builder()
                            .earned(points)
                            .balance(newBalance)
                            .message("+" + points + " 积分")
                            .build());
                }))
            .onErrorResume(ex -> {
                // 并发发放时唯一流水创建失败视为已发放
                if (isDuplicateRecordError(ex)) {
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.just(PointsEarnResponse.builder()
                            .earned(0)
                            .balance(existing.getBalance())
                            .message(duplicateMessage)
                            .build()));
                }
                return Mono.error(ex);
            });
    }

    @Override
    public Mono<PointsDrawResponse> drawStatus(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> {
                    ensureEnabled(config);
                    var rule = config.getRules() == null ? null : config.getRules().getDraw();
                    var cost = rule == null ? 10 : rule.getCost();
                    var earnKey = buildDailyEarnKey(id, DRAW_ACTION, "daily");
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> buildAlreadyDrawnResponse(id))
                        .switchIfEmpty(persistence.getOrCreateAccount(id)
                            .map(account -> PointsDrawResponse.builder()
                                .earned(0)
                                .cost(cost)
                                .balance(safeBalance(account))
                                .alreadyDrawn(false)
                                .message("今日可翻牌")
                                .build()));
                }));
    }

    // ========== 积分兑换（配置驱动：当日开通标记） ==========

    @Override
    public Mono<PointsExchangeResponse> exchange(String openid, String item) {
        if (!StringUtils.hasText(item)) {
            return Mono.error(new WxAuthException("item 不能为空"));
        }
        var itemKey = item.trim().toLowerCase();
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "exchange", 30, "兑换过于频繁，请稍后再试");
                return loadConfig().flatMap(config -> {
                    ensureEnabled(config);
                    return loadExchangeItems()
                        .flatMap(items -> {
                            var cfg = items.stream()
                                .filter(it -> it.enabled() && it.key().equals(itemKey))
                                .findFirst()
                                .orElse(null);
                            if (cfg == null) {
                                return Mono.error(new WxAuthException("该兑换项不存在或已下线"));
                            }
                            var action = exchangeActionOf(itemKey);
                            return persistence.listRecordsByOpenid(id)
                                .map(records -> countRecordsToday(records, exchangeActionsOf(itemKey), today()))
                                .flatMap(todayCount -> {
                                    if (cfg.dailyMax() > 0 && todayCount >= cfg.dailyMax()) {
                                        return Mono.error(new WxAuthException("今日兑换次数已达上限"));
                                    }
                                    return persistence.getOrCreateAccount(id)
                                        .flatMap(account -> {
                                            if (safeBalance(account) < cfg.points()) {
                                                return Mono.error(new WxAuthException(
                                                    "积分不足，需要 " + cfg.points() + " 积分"));
                                            }
                                            var newBalance = safeBalance(account) - cfg.points();
                                            var earnKey = buildEarnKey(id, action,
                                                today() + "-" + (todayCount + 1));
                                            var record = persistence.newRecord(id, action,
                                                -cfg.points(), newBalance,
                                                cfg.name(), cfg.description(), earnKey,
                                                account.getWxUserId());
                                            // 顺序要求：**先写幂等流水，成功后再改账户**。
                                            // 流水资源名由 earnKey 派生故唯一，并发兑换同一权益时
                                            // 只有一个 create 能成功；若先改账户，两个并发请求会读到
                                            // 相同的 todayCount 与余额、各自扣一次积分，
                                            // 结果是一个兑换扣了积分却既没记账也没发券。
                                            return persistence.appendRecord(record)
                                                .onErrorResume(ex -> {
                                                    if (isDuplicateNameError(ex)) {
                                                        log.info("兑换流水已存在，跳过重复兑换：earnKey={}", earnKey);
                                                        return Mono.empty();
                                                    }
                                                    return Mono.error(ex);
                                                })
                                                .flatMap(saved -> {
                                                    // 拿到幂等位后才允许改账户：扣积分 + 按权益类型发券
                                                    account.setBalance(newBalance);
                                                    applyTicketGrant(account, itemKey);
                                                    return persistence.saveAccount(account)
                                                        .thenReturn(PointsExchangeResponse.builder()
                                                            .item(itemKey)
                                                            .points(cfg.points())
                                                            .balance(newBalance)
                                                            .message("兑换成功：" + cfg.name())
                                                            .build());
                                                })
                                                .switchIfEmpty(Mono.defer(() -> Mono.just(
                                                    PointsExchangeResponse.builder()
                                                        .item(itemKey)
                                                        .points(0)
                                                        .balance(safeBalance(account))
                                                        .message("请勿重复提交")
                                                        .build())));
                                        });
                                });
                        });
                });
            });
    }

    @Override
    public Mono<PointsExchangeResponse> exchangeStatus(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
                .flatMap(config -> {
                    ensureEnabled(config);
                    return loadExchangeItems()
                        .flatMap(items -> persistence.getOrCreateAccount(id)
                            .flatMap(account -> persistence.listRecordsByOpenid(id)
                                .map(records -> {
                                    var today = today();
                                    var itemDTOs = items.stream()
                                        .filter(ExchangeItem::enabled)
                                        .map(item -> ExchangeItemDTO.builder()
                                            .key(item.key())
                                            .name(item.name())
                                            .points(item.points())
                                            .dailyMaxCount(item.dailyMax())
                                            .description(item.description())
                                            .todayCount(countRecordsToday(records,
                                                exchangeActionsOf(item.key()), today))
                                            .build())
                                        .toList();
                                    return PointsExchangeResponse.builder()
                                        .balance(safeBalance(account))
                                        .items(itemDTOs)
                                        .message("兑换中心状态")
                                        .build();
                                })));
                }));
    }

    private Mono<List<ExchangeItem>> loadExchangeItems() {
        // 双源兼容：「积分商城」mallConfig.exchangeItems 优先，回落旧 exchangeConfig 分组
        var mall = settingFetcher.get("mallConfig");
        return (mall == null ? Mono.<JsonNode>empty() : mall)
            .map(node -> parseExchangeItemsArray(node.path("exchangeItems")))
            .flatMap(items -> items.isEmpty()
                ? legacyExchangeItems()
                : Mono.just(items))
            .switchIfEmpty(legacyExchangeItems());
    }

    private Mono<List<ExchangeItem>> legacyExchangeItems() {
        return settingFetcher.get(EXCHANGE_CONFIG_GROUP)
            .map(this::parseExchangeItems)
            .defaultIfEmpty(defaultExchangeItems());
    }

    /** 解析 mallConfig.exchangeItems 数组（权益 repeater） */
    private List<ExchangeItem> parseExchangeItemsArray(JsonNode itemsNode) {
        var items = new ArrayList<ExchangeItem>();
        if (itemsNode != null && itemsNode.isArray()) {
            itemsNode.forEach(itemNode -> {
                var key = itemNode.path("key").asText("").trim();
                var name = itemNode.path("name").asText("").trim();
                var points = itemNode.path("points").asInt(0);
                var dailyMax = itemNode.path("dailyMaxCount").asInt(0);
                var description = itemNode.path("description").asText("").trim();
                if (StringUtils.hasText(key) && StringUtils.hasText(name) && points > 0 && dailyMax > 0) {
                    items.add(new ExchangeItem(key, name, points, dailyMax,
                        StringUtils.hasText(description) ? description : name));
                }
            });
        }
        return items;
    }

    private List<ExchangeItem> parseExchangeItems(JsonNode node) {
        var items = new ArrayList<ExchangeItem>();
        // 简单文本格式：每行一个权益：key,名称,积分,每日次数[,说明]
        var itemsText = node.path("itemsText").asText("").trim();
        if (StringUtils.hasText(itemsText)) {
            for (var line : itemsText.split("\n")) {
                var parts = line.trim().split(",");
                if (parts.length < 4) {
                    continue;
                }
                var key = parts[0].trim();
                var name = parts[1].trim();
                var points = parseIntOr(parts[2].trim(), 0);
                var dailyMax = parseIntOr(parts[3].trim(), 0);
                var description = parts.length >= 5 ? parts[4].trim() : "";
                if (StringUtils.hasText(key) && StringUtils.hasText(name) && points > 0 && dailyMax > 0) {
                    items.add(new ExchangeItem(key, name, points, dailyMax,
                        StringUtils.hasText(description) ? description : name));
                }
            }
        } else {
            // 兼容旧 repeater 配置
            var itemsNode = node.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                itemsNode.forEach(itemNode -> {
                    var key = itemNode.path("key").asText("").trim();
                    var name = itemNode.path("name").asText("").trim();
                    var points = itemNode.path("points").asInt(0);
                    var dailyMax = itemNode.path("dailyMaxCount").asInt(0);
                    var description = itemNode.path("description").asText("").trim();
                    if (StringUtils.hasText(key) && StringUtils.hasText(name) && points > 0 && dailyMax > 0) {
                        items.add(new ExchangeItem(key, name, points, dailyMax,
                            StringUtils.hasText(description) ? description : name));
                    }
                });
            }
        }
        return items.isEmpty() ? defaultExchangeItems() : List.copyOf(items);
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<ExchangeItem> defaultExchangeItems() {
        return List.of(new ExchangeItem(EXCHANGE_DASHBOARD, "数据看板高级版", 30, 1,
            "解锁当日数据看板高级版（30 日趋势与数据摘要导出）"));
    }

    private record ExchangeItem(String key, String name, int points, int dailyMax,
        String description) {
        boolean enabled() {
            return StringUtils.hasText(key) && points > 0 && dailyMax > 0;
        }
    }

    private static int countRecordsToday(List<PointsRecord> records, String action, String date) {
        return countRecordsToday(records, Set.of(action), date);
    }

    /**
     * 统计当日命中任一动作码的流水条数。
     *
     * <p>同一权益可能有多条获取路径（积分兑换 / 现金道具直购），
     * 判定「当日是否已开通」时必须把这些路径合并计数，
     * 否则现金购买的用户看板不会解锁，或会重复消耗积分再兑换一次。</p>
     */
    private static int countRecordsToday(List<PointsRecord> records, Set<String> actions, String date) {
        if (records == null || records.isEmpty() || actions == null || actions.isEmpty()) {
            return 0;
        }
        var count = 0;
        for (var record : records) {
            if (record == null || !actions.contains(record.getAction())) {
                continue;
            }
            if (isSameDay(record.getCreateTime(), date)) {
                count++;
            }
        }
        return count;
    }

    private static String exchangeActionOf(String item) {
        if (EXCHANGE_DASHBOARD.equals(item)) {
            // 兼容历史版本动作码 exchange_dashboard
            return EXCHANGE_DASHBOARD_ACTION;
        }
        var normalized = StringUtils.hasText(item) ? item.trim().toLowerCase() : "";
        var sanitized = new StringBuilder();
        for (var i = 0; i < normalized.length() && sanitized.length() < 32; i++) {
            var ch = normalized.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                sanitized.append(ch);
            }
        }
        return sanitized.length() == 0 ? EXCHANGE_DASHBOARD_ACTION : "exchange_" + sanitized;
    }

    /**
     * 某权益在「当日已开通」判定中应计入的全部动作码。
     *
     * <p>数据看板高级版有两条获取路径：积分兑换写 {@code exchange_dashboard}，
     * 虚拟支付道具直购写 {@code purchase_dashboard}。两者都表示当日已开通，
     * 必须一并计入，否则现金购买的用户看板不生效、或会再消耗积分重复兑换。</p>
     */
    private static Set<String> exchangeActionsOf(String item) {
        var primary = exchangeActionOf(item);
        if (EXCHANGE_DASHBOARD.equals(item)) {
            return Set.of(primary, PURCHASE_DASHBOARD_ACTION);
        }
        return Set.of(primary);
    }

    @Override
    public Mono<PointsDrawResponse> draw(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "draw", 20, "翻牌过于频繁，请稍后再试");
                return loadConfig()
                    .flatMap(config -> {
                        ensureEnabled(config);
                        var rule = config.getRules() == null ? null : config.getRules().getDraw();
                        if (rule == null || rule.getCost() <= 0 || rule.getPrizes().isEmpty()) {
                            return Mono.error(new WxAuthException("翻牌抽奖未配置"));
                        }
                        var earnKey = buildDailyEarnKey(id, DRAW_ACTION, "daily");
                        return persistence.findRecordByEarnKey(earnKey)
                            .flatMap(existing -> buildAlreadyDrawnResponse(id))
                            .switchIfEmpty(Mono.defer(() -> processDraw(id, rule, earnKey)));
                    });
            });
    }

    private Mono<PointsDrawResponse> buildAlreadyDrawnResponse(String openid) {
        return persistence.getOrCreateAccount(openid)
            .map(account -> PointsDrawResponse.builder()
                .earned(0)
                .cost(0)
                .balance(safeBalance(account))
                .alreadyDrawn(true)
                .message("今日已翻牌，明天再来吧")
                .build());
    }

    private Mono<PointsDrawResponse> processDraw(String openid, PointsDrawRuleDTO rule, String earnKey) {
        var prize = pickPrize(rule.getPrizes());
        var drawCostKey = buildEarnKey(openid, DRAW_COST_ACTION, today());
        return persistence.getOrCreateAccount(openid)
            .flatMap(account -> {
                var balance = safeBalance(account);
                if (balance < rule.getCost()) {
                    return Mono.error(new WxAuthException("积分不足，需要 " + rule.getCost() + " 积分"));
                }
                var newBalance = balance - rule.getCost() + prize;
                return resolveWxUserId(openid, account)
                    .flatMap(wxUserId -> {
                        account.setBalance(newBalance);
                        if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                            account.setWxUserId(wxUserId);
                        }
                        var prizeRecord = persistence.newRecord(openid, DRAW_ACTION, prize, newBalance,
                            "翻牌中奖 +" + prize, "每日翻牌", earnKey, wxUserId);
                        var costRecord = persistence.newRecord(openid, DRAW_COST_ACTION, -rule.getCost(),
                            newBalance, "翻牌消耗 -" + rule.getCost(), "每日翻牌", drawCostKey, wxUserId);
                        return persistence.appendRecord(prizeRecord)
                            .then(persistence.appendRecord(costRecord))
                            .then(persistence.saveAccount(account))
                            .thenReturn(PointsDrawResponse.builder()
                                .earned(prize)
                                .cost(rule.getCost())
                                .balance(newBalance)
                                .alreadyDrawn(false)
                                .message("中奖 +" + prize + " 积分")
                                .build());
                    });
            });
    }

    /** 按权重随机挑选奖品（返回中奖积分数） */
    static int pickPrize(List<DrawPrizeDTO> prizes) {
        if (prizes == null || prizes.isEmpty()) {
            return 0;
        }
        var total = prizes.stream().mapToInt(DrawPrizeDTO::getWeight).sum();
        if (total <= 0) {
            return prizes.get(0).getPoints();
        }
        var bound = PRIZE_RANDOM.nextInt(total);
        var cursor = 0;
        for (var prize : prizes) {
            cursor += prize.getWeight();
            if (bound < cursor) {
                return prize.getPoints();
            }
        }
        return prizes.get(prizes.size() - 1).getPoints();
    }

    private static boolean isDuplicateRecordError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }

    @Override
    public Mono<PointsHistoryResponse> listHistory(String openid, String action, int page, int size) {
        var normalizedAction = StringUtils.hasText(action) ? action.trim().toLowerCase(Locale.ROOT) : null;
        return requireOpenid(openid)
            .flatMap(id -> persistence.listRecordsByOpenid(id)
                .map(records -> toHistoryPage(records, normalizedAction, page, size)));
    }

    @Override
    public Mono<AdminPointsRecordListResponse> listAllRecordsForAdmin(String action, String keyword,
        int page, int size) {
        var normalizedPage = Math.max(page, DEFAULT_PAGE);
        var normalizedSize = Math.min(Math.max(size, 1), ADMIN_MAX_SIZE);
        var normalizedAction = StringUtils.hasText(action) ? action.trim().toLowerCase() : null;
        var normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;

        return Mono.zip(
                persistence.listAllRecords(),
                wxUserService.loadUserLookup())
            .map(tuple -> {
                var records = tuple.getT1();
                var userLookup = tuple.getT2();
                var filtered = records.stream()
                    .filter(record -> normalizedAction == null
                        || normalizedAction.equals(record.getAction()))
                    .filter(record -> matchesRecordKeyword(record,
                        userLookup.resolveForRecord(record), normalizedKeyword))
                    .sorted(Comparator.comparing(PointsRecord::getCreateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
                var total = filtered.size();
                var from = Math.min(normalizedPage * normalizedSize, total);
                var to = Math.min(from + normalizedSize, total);
                var items = filtered.subList(from, to).stream()
                    .map(record -> toAdminRecordItem(record, userLookup))
                    .toList();
                return AdminPointsRecordListResponse.builder()
                    .items(items)
                    .total(total)
                    .hasNext(to < total)
                    .page(normalizedPage)
                    .size(normalizedSize)
                    .build();
            });
    }

    private AdminPointsRecordItemDTO toAdminRecordItem(PointsRecord record, WxUserLookup userLookup) {
        var wxUser = userLookup.resolveForRecord(record);
        var action = record.getAction();
        return AdminPointsRecordItemDTO.builder()
            .id(record.getMetadata() != null ? record.getMetadata().getName() : null)
            .wxOpenid(PointsRecordHelper.resolveOpenid(record))
            .nickname(PointsRecordHelper.resolveDisplayName(wxUser,
                PointsRecordHelper.resolveOpenid(record)))
            .avatarUrl(wxUser == null ? null : wxUser.getAvatarUrl())
            .haloUserName(wxUser != null ? wxUser.getHaloUserName() : null)
            .action(action)
            .actionLabel(PointsAction.labelOf(action))
            .points(record.getPoints() == null ? 0 : record.getPoints())
            .balance(record.getBalance() == null ? 0L : record.getBalance())
            .description(PointsRecordHelper.resolveDetailText(record))
            .contentTitle(PointsRecordHelper.resolveContentTitleOrLabel(record))
            .refName(record.getRefName())
            .createTime(PointsRecordHelper.resolveCreateTime(record))
            .createTimeText(PointsRecordHelper.formatInstant(PointsRecordHelper.resolveCreateInstant(record)))
            .build();
    }

    private static boolean matchesRecordKeyword(PointsRecord record, WxUser wxUser, String keyword) {
        if (keyword == null) {
            return true;
        }
        for (var openid : PointsRecordHelper.resolveOpenidCandidates(record)) {
            if (containsIgnoreCase(openid, keyword)) {
                return true;
            }
        }
        if (containsIgnoreCase(record.getRefName(), keyword)) {
            return true;
        }
        if (containsIgnoreCase(record.getDescription(), keyword)) {
            return true;
        }
        if (containsIgnoreCase(PointsAction.labelOf(record.getAction()), keyword)) {
            return true;
        }
        if (wxUser == null) {
            return false;
        }
        return containsIgnoreCase(wxUser.getNickname(), keyword)
            || containsIgnoreCase(wxUser.getHaloUserName(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private Mono<PointsEarnResponse> processEarn(String openid, PointsConfigDTO config,
        PointsAction action, PointsEarnRequest request, String refKey, String earnKey) {
        var rule = PointsConfigHelper.ruleFor(config, action.value());
        if (rule.getPoints() <= 0) {
            return buildEarnFailureResponse(openid, "该行为未配置积分", false, false);
        }
        return listTodayRecords(openid, action.value())
            .flatMap(todayRecords -> {
                if (isCountCapped(todayRecords, rule)) {
                    return buildEarnFailureResponse(openid, capMessage(action.value(), "count"), true, false);
                }
                var todayPoints = sumPoints(todayRecords);
                if (isPointsCapped(todayPoints, rule, rule.getPoints())) {
                    return buildEarnFailureResponse(openid, capMessage(action.value(), "points"), true, false);
                }
                return getOrCreateAccount(openid)
                    .flatMap(account -> resolveWxUserId(openid, account)
                        .flatMap(wxUserId -> {
                            var newBalance = safeBalance(account) + rule.getPoints();
                            account.setBalance(newBalance);
                            if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                                account.setWxUserId(wxUserId);
                            }
                            var description = StringUtils.hasText(request.getTitle())
                                ? request.getTitle().trim()
                                : refKey;
                            var record = PointsPersistence.newRecord(openid, action.value(), rule.getPoints(),
                                newBalance, description, refKey, earnKey, wxUserId);
                            return persistence.saveAccount(account)
                                .then(persistence.appendRecord(record))
                                .then(sumTodayEarned(openid)
                                    .map(todayEarned -> PointsEarnResponse.builder()
                                        .earned(rule.getPoints())
                                        .balance(newBalance)
                                        .todayEarned(todayEarned)
                                        .capped(false)
                                        .earnedToday(false)
                                        .message("+" + rule.getPoints() + " 积分")
                                        .build()));
                        }));
            });
    }

    private Mono<PointsEarnResponse> applySignIn(String openid, UserPoints account,
        PointsConfigDTO config, String today) {
        var signInRule = config.getRules().getSignIn();
        var basePoints = Math.max(0, signInRule.getPoints());
        var consecutiveDays = calcNextConsecutiveDays(account.getLastSignInDate(),
            account.getConsecutiveDays(), today);
        var bonus = calcConsecutiveBonus(signInRule, consecutiveDays);
        var earned = basePoints + bonus;
        if (earned <= 0) {
            return Mono.error(new WxAuthException("签到积分未配置"));
        }
        return resolveWxUserId(openid, account)
            .flatMap(wxUserId -> {
                account.setBalance(safeBalance(account) + earned);
                account.setConsecutiveDays(consecutiveDays);
                account.setLastSignInDate(today);
                if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                    account.setWxUserId(wxUserId);
                }
                var description = bonus > 0
                    ? String.format("连续签到 %d 天（含额外奖励 %d 分）", consecutiveDays, bonus)
                    : String.format("连续签到 %d 天", consecutiveDays);
                var record = PointsPersistence.newRecord(openid, PointsAction.SIGN_IN.value(), earned,
                    account.getBalance(), description, null,
                    buildEarnKey(openid, PointsAction.SIGN_IN.value(), today),
                    wxUserId);
                return persistence.saveAccount(account)
                    .then(persistence.appendRecord(record))
                    .then(sumTodayEarned(openid)
                        .flatMap(todayEarned -> buildSignInStatus(account, openid)
                            .map(status -> PointsEarnResponse.builder()
                                .earned(earned)
                                .balance(safeBalance(account))
                                .todayEarned(todayEarned)
                                .message("+" + earned + " 积分")
                                .signedToday(true)
                                .signInStatus(status)
                                .build())));
            });
    }

    private Mono<PointsEarnResponse> buildDuplicateEarnResponse(String openid) {
        return buildEarnFailureResponse(openid, "该文章今日已领取", true, true);
    }

    private Mono<PointsEarnResponse> buildEarnFailureResponse(String openid, String message,
        boolean capped, boolean earnedToday) {
        return getOrCreateAccount(openid)
            .flatMap(account -> sumTodayEarned(openid)
                .map(todayEarned -> PointsEarnResponse.builder()
                    .earned(0)
                    .balance(safeBalance(account))
                    .todayEarned(todayEarned)
                    .message(message)
                    .capped(capped)
                    .earnedToday(earnedToday)
                    .build()));
    }

    private Mono<PointsEarnStatusResponse> evaluateEarnAvailability(String openid, String action,
        PointsActionRuleDTO rule) {
        if (rule.getPoints() <= 0) {
            return Mono.just(PointsEarnStatusResponse.builder()
                .earnedToday(false)
                .earned(0)
                .capped(false)
                .message("该行为未配置积分")
                .build());
        }
        return listTodayRecords(openid, action)
            .map(todayRecords -> {
                if (isCountCapped(todayRecords, rule)) {
                    return PointsEarnStatusResponse.builder()
                        .earnedToday(false)
                        .earned(0)
                        .capped(true)
                        .message(capMessage(action, "count"))
                        .build();
                }
                var todayPoints = sumPoints(todayRecords);
                if (isPointsCapped(todayPoints, rule, rule.getPoints())) {
                    return PointsEarnStatusResponse.builder()
                        .earnedToday(false)
                        .earned(0)
                        .capped(true)
                        .message(capMessage(action, "points"))
                        .build();
                }
                return PointsEarnStatusResponse.builder()
                    .earnedToday(false)
                    .earned(rule.getPoints())
                    .capped(false)
                    .message("")
                    .build();
            });
    }

    private Mono<PointsSignInStatusDTO> buildSignInStatus(UserPoints account, String openid) {
        var today = today();
        var signedToday = today.equals(account.getLastSignInDate());
        return listSignInDates(openid)
            .map(dates -> PointsSignInStatusDTO.builder()
                .signedToday(signedToday)
                .consecutiveDays(account.getConsecutiveDays() == null ? 0 : account.getConsecutiveDays())
                .weekRecords(buildWeekRecords(dates, today))
                .lastSignInDate(StringUtils.hasText(account.getLastSignInDate())
                    ? account.getLastSignInDate()
                    : "")
                .build());
    }

    static String alreadyEarnedStatusMessage(String action) {
        var label = switch (action) {
            case "browse" -> "浏览";
            case "comment" -> "评论";
            case "upvote" -> "点赞";
            case "vote" -> "投票";
            default -> "";
        };
        return "该文章今日已领取" + label + "积分";
    }

    private Mono<UserPoints> getOrCreateAccount(String openid) {
        return persistence.getOrCreateAccount(openid);
    }

    static List<Boolean> buildWeekRecords(Set<String> signInDates, String today) {
        var records = new ArrayList<Boolean>(7);
        var end = LocalDate.parse(today, DATE_FMT);
        for (int i = 6; i >= 0; i--) {
            var date = end.minusDays(i);
            records.add(signInDates.contains(date.format(DATE_FMT)));
        }
        return records;
    }

    static int calcNextConsecutiveDays(String lastSignInDate, Integer currentDays, String today) {
        if (!StringUtils.hasText(lastSignInDate)) {
            return 1;
        }
        var last = LocalDate.parse(lastSignInDate, DATE_FMT);
        var current = LocalDate.parse(today, DATE_FMT);
        if (last.equals(current)) {
            return currentDays == null || currentDays <= 0 ? 1 : currentDays;
        }
        if (last.plusDays(1).equals(current)) {
            return (currentDays == null ? 0 : currentDays) + 1;
        }
        return 1;
    }

    static int calcConsecutiveBonus(pro.uhalo.uni.dto.PointsSignInRuleDTO signInRule, int consecutiveDays) {
        if (signInRule == null || signInRule.getConsecutiveBonus() == null) {
            return 0;
        }
        var bonus = 0;
        for (var item : signInRule.getConsecutiveBonus()) {
            if (item.getDays() > 0 && consecutiveDays >= item.getDays()) {
                bonus = Math.max(bonus, item.getBonus());
            }
        }
        return bonus;
    }

    private Mono<List<PointsRecord>> listTodayRecords(String openid, String action) {
        return persistence.listRecordsByOpenid(openid)
            .map(records -> records.stream()
                .filter(record -> action.equals(record.getAction()))
                .filter(record -> isSameDay(record.getCreateTime(), today()))
                .toList());
    }

    private Mono<Long> sumTodayEarned(String openid) {
        return persistence.listRecordsByOpenid(openid)
            .map(records -> records.stream()
                .filter(record -> isSameDay(record.getCreateTime(), today()))
                .mapToLong(record -> record.getPoints() == null ? 0 : Math.max(0, record.getPoints()))
                .sum());
    }

    private Mono<Set<String>> listSignInDates(String openid) {
        return persistence.listRecordsByOpenid(openid)
            .map(records -> {
                var dates = new HashSet<String>();
                records.stream()
                    .filter(record -> PointsAction.SIGN_IN.value().equals(record.getAction()))
                    .forEach(record -> {
                        if (record.getEarnKey() != null && record.getEarnKey().contains(EARN_KEY_SEP)) {
                            var parts = record.getEarnKey().split(EARN_KEY_SEP);
                            dates.add(parts[parts.length - 1]);
                        } else if (record.getCreateTime() != null) {
                            dates.add(formatDate(record.getCreateTime()));
                        }
                    });
                return dates;
            });
    }

    /**
     * 明细分页。
     *
     * <p>过滤（action）必须在排序与分页<b>之前</b>：否则会出现「筛选后第一页是空的、
     * 但后面几页其实有记录」这种分页错乱。原实现已是全量加载后内存分页，
     * 因此这里过滤不引入额外查询成本。</p>
     */
    private PointsHistoryResponse toHistoryPage(List<PointsRecord> records, String action, int page,
        int size) {
        var normalizedPage = Math.max(page, DEFAULT_PAGE);
        var normalizedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        var filtered = action == null ? records : records.stream()
            .filter(record -> action.equals(record.getAction()))
            .toList();
        var sorted = filtered.stream()
            .sorted(Comparator.comparing(PointsRecord::getCreateTime,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
        var total = sorted.size();
        // 本次查询范围内的积分合计（正负相加）：给「抽奖消耗明细」这类页面直接展示累计值
        var totalPoints = sorted.stream()
            .mapToLong(record -> record.getPoints() == null ? 0L : record.getPoints())
            .sum();
        var from = Math.min(normalizedPage * normalizedSize, total);
        var to = Math.min(from + normalizedSize, total);
        var items = sorted.subList(from, to).stream()
            .map(this::toHistoryItem)
            .toList();
        return PointsHistoryResponse.builder()
            .items(items)
            .total(total)
            .totalPoints(totalPoints)
            .hasNext(to < total)
            .build();
    }

    private PointsHistoryItemDTO toHistoryItem(PointsRecord record) {
        var action = record.getAction();
        return PointsHistoryItemDTO.builder()
            .id(record.getMetadata() != null ? record.getMetadata().getName() : null)
            .action(action)
            .actionLabel(PointsAction.labelOf(action))
            .points(record.getPoints() == null ? 0 : record.getPoints())
            .balance(record.getBalance())
            .description(record.getDescription())
            .refName(record.getRefName())
            .createTime(record.getCreateTime() == null ? null : formatCreateTime(record.getCreateTime()))
            .createTimeText(PointsRecordHelper.formatInstant(record.getCreateTime()))
            .build();
    }

    static String capMessage(String action, String type) {
        var label = switch (action) {
            case "browse" -> "浏览";
            case "comment" -> "评论";
            case "upvote" -> "点赞";
            case "vote" -> "投票";
            default -> "该行为";
        };
        if ("count".equals(type)) {
            return "今日" + label + "次数已达上限";
        }
        return "今日" + label + "积分已达上限";
    }

    static String formatCreateTime(Instant instant) {
        return instant.atZone(ZONE).format(OFFSET_TIME_FMT);
    }

    private Mono<PointsConfigDTO> loadConfig() {
        return settingFetcher.get(PointsConfigHelper.groupName())
            .map(PointsConfigHelper::parse)
            .defaultIfEmpty(PointsConfigHelper.defaults());
    }

    private static Mono<String> requireOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        return Mono.just(openid.trim());
    }

    private Mono<Boolean> requireEvidence(String openid, String action, String refKey) {
        return pointsEvidenceService.evidenceReady(openid, action, refKey);
    }

    private void ensureRateLimit(String openid, String scene, int limit, String message) {
        if (!rateLimiter.tryAcquire(openid + ":" + scene, limit, Duration.ofMinutes(1))) {
            throw new WxAuthException(message);
        }
    }

    private static void validateRefKey(String refKey) {
        var normalized = refKey.trim();
        if (normalized.length() > MAX_REF_KEY_LENGTH) {
            throw new WxAuthException("articleName 参数过长");
        }
        for (var i = 0; i < normalized.length(); i++) {
            var ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                throw new WxAuthException("articleName 参数不合法");
            }
        }
    }

    private Mono<String> resolveWxUserId(String openid, UserPoints account) {
        if (account != null && StringUtils.hasText(account.getWxUserId())) {
            return Mono.just(account.getWxUserId().trim());
        }
        return wxUserService.fetchByOpenid(openid)
            .map(WxUserIds::resolveId)
            .filter(StringUtils::hasText)
            .defaultIfEmpty("");
    }

    private static void ensureEnabled(PointsConfigDTO config) {
        if (config == null || !config.isEnabled()) {
            throw new WxAuthException("积分功能未开启");
        }
    }

    static String buildEarnKey(String openid, String action, String ref) {
        return openid + EARN_KEY_SEP + action + EARN_KEY_SEP + ref;
    }

    static String buildDailyEarnKey(String openid, String action, String ref) {
        return buildDailyEarnKey(openid, action, ref, LocalDate.now(ZONE));
    }

    static String buildDailyEarnKey(String openid, String action, String ref, LocalDate date) {
        return buildEarnKey(openid, action, ref) + EARN_KEY_SEP + date.format(DATE_FMT);
    }

    static LocalDate recordDate(Instant instant) {
        if (instant == null) {
            return LocalDate.now(ZONE);
        }
        return instant.atZone(ZONE).toLocalDate();
    }

    static String resolveRefKey(PointsEarnRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.getArticleName())) {
            return request.getArticleName().trim();
        }
        if (StringUtils.hasText(request.getRefId())) {
            return request.getRefId().trim();
        }
        return null;
    }

    static boolean isCountCapped(List<PointsRecord> todayRecords, PointsActionRuleDTO rule) {
        return rule.getDailyMaxCount() > 0 && todayRecords.size() >= rule.getDailyMaxCount();
    }

    static boolean isPointsCapped(long todayPoints, PointsActionRuleDTO rule, int nextPoints) {
        return rule.getDailyMaxPoints() > 0 && todayPoints + nextPoints > rule.getDailyMaxPoints();
    }

    static long sumPoints(List<PointsRecord> records) {
        return records.stream()
            .mapToLong(record -> record.getPoints() == null ? 0 : Math.max(0, record.getPoints()))
            .sum();
    }

    static long safeBalance(UserPoints account) {
        return account.getBalance() == null ? 0L : Math.max(0L, account.getBalance());
    }

    /**
     * 券数量兜底：null 与负数一律按 0 处理。
     *
     * <p>阅读券、发布券都是后来加入的可空字段，存量账户读到的值必然为 null，
     * 不兜底会在累加时抛 NPE 导致兑换失败。</p>
     */
    static int safeTickets(Integer tickets) {
        return tickets == null ? 0 : Math.max(0, tickets);
    }

    /**
     * 券类兑换项的实际发放：把券加到账户上。
     *
     * <p>⚠️ 这一步不能省。{@code exchange()} 原本只扣积分写流水，
     * 对券类兑换项来说用户「积分被扣、券没到手」，是实打实的资损。
     * 看板高级版（dashboard_advanced）不在此列——它的生效依据是流水本身
     * （{@code exchangeActionsOf} 统计当日动作码），不需要改账户字段。</p>
     *
     * <p>调用时机必须在幂等流水写入成功之后，与扣积分在同一次 saveAccount 内完成，
     * 保证「扣积分」与「发券」要么都成功、要么都不发生。</p>
     */
    private static void applyTicketGrant(UserPoints account, String itemKey) {
        if (account == null || !StringUtils.hasText(itemKey)) {
            return;
        }
        switch (itemKey.trim().toLowerCase()) {
            case EXCHANGE_PUBLISH_TICKET -> account.setPublishTickets(
                safeTickets(account.getPublishTickets()) + TICKETS_PER_EXCHANGE);
            case EXCHANGE_CONTENT_TICKET -> account.setContentTickets(
                safeTickets(account.getContentTickets()) + TICKETS_PER_EXCHANGE);
            default -> {
                // 其余兑换项（如看板高级版）靠流水判定生效，不改账户字段
            }
        }
    }

    /**
     * 是否为「资源名已存在」类冲突错误。
     *
     * <p>积分流水的 metadata.name 由 earnKey 派生、全局唯一，
     * create 冲突即代表这笔流水已写过（并发或重复提交），
     * 调用方据此判定幂等而不是把错误抛给用户。</p>
     */
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

    static String today() {
        return LocalDate.now(ZONE).format(DATE_FMT);
    }

    static boolean isSameDay(Instant instant, String date) {
        if (instant == null || !StringUtils.hasText(date)) {
            return false;
        }
        return date.equals(formatDate(instant));
    }

    static String formatDate(Instant instant) {
        return instant.atZone(ZONE).toLocalDate().format(DATE_FMT);
    }
}
