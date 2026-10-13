package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.AiContentGenerationLog;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * AI 定制内容生成记录（审计 + 计数）。
 *
 * <p>两件事合在一处：</p>
 * <ul>
 *   <li><b>审计</b>：后台「AI 定制内容」页面按用户列出「生成了什么、什么时候、扣了几次」；</li>
 *   <li><b>计数</b>：每日上限的判据是「今天已经生成几次」——按 {@code dayKey} 一次索引查询即可，
 *       不需要在账户上再维护一套「今天第几次」的计数（那种计数一旦与日志不一致就再也说不清）。</li>
 * </ul>
 *
 * <p>写记录失败只告警不抛错：日志不该让一次成功的生成变成失败。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentGenerationLogService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReactiveExtensionClient client;

    /** 今天的生成次数（用于每日上限判定） */
    public Mono<Integer> countToday(String openid) {
        return countOn(openid, LocalDate.now(ZONE).toString());
    }

    public Mono<Integer> countOn(String openid, String date) {
        if (!StringUtils.hasText(openid) || !StringUtils.hasText(date)) {
            return Mono.just(0);
        }
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("dayKey", dayKey(openid, date))));
        return client.listAll(AiContentGenerationLog.class, listOptions, null)
            .count()
            .map(Long::intValue)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取今日生成次数失败，按 0 计：{}", ex.getMessage());
                return Mono.just(0);
            });
    }

    /** 最近若干条记录（后台列表用；按时间倒序） */
    public Mono<List<AiContentGenerationLog>> listRecent(int limit) {
        return client.listAll(AiContentGenerationLog.class, new ListOptions(), null)
            .collectList()
            .map(items -> items.stream()
                .sorted((a, b) -> nullSafe(b.getCreatedAt()).compareTo(nullSafe(a.getCreatedAt())))
                .limit(Math.max(1, limit))
                .toList())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取生成记录失败：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 写一条生成记录（尽力而为：失败只记日志） */
    public Mono<AiContentGenerationLog> write(String openid, String date, String trigger,
        String status, List<String> titles, int itemCount, Integer creditsAfter,
        boolean creditsCharged, Integer liveHours, String error) {
        if (!StringUtils.hasText(openid) || !StringUtils.hasText(date)) {
            return Mono.empty();
        }
        var record = new AiContentGenerationLog();
        record.setWxOpenid(openid.trim());
        record.setDate(date);
        record.setDayKey(dayKey(openid, date));
        record.setTrigger(StringUtils.hasText(trigger) ? trigger : "manual");
        record.setStatus(status);
        record.setTitles(serializeTitles(titles));
        record.setItemCount(itemCount);
        record.setCreditsAfter(creditsAfter);
        record.setCreditsCharged(creditsCharged);
        record.setLiveHours(liveHours);
        record.setError(error == null ? "" : error);
        var now = Instant.now();
        record.setCreatedAt(now);
        var metadata = new Metadata();
        // 资源名带随机后缀：同一天可以有很多次生成，不能撞名
        metadata.setName("acgl-" + sha256Hex(openid.trim() + ":" + date + ":" + UUID.randomUUID()));
        metadata.setCreationTimestamp(now);
        record.setMetadata(metadata);
        return client.create(record)
            .doOnNext(saved -> log.info("[UniHalo][AiContent] 生成记录已写入：openid={} date={} "
                    + "trigger={} status={} 条数={} 剩余次数={}",
                mask(openid), date, trigger, status, itemCount,
                creditsAfter == null ? "不限" : creditsAfter))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 写入生成记录失败（不影响生成）：{}", ex.getMessage());
                return Mono.empty();
            });
    }

    /** 计数键：与 {@link AiContentGenerationLog#getDayKey()} 同一口径 */
    public static String dayKey(String openid, String date) {
        return (openid == null ? "" : openid.trim()) + ":" + (date == null ? "" : date);
    }

    private static Instant nullSafe(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }

    private static String serializeTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return "[]";
        }
        var safe = new ArrayList<String>();
        titles.forEach(title -> {
            if (StringUtils.hasText(title)) {
                safe.add(title.length() <= 80 ? title : title.substring(0, 80));
            }
        });
        try {
            return OBJECT_MAPPER.writeValueAsString(safe);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }
}
