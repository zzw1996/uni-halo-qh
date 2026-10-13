package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.MpIssue;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 公众号稿件持久化。
 *
 * <p>资源名即幂等键：按方向生成的是 {@code mp-<date>-<direction>}（一天一方向只有一条，
 * 重复触发复用而不是新建）；历史补推的是 {@code mpbf-<hash>}。</p>
 */
@Component
@RequiredArgsConstructor
public class MpIssueStore {

    public static final String KIND_ISSUE = "ISSUE";
    public static final String KIND_BACKFILL = "BACKFILL";

    /**
     * 公众号专属文章在跨天去重表里的命名空间前缀。
     *
     * <p>{@code DigestSeenStore} 按「来源名前缀」分流：公众号的已发记录都带这个前缀，
     * 站点日报读不到它们，两边各自去重、互不抢占素材。</p>
     */
    public static final String KIND_PREFIX = "mp-";

    private final ReactiveExtensionClient client;

    public Mono<MpIssue> fetch(String name) {
        return client.fetch(MpIssue.class, name);
    }

    public Mono<MpIssue> create(MpIssue issue) {
        return client.create(issue);
    }

    public Mono<MpIssue> update(MpIssue issue) {
        return client.update(issue);
    }

    public Mono<MpIssue> delete(MpIssue issue) {
        return client.delete(issue);
    }

    /** 某一天的全部稿件（按方向顺序） */
    public Mono<List<MpIssue>> listByDate(String date) {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(equal("date", date)));
        return client.listAll(MpIssue.class, options, null)
            .collectList()
            .map(list -> list.stream().sorted(byCreatedDesc()).toList());
    }

    /** 最近若干条（后台列表） */
    public Mono<List<MpIssue>> listRecent(int limit) {
        return client.listAll(MpIssue.class, new ListOptions(), null)
            .collectList()
            .map(list -> list.stream()
                .sorted(byCreatedDesc())
                .limit(Math.max(1, limit))
                .toList());
    }

    /** 历史日报补推的稿子（列表页要标出「这期已生成过公众号稿」） */
    public Mono<List<MpIssue>> listByKind(String kind, int limit) {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(equal("sourceKind", kind)));
        return client.listAll(MpIssue.class, options, null)
            .collectList()
            .map(list -> list.stream()
                .sorted(byCreatedDesc())
                .limit(Math.max(1, limit))
                .toList());
    }

    private static Comparator<MpIssue> byCreatedDesc() {
        return Comparator.comparing(MpIssue::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /** 新建一条空的稿件记录（调用方随后填内容） */
    public static MpIssue newIssue(String name, String kind, String sourceKey, String date,
        String direction, String directionLabel) {
        var issue = new MpIssue();
        issue.setSourceKind(kind);
        issue.setSourceKey(sourceKey);
        issue.setDate(date);
        issue.setDirection(direction);
        issue.setDirectionLabel(directionLabel);
        issue.setState("GENERATING");
        issue.setAttempts(1);
        var now = Instant.now();
        issue.setCreatedAt(now);
        issue.setUpdatedAt(now);
        issue.setStartedAt(now);
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(now);
        issue.setMetadata(metadata);
        return issue;
    }

    /** 按方向生成的稿件资源名（一天一方向一条，幂等键） */
    public static String issueName(String date, String direction) {
        return ("mp-" + date + "-" + safe(direction)).toLowerCase(Locale.ROOT);
    }

    /** 「每篇精选各一篇」粒度下的资源名：同一天同一方向的第 index 篇 */
    public static String articleIssueName(String date, String direction, int index) {
        return ("mp-" + date + "-" + safe(direction) + "-" + String.format("%02d", index + 1))
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 是否是「乐观锁冲突」（Halo 的 {@code metadata.version} 对不上）。
     *
     * <p>一次推送要在同一条记录上写很多步（封面素材 id、草稿 media_id、状态、日志…），
     * 而 {@code client.update()} 返回的是新实例——只要某一步还在用旧引用，
     * 就会撞上：{@code Failed to update versioned entity ... (version '5');
     * Was the entity updated or deleted concurrently?}。
     * 调用方据此重读记录后重试，而不是把错误抛给站长。</p>
     */
    public static boolean isVersionConflict(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("concurrently")
            || normalized.contains("versioned entity")
            || normalized.contains("optimistic");
    }

    /** 历史补推的稿件资源名（同一期同一模式只有一条） */
    public static String backfillName(String reportName, String mode, int index) {
        var key = reportName + ":" + mode + (index > 0 ? ":" + index : "");
        return "mpbf-" + hash8(key);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        var cleaned = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private static String hash8(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (var i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
