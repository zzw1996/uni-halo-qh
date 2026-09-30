package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.scheme.DigestSeenArticle;
import pro.uhalo.uni.utils.DigestTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static run.halo.app.extension.index.query.QueryFactory.in;

/**
 * AI 日报「已发文章」记录的读写（跨天去重的持久层）。
 *
 * <p>查询口径：<b>最近 14 天</b>的记录一次索引查询拿回去重键集合——
 * 14 天大于抓取窗（默认 48h）+ 分类补抓（最长 168h）的最大重叠，
 * 老数据不参与查询也就无需 GC；超出窗口的常青内容允许再次入选。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestSeenStore {

    /** 跨天去重的记忆窗口：必须 ≥ 抓取时间窗 + 补抓窗口的最大天数（7 天）再留余量 */
    private static final int REMEMBER_DAYS = 14;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter RESOURCE_DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReactiveExtensionClient client;

    /** 最近 N 天已发布文章的去重键集合；读取失败按空集合处理（本轮退化为单轮去重，不阻断生成） */
    public Mono<Set<String>> recentKeys() {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(in("seenDate", lastDays(REMEMBER_DAYS))));
        return client.listAll(DigestSeenArticle.class, listOptions, null)
            .map(DigestSeenArticle::getDedupKey)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取跨天去重记录失败，本轮退化为单轮去重: {}",
                    ex.getMessage());
                return Mono.just(Set.of());
            });
    }

    /** 生成成功后把入选文章记为「已发」。重复写入（同一天两轮生成的同名记录）命中唯一资源名冲突，静默忽略 */
    public Mono<Void> recordAll(List<DigestArticleDTO> articles, String digestName) {
        if (articles == null || articles.isEmpty()) {
            return Mono.empty();
        }
        var now = Instant.now();
        var today = LocalDate.ofInstant(now, ZONE);
        return Flux.fromIterable(articles)
            .flatMap(article -> {
                var record = newRecord(article, digestName, today, now);
                if (record == null) {
                    // 既没有链接也没有标题的文章没有稳定去重键，跳过记录
                    return Mono.empty();
                }
                return client.create(record)
                    .onErrorResume(ex -> {
                        if (!isDuplicateNameError(ex)) {
                            log.warn("[UniHalo][Digest] 记录已发文章失败（跳过）: name={}, {}",
                                record.getMetadata().getName(), ex.getMessage());
                        }
                        return Mono.empty();
                    });
            })
            .then();
    }

    private DigestSeenArticle newRecord(DigestArticleDTO article, String digestName,
        LocalDate today, Instant now) {
        var key = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
        if (!StringUtils.hasText(key)) {
            return null;
        }
        var record = new DigestSeenArticle();
        record.setDedupKey(key);
        record.setSeenDate(today.format(DAY_FMT));
        record.setTitle(StringUtils.hasText(article.getTitleZh())
            ? article.getTitleZh() : article.getTitle());
        record.setDigestName(digestName);
        record.setSeenAt(now);
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName("dsa-" + today.format(RESOURCE_DAY_FMT) + "-" + hash8(key));
        metadata.setCreationTimestamp(now);
        record.setMetadata(metadata);
        return record;
    }

    private static List<String> lastDays(int days) {
        var dates = new ArrayList<String>(days);
        var today = LocalDate.now(ZONE);
        for (var i = 0; i < days; i++) {
            dates.add(today.minusDays(i).format(DAY_FMT));
        }
        return dates;
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
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 在 JVM 里必然存在；兜底用 hashCode 保持功能可用
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }
}
