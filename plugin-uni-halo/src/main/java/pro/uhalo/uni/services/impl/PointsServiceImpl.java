package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminPointsRecordItemDTO;
import pro.uhalo.uni.dto.AdminPointsRecordListResponse;
import pro.uhalo.uni.dto.PointsActionRuleDTO;
import pro.uhalo.uni.dto.PointsConfigDTO;
import pro.uhalo.uni.dto.PointsEarnRequest;
import pro.uhalo.uni.dto.PointsEarnResponse;
import pro.uhalo.uni.dto.PointsEarnStatusResponse;
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
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.PointsConfigHelper;
import pro.uhalo.uni.utils.PointsRecordHelper;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

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

    private final PointsPersistence persistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final WxUserService wxUserService;

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
            .flatMap(id -> loadConfig()
                .flatMap(config -> {
                    ensureEnabled(config);
                    var action = PointsAction.parseEarnAction(request.getAction());
                    var refKey = resolveRefKey(request);
                    if (!StringUtils.hasText(refKey)) {
                        return Mono.error(new WxAuthException("articleName 或 refId 不能为空"));
                    }
                    var earnKey = buildDailyEarnKey(id, action.value(), refKey);
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> buildDuplicateEarnResponse(id))
                        .switchIfEmpty(processEarn(id, config, action, request, refKey, earnKey));
                }));
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
                    var rule = PointsConfigHelper.ruleFor(config, pointsAction.value());
                    var earnKey = buildDailyEarnKey(id, pointsAction.value(), refKey);
                    return persistence.findRecordByEarnKey(earnKey)
                        .flatMap(existing -> Mono.just(PointsEarnStatusResponse.builder()
                            .earnedToday(true)
                            .earned(0)
                            .capped(true)
                            .message(alreadyEarnedStatusMessage(pointsAction.value()))
                            .build()))
                        .switchIfEmpty(evaluateEarnAvailability(id, pointsAction.value(), rule));
                }));
    }

    @Override
    public Mono<PointsEarnResponse> signIn(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> loadConfig()
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
                }));
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
            .nickname(wxUser != null ? wxUser.getNickname() : null)
            .haloUserName(wxUser != null ? wxUser.getHaloUserName() : null)
            .action(action)
            .actionLabel(PointsAction.labelOf(action))
            .points(record.getPoints() == null ? 0 : record.getPoints())
            .balance(record.getBalance())
            .description(PointsRecordHelper.resolveDetailText(record))
            .contentTitle(PointsRecordHelper.resolveContentTitle(record))
            .refName(record.getRefName())
            .createTime(PointsRecordHelper.resolveCreateTime(record))
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
