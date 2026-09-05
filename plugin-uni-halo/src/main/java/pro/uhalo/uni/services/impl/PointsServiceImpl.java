package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminPointsRecordItemDTO;
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
    private static final String DRAW_ACTION = "draw";
    private static final String DRAW_COST_ACTION = "draw_cost";
    private static final String EXCHANGE_PUBLISH = "publish_ticket";
    private static final String EXCHANGE_DASHBOARD = "dashboard_advanced";
    private static final String EXCHANGE_PUBLISH_ACTION = "exchange_publish";
    private static final String EXCHANGE_DASHBOARD_ACTION = "exchange_dashboard";
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

    private Mono<PointsEarnResponse> grantNewUserGift(String openid, int points, String earnKey) {
        return persistence.getOrCreateAccount(openid)
            .flatMap(account -> resolveWxUserId(openid, account)
                .flatMap(wxUserId -> {
                    var newBalance = safeBalance(account) + points;
                    account.setBalance(newBalance);
                    if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                        account.setWxUserId(wxUserId);
                    }
                    var record = persistence.newRecord(openid, NEW_USER_ACTION, points, newBalance,
                        "新用户见面礼", "新人礼", earnKey, wxUserId);
                    return persistence.saveAccount(account)
                        .then(persistence.appendRecord(record))
                        .thenReturn(PointsEarnResponse.builder()
                            .earned(points)
                            .balance(newBalance)
                            .message("+" + points + " 积分")
                            .build());
                }))
            .onErrorResume(ex -> {
                // 并发领取时唯一流水创建失败视为已领取
                if (isDuplicateRecordError(ex)) {
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.just(PointsEarnResponse.builder()
                            .earned(0)
                            .balance(existing.getBalance())
                            .message("新人礼已领取")
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

    // ========== 积分兑换（发布券 / 数据看板高级版） ==========

    @Override
    public Mono<PointsExchangeResponse> exchange(String openid, String item) {
        if (!StringUtils.hasText(item)) {
            return Mono.error(new WxAuthException("item 不能为空"));
        }
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "exchange", 30, "兑换过于频繁，请稍后再试");
                return loadConfig().flatMap(config -> {
                    ensureEnabled(config);
                    var rule = resolveExchangeRule(config, item);
                    if (rule == null || rule.points() <= 0) {
                        return Mono.error(new WxAuthException("该兑换项未配置"));
                    }
                    var action = exchangeActionOf(item);
                    return persistence.listRecordsByOpenid(id)
                        .map(records -> countRecordsToday(records, action, today()))
                        .flatMap(todayCount -> {
                            if (rule.dailyMax() > 0 && todayCount >= rule.dailyMax()) {
                                return Mono.error(new WxAuthException("今日兑换次数已达上限"));
                            }
                            return persistence.getOrCreateAccount(id)
                                .flatMap(account -> {
                                    if (safeBalance(account) < rule.points()) {
                                        return Mono.error(new WxAuthException("积分不足，需要 " + rule.points() + " 积分"));
                                    }
                                    var newBalance = safeBalance(account) - rule.points();
                                    account.setBalance(newBalance);
                                    if (EXCHANGE_PUBLISH.equals(item)) {
                                        account.setPublishTickets(safeTickets(account) + 1);
                                    }
                                    var earnKey = buildEarnKey(id, action, today() + "-" + (todayCount + 1));
                                    var record = persistence.newRecord(id, action, -rule.points(), newBalance,
                                        rule.description(), rule.description(), earnKey, account.getWxUserId());
                                    return persistence.saveAccount(account)
                                        .then(persistence.appendRecord(record))
                                        .thenReturn(PointsExchangeResponse.builder()
                                            .item(item)
                                            .points(rule.points())
                                            .balance(newBalance)
                                            .publishTickets(account.getPublishTickets())
                                            .message("兑换成功：" + rule.description())
                                            .build());
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
                    var publishRule = resolveExchangeRule(config, EXCHANGE_PUBLISH);
                    return persistence.getOrCreateAccount(id)
                        .flatMap(account -> persistence.listRecordsByOpenid(id)
                            .map(records -> {
                                var today = today();
                                var publishCount = countRecordsToday(records, EXCHANGE_PUBLISH_ACTION, today);
                                var dashboardCount = countRecordsToday(records, EXCHANGE_DASHBOARD_ACTION, today);
                                return PointsExchangeResponse.builder()
                                    .balance(safeBalance(account))
                                    .publishTickets(safeTickets(account))
                                    .freeQuota(publishRule == null ? 3 : publishRule.freeCount())
                                    .remaining(publishRule == null
                                        ? 5
                                        : Math.max(0, publishRule.dailyMax() - publishCount))
                                    .dashboardUnlockedToday(dashboardCount > 0)
                                    .message("兑换中心状态")
                                    .build();
                            }));
                }));
    }

    @Override
    public Mono<PointsExchangeResponse> spendPublishTicket(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> persistence.getOrCreateAccount(id)
                .flatMap(account -> {
                    if (safeTickets(account) <= 0) {
                        return Mono.error(new WxAuthException("发布券不足"));
                    }
                    account.setPublishTickets(safeTickets(account) - 1);
                    return persistence.saveAccount(account)
                        .thenReturn(PointsExchangeResponse.builder()
                            .item(EXCHANGE_PUBLISH)
                            .balance(safeBalance(account))
                            .publishTickets(account.getPublishTickets())
                            .message("已使用 1 张发布券")
                            .build());
                }));
    }

    private record ExchangeRule(int points, int dailyMax, int freeCount, String description) {
    }

    private static ExchangeRule resolveExchangeRule(PointsConfigDTO config, String item) {
        var rules = config == null ? null : config.getRules();
        if (rules == null || rules.getExchange() == null) {
            return null;
        }
        if (EXCHANGE_PUBLISH.equals(item)) {
            var rule = rules.getExchange().getPublishTicket();
            return new ExchangeRule(rule.getPoints(), rule.getDailyMaxCount(), rule.getFreeCount(),
                "瞬间发布券");
        }
        if (EXCHANGE_DASHBOARD.equals(item)) {
            var rule = rules.getExchange().getDashboardAdvanced();
            return new ExchangeRule(rule.getPoints(), rule.getDailyMaxCount(), rule.getFreeCount(),
                "数据看板高级版");
        }
        return null;
    }

    private static String exchangeActionOf(String item) {
        return EXCHANGE_PUBLISH.equals(item) ? EXCHANGE_PUBLISH_ACTION : EXCHANGE_DASHBOARD_ACTION;
    }

    private static int countRecordsToday(List<PointsRecord> records, String action, String date) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        var count = 0;
        for (var record : records) {
            if (record == null || !action.equals(record.getAction())) {
                continue;
            }
            if (isSameDay(record.getCreateTime(), date)) {
                count++;
            }
        }
        return count;
    }

    private static int safeTickets(UserPoints account) {
        return account.getPublishTickets() == null ? 0 : Math.max(0, account.getPublishTickets());
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
    public Mono<PointsHistoryResponse> listHistory(String openid, int page, int size) {
        return requireOpenid(openid)
            .flatMap(id -> persistence.listRecordsByOpenid(id)
                .map(records -> toHistoryPage(records, page, size)));
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

    private PointsHistoryResponse toHistoryPage(List<PointsRecord> records, int page, int size) {
        var normalizedPage = Math.max(page, DEFAULT_PAGE);
        var normalizedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        var sorted = records.stream()
            .sorted(Comparator.comparing(PointsRecord::getCreateTime,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
        var total = sorted.size();
        var from = Math.min(normalizedPage * normalizedSize, total);
        var to = Math.min(from + normalizedSize, total);
        var items = sorted.subList(from, to).stream()
            .map(this::toHistoryItem)
            .toList();
        return PointsHistoryResponse.builder()
            .items(items)
            .total(total)
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
