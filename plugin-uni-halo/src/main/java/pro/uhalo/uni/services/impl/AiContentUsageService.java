package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.AiContentGenerationLog;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 后台「AI 定制内容」用量页的数据源。
 *
 * <p>回答两个问题：</p>
 * <ul>
 *   <li><b>谁还有几次</b>：每个用户的剩余生成次数、到期时间、今日已生成几次；</li>
 *   <li><b>生成了什么</b>：每次生成的触发来源、条数、标题、扣了几次、失败原因——
 *       既是「付费功能被怎么用了」的审计依据，也是排查「用户说没生成出来」的第一手材料。</li>
 * </ul>
 *
 * <p>数据都在已有记录里：额度与到期时间在 {@code UserPoints}，明细在
 * {@code AiContentGenerationLog}；这里只做拼装与展示层排序，不新增存储。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentUsageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 明细最多回看多少条（后台页面是「最近发生什么」，不需要全量导出） */
    private static final int LOG_SCAN_LIMIT = 2000;

    private final ReactiveExtensionClient client;

    /** 用户额度视图：剩余次数 / 到期 / 今日与累计生成次数 */
    public Mono<Map<String, Object>> usage(int page, int size, String keyword) {
        return Mono.zip(usersWithAccounts(), allLogs(null))
            .map(tuple -> {
                var accounts = tuple.getT1();
                var logs = tuple.getT2();
                var today = LocalDate.now(ZONE).toString();
                var rows = new ArrayList<Map<String, Object>>();
                accounts.forEach(view -> {
                    var logsOfUser = logs.stream()
                        .filter(log -> view.openid().equals(log.getWxOpenid()))
                        .toList();
                    var todayCount = (int) logsOfUser.stream()
                        .filter(log -> today.equals(log.getDate()))
                        .count();
                    var last = logsOfUser.isEmpty() ? null : logsOfUser.get(0);
                    var row = new LinkedHashMap<String, Object>();
                    row.put("openid", view.openid());
                    row.put("nickname", view.nickname());
                    row.put("avatarUrl", view.avatarUrl());
                    row.put("expireAt", AiContentConfigHelper.formatExpireAt(view.expireAt()));
                    row.put("daysRemaining", AiContentConfigHelper.daysRemaining(view.expireAt()));
                    row.put("active", AiContentConfigHelper.isActive(view.expireAt()));
                    row.put("remainingGenerations", view.credits());
                    row.put("todayGenerations", todayCount);
                    row.put("totalGenerations", logsOfUser.size());
                    row.put("lastGenerateAt", last == null ? "" : formatTime(last.getCreatedAt()));
                    row.put("lastTrigger", last == null ? "" : last.getTrigger());
                    rows.add(row);
                });
                if (StringUtils.hasText(keyword)) {
                    var needle = keyword.trim().toLowerCase();
                    rows.removeIf(row -> !matches(row, needle));
                }
                // 权益有效、还有次数、最近生成过的排前面：站长最关心这些人
                rows.sort(Comparator
                    .comparing((Map<String, Object> row) -> Boolean.TRUE.equals(row.get("active")))
                    .reversed()
                    .thenComparing(row -> (String) row.get("lastGenerateAt"),
                        Comparator.reverseOrder()));
                return paged(rows, page, size);
            });
    }

    /** 生成明细（openid 为空 = 所有人） */
    public Mono<Map<String, Object>> logs(int page, int size, String openid) {
        return allLogs(StringUtils.hasText(openid) ? openid.trim() : null)
            .map(all -> paged(all.stream().map(AiContentUsageService::toLogRow).toList(), page, size));
    }

    private Mono<List<AiContentGenerationLog>> allLogs(String openid) {
        var options = new ListOptions();
        if (StringUtils.hasText(openid)) {
            options.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        }
        return client.listAll(AiContentGenerationLog.class, options, null)
            .collectList()
            .map(items -> items.stream()
                .sorted(Comparator.comparing(
                    (AiContentGenerationLog log) -> log.getCreatedAt() == null
                        ? Instant.EPOCH : log.getCreatedAt()).reversed())
                .limit(LOG_SCAN_LIMIT)
                .toList())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取生成记录失败：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 小程序用户 + 其账户上的 AI 权益（未购买的用户也在列表里：站长要能看出「谁买了」） */
    private Mono<List<AccountView>> usersWithAccounts() {
        return Mono.zip(
                client.listAll(WxUser.class, new ListOptions(), null).collectList()
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][AiContent] 读取小程序用户失败：{}", ex.getMessage());
                        return Mono.just(List.of());
                    }),
                client.listAll(UserPoints.class, new ListOptions(), null).collectList()
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][AiContent] 读取积分账户失败：{}", ex.getMessage());
                        return Mono.just(List.of());
                    }))
            .map(tuple -> {
                var byOpenid = new LinkedHashMap<String, UserPoints>();
                tuple.getT2().forEach(account -> {
                    if (StringUtils.hasText(account.getWxOpenid())) {
                        byOpenid.put(account.getWxOpenid().trim(), account);
                    }
                });
                var views = new ArrayList<AccountView>();
                tuple.getT1().forEach(user -> {
                    var openid = user.getOpenid() == null ? "" : user.getOpenid().trim();
                    if (!StringUtils.hasText(openid)) {
                        return;
                    }
                    var account = byOpenid.get(openid);
                    views.add(new AccountView(openid,
                        StringUtils.hasText(user.getNickname())
                            ? user.getNickname() : "（未设置昵称）",
                        user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
                        account == null ? null : account.getAiContentExpireAt(),
                        AiContentEntitlementService.creditsOf(account)));
                });
                return List.copyOf(views);
            });
    }

    private static Map<String, Object> paged(List<Map<String, Object>> rows, int page, int size) {
        var total = rows.size();
        var safeSize = Math.max(1, size);
        var from = Math.min(total, Math.max(0, page) * safeSize);
        var to = Math.min(total, from + safeSize);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("items", rows.subList(from, to));
        payload.put("total", total);
        return payload;
    }

    private static Map<String, Object> toLogRow(AiContentGenerationLog entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("openid", entry.getWxOpenid());
        row.put("time", formatTime(entry.getCreatedAt()));
        row.put("date", entry.getDate());
        row.put("trigger", entry.getTrigger());
        row.put("status", entry.getStatus());
        row.put("itemCount", entry.getItemCount() == null ? 0 : entry.getItemCount());
        row.put("titles", parseTitles(entry.getTitles()));
        row.put("creditsAfter", entry.getCreditsAfter());
        row.put("creditsCharged", Boolean.TRUE.equals(entry.getCreditsCharged()));
        row.put("liveHours", entry.getLiveHours() == null ? 0 : entry.getLiveHours());
        row.put("error", entry.getError() == null ? "" : entry.getError());
        return row;
    }

    private static List<String> parseTitles(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            var titles = new ArrayList<String>();
            OBJECT_MAPPER.readTree(json).forEach(item -> titles.add(item.asText("")));
            return titles;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static boolean matches(Map<String, Object> row, String needle) {
        var nickname = String.valueOf(row.getOrDefault("nickname", "")).toLowerCase();
        var openid = String.valueOf(row.getOrDefault("openid", "")).toLowerCase();
        return nickname.contains(needle) || openid.contains(needle);
    }

    static String formatTime(Instant instant) {
        return instant == null ? "" : instant.atZone(ZONE).format(TIME_FMT);
    }

    /** 列表里的一行：小程序用户 + 账户上的 AI 权益 */
    private record AccountView(String openid, String nickname, String avatarUrl, Instant expireAt,
                               int credits) {
    }
}
