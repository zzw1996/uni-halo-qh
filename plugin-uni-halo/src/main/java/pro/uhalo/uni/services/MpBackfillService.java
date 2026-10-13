package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestPostPayloadDTO;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.scheme.MpIssue;
import pro.uhalo.uni.utils.MpConfigHelper;
import pro.uhalo.uni.utils.MpTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史 AI 日报 → 公众号稿件（补推）。
 *
 * <p>数据源是日报运行记录里<b>当时固化的内容</b>（{@code postPayloads} 的逐篇 markdown、
 * 或整份 {@code markdown}）：不需要重新抓取与 AI，发出去的就是当时那一期，
 * 也就不会「补推的内容和站上那期对不上」。</p>
 *
 * <p>两种形态：{@code merged}（整期合成一篇，默认）与 {@code per-article}（逐篇各成一篇）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpBackfillService {

    /** 合并版标题模板 */
    private static final String MERGED_TITLE = "AI 日报 · {date}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final DigestPersistence digestPersistence;
    private final MpIssueStore store;
    private final MpHtmlRenderer htmlRenderer;

    /** 可补推的日报期次（最近 N 期） */
    public Mono<List<DigestReport>> listReports(int limit) {
        return digestPersistence.listRecent(limit);
    }

    /**
     * 从一期日报生成公众号稿。
     *
     * @param reportName 日报记录名（metadata.name）
     * @param mode       merged（整期一篇）/ per-article（逐篇）
     */
    public Mono<List<MpIssue>> buildFromReport(String reportName, String mode) {
        if (!StringUtils.hasText(reportName)) {
            return Mono.error(new IllegalStateException("缺少日报记录名"));
        }
        var normalizedMode = "per-article".equalsIgnoreCase(mode) ? "per-article" : "merged";
        return settingFetcher.get(MpConfigHelper.groupName())
            .map(MpConfigHelper::parse)
            .defaultIfEmpty(MpConfigHelper.defaults())
            .flatMap(config -> digestPersistence.fetch(reportName)
                .switchIfEmpty(Mono.error(new IllegalStateException("日报记录不存在：" + reportName)))
                .flatMap(report -> {
                    if (!"SUCCESS".equals(report.getStatus())
                        && !StringUtils.hasText(report.getMarkdown())) {
                        return Mono.error(new IllegalStateException(
                            "该期日报没有生成成功（状态 " + report.getStatus() + "），无法补推"));
                    }
                    return "per-article".equals(normalizedMode)
                        ? buildPerArticle(report, config)
                        : buildMerged(report, config);
                }));
    }

    /** 整期一篇（合并版）：直接用日报全文，标题用固定模板 */
    private Mono<List<MpIssue>> buildMerged(DigestReport report, MpConfigDTO config) {
        if (!StringUtils.hasText(report.getMarkdown())) {
            return Mono.error(new IllegalStateException("该期日报没有可用的正文（markdown 为空）"));
        }
        var name = MpIssueStore.backfillName(report.getMetadata().getName(), "merged", 0);
        var issue = newBackfillIssue(name, report, "merged", 0);
        issue.setTitle(MpTextUtils.clipTitle(
            MERGED_TITLE.replace("{date}", String.valueOf(report.getDate())),
            MpTextUtils.MAX_TITLE_CHARS));
        issue.setDigest(fallbackDigestFromReport(report, config));
        issue.setMarkdown(report.getMarkdown());
        issue.setHtml(htmlRenderer.render(report.getMarkdown()));
        issue.setCover(report.getCover());
        issue.setArticleCount(report.getArticleCount());
        issue.setArticles(report.getArticleEntries());
        issue.setSitePostUrl(StringUtils.hasText(report.getPostUrl()) ? report.getPostUrl() : "");
        issue.setSitePostName(report.getPostName());
        issue.setState("GENERATED");
        issue.setFinishedAt(Instant.now());
        log.info("[UniHalo][Mp] 历史补推稿已生成（合并版）：{} ← {}", name, report.getMetadata().getName());
        return save(issue).map(List::of);
    }

    /** 逐篇：每篇载荷各成一稿（标题、封面、正文都用生成时固化下来的那一份） */
    private Mono<List<MpIssue>> buildPerArticle(DigestReport report, MpConfigDTO config) {
        var payloads = parsePayloads(report.getPostPayloads());
        if (payloads.isEmpty()) {
            return Mono.error(new IllegalStateException(
                "该期日报没有逐篇内容（生成于逐篇发布能力上线之前）："
                    + "请改用「整期合并」补推，或重新生成一期"));
        }
        var postUrls = parsePostUrls(report.getPosts());
        return Flux.range(0, payloads.size())
            .concatMap(index -> {
                var payload = payloads.get(index);
                var name = MpIssueStore.backfillName(report.getMetadata().getName(), "per-article",
                    index + 1);
                var issue = newBackfillIssue(name, report, "per-article", index + 1);
                issue.setTitle(MpTextUtils.clipTitle(payload.getTitle(),
                    MpTextUtils.MAX_TITLE_CHARS));
                issue.setDigest(MpTextUtils.tidyTail(MpTextUtils.clip(
                    MpHtmlRenderer.plainText(payload.getMarkdown()), config.getDigestWords())));
                issue.setMarkdown(payload.getMarkdown());
                issue.setHtml(htmlRenderer.render(payload.getMarkdown()));
                issue.setCover(payload.getCover());
                issue.setArticleCount(1);
                issue.setState("GENERATED");
                issue.setFinishedAt(Instant.now());
                if (index < postUrls.size() && StringUtils.hasText(postUrls.get(index))) {
                    issue.setSitePostUrl(postUrls.get(index));
                }
                return save(issue);
            })
            .collectList()
            .doOnNext(list -> log.info("[UniHalo][Mp] 历史补推稿已生成（逐篇 {} 篇）：← {}",
                list.size(), report.getMetadata().getName()));
    }

    private MpIssue newBackfillIssue(String name, DigestReport report, String mode, int index) {
        return MpIssueStore.newIssue(name, MpIssueStore.KIND_BACKFILL,
            report.getMetadata().getName() + "|" + mode + (index > 0 ? "|" + index : ""),
            report.getDate(), "", "历史补推");
    }

    /** 摘要兜底：逐条取文章标题拼一句（历史记录里没有 AI 摘要可用，这是最稳的兜底） */
    private String fallbackDigestFromReport(DigestReport report, MpConfigDTO config) {
        var entries = parseEntries(report.getArticleEntries());
        if (entries.isEmpty()) {
            return MpTextUtils.clip("本期精选 " + safeCount(report) + " 篇，详见正文。",
                config.getDigestWords());
        }
        var titles = entries.stream().limit(3).toList();
        var text = "本期精选 " + safeCount(report) + " 篇：" + String.join("、", titles);
        return MpTextUtils.tidyTail(MpTextUtils.clip(text, config.getDigestWords()));
    }

    private static int safeCount(DigestReport report) {
        return report.getArticleCount() == null ? 0 : report.getArticleCount();
    }

    private List<String> parseEntries(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            var node = OBJECT_MAPPER.readTree(json);
            var titles = new ArrayList<String>();
            for (var item : node) {
                var title = item.path("title").asText("");
                if (StringUtils.hasText(title)) {
                    titles.add(title);
                }
            }
            return titles;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<DigestPostPayloadDTO> parsePayloads(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                .constructCollectionType(List.class, DigestPostPayloadDTO.class));
        } catch (Exception ex) {
            log.warn("[UniHalo][Mp] 解析逐篇内容失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private List<String> parsePostUrls(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            var node = OBJECT_MAPPER.readTree(json);
            var urls = new ArrayList<String>();
            for (var item : node) {
                urls.add(item.path("url").asText(""));
            }
            return urls;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Mono<MpIssue> save(MpIssue fresh) {
        var name = fresh.getMetadata().getName();
        return store.fetch(name)
            .onErrorResume(ex -> Mono.empty())
            .flatMap(existing -> {
                fresh.getMetadata().setVersion(existing.getMetadata().getVersion());
                fresh.getMetadata()
                    .setCreationTimestamp(existing.getMetadata().getCreationTimestamp());
                fresh.setCreatedAt(existing.getCreatedAt());
                // 重新生成补推稿时保留已推送的媒资与状态：草稿还在公众号那边，
                // 状态被重置会让站长重复推送
                if (StringUtils.hasText(existing.getDraftMediaId())
                    && MpIssueStore.KIND_BACKFILL.equals(existing.getSourceKind())) {
                    fresh.setDraftMediaId(existing.getDraftMediaId());
                    fresh.setCoverMediaId(existing.getCoverMediaId());
                    fresh.setState(existing.getState());
                }
                fresh.setUpdatedAt(Instant.now());
                return store.update(fresh);
            })
            .switchIfEmpty(Mono.defer(() -> store.create(fresh)));
    }
}
