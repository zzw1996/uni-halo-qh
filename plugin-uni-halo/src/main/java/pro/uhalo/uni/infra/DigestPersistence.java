package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.DigestReport;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * AI 日报运行记录持久化。
 */
@Component
@RequiredArgsConstructor
public class DigestPersistence {

    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

    private final ReactiveExtensionClient client;

    public Mono<DigestReport> create(DigestReport report) {
        return client.create(report);
    }

    public Mono<DigestReport> update(DigestReport report) {
        return client.update(report);
    }

    public Mono<DigestReport> fetch(String name) {
        return client.fetch(DigestReport.class, name);
    }

    /** 某一天的全部运行记录（一天可能有多条：多个触发时段 + 手动重跑） */
    public Mono<List<DigestReport>> listByDate(String date) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("date", date)));
        return client.listAll(DigestReport.class, listOptions, null).collectList();
    }

    public Mono<List<DigestReport>> listRecent(int limit) {
        return client.listAll(DigestReport.class, new ListOptions(), null)
            .collectList()
            .map(list -> list.stream()
                .sorted(Comparator.comparing(DigestReport::getStartedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .toList());
    }

    public Mono<DigestReport> findLatestSuccess() {
        return client.listAll(DigestReport.class, new ListOptions(), null)
            .filter(report -> "SUCCESS".equals(report.getStatus()))
            .collectList()
            .map(list -> list.stream()
                .sorted(Comparator.comparing(DigestReport::getStartedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null));
    }

    /**
     * 构造一条运行记录。
     *
     * <p>资源名只能包含小写字母、数字和短横线，所以 slot（HH:mm 或 manual-143005）
     * 需要先去掉冒号再拼进 metadata.name。</p>
     */
    public static DigestReport newReport(String date, String slot) {
        var report = new DigestReport();
        report.setDate(date);
        report.setSlot(slot);
        report.setStatus("RUNNING");
        report.setPublishState("PENDING");
        report.setStartedAt(Instant.now());
        var metadata = new Metadata();
        metadata.setName(resourceName(date, slot));
        metadata.setCreationTimestamp(Instant.now());
        report.setMetadata(metadata);
        return report;
    }

    public static String resourceName(String date, String slot) {
        var safeSlot = slot == null ? "manual" : slot.replaceAll("[^0-9a-zA-Z]", "");
        if (safeSlot.isEmpty()) {
            safeSlot = "manual";
        }
        return ("digest-" + date + "-" + safeSlot).toLowerCase(Locale.ROOT);
    }

    /**
     * 手动触发的槽位名。
     *
     * <p>带时分秒是为了让同一天可以手动重跑多次（定时槽位是 HH:mm，同一天同一槽位只跑一次）。</p>
     */
    public static String manualSlot(ZonedDateTime now) {
        return "manual-" + now.format(SLOT_FORMAT);
    }
}
