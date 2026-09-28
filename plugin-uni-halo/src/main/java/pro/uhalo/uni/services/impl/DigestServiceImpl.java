package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import pro.uhalo.uni.dto.DigestPostPayloadDTO;
import pro.uhalo.uni.dto.DigestPreflightItemDTO;
import pro.uhalo.uni.dto.DigestArticleEntryDTO;
import pro.uhalo.uni.dto.DigestPostResultDTO;
import pro.uhalo.uni.dto.DigestStatsDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.services.DigestProgressTracker;
import pro.uhalo.uni.services.DigestRenderer;
import pro.uhalo.uni.services.DigestService;
import pro.uhalo.uni.services.DigestTaxonomyService;
import pro.uhalo.uni.services.HaloPostPublisher;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiHelper;
import pro.uhalo.uni.utils.DigestAiUsageLog;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestCoverGenerator;
import pro.uhalo.uni.utils.DigestSelection;
import pro.uhalo.uni.utils.DigestTextUtils;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * AI 日报流水线：抓取 → 打分 → 摘要 → 趋势 → 配图 → 成稿 → 发布。
 *
 * <p>并发控制用一把进程内开关：定时任务与后台「立即生成」共用同一条流水线，
 * 一次只允许一个在跑，否则两次运行会同时打同一个 AI Key、也会抢同一批附件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestServiceImpl implements DigestService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int AI_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENT_AI = 2;
    private static final int AI_OUTPUT_CHARS_BUDGET = 5000;
    private static final int AI_BATCH_SPLIT_DEPTH = 2;
    private static final int MIN_WORDS_PER_ARTICLE = 80;
    private static final int IMAGE_UPLOAD_CONCURRENCY = 4;

    /** 候选池上限：给「必须有封面图」与分类保底留补位空间，又不想为太多文章白抓一次页面 */
    private static final int MAX_CANDIDATE_POOL = 50;
    private static final int DESCRIPTION_FALLBACK_LIMIT = 200;

    /** 每个分类至少进池子的篇数：低于这个数，冷门分类的保底名额就没有可挑的素材 */
    private static final int MIN_PER_CATEGORY_POOL = 3;

    /** 逐篇发布时单篇的发布确认上限（秒）：单篇正文小，等太久没有意义 */
    private static final int ARTICLE_PUBLISH_WAIT_SECONDS = 180;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final DigestPersistence persistence;
    private final DigestFeedService feedService;
    private final DigestAiClient aiClient;
    private final DigestAiModelService aiModelService;
    private final DigestRenderer renderer;
    private final DigestAssetUploader assetUploader;
    private final HaloPostPublisher publisher;
    private final DigestProgressTracker progressTracker;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public Mono<List<DigestPreflightItemDTO>> preflight() {
        return loadConfig().flatMap(config -> publisher.preflight(config)
            .doOnNext(items -> log.info("[UniHalo][Digest] 发布环境自检完成：{}",
                items.stream().map(item -> (item.isOk() ? "✔ " : "✘ ") + item.getItem())
                    .collect(Collectors.joining(" / ")))));
    }

    @Override
    public Mono<List<DigestReport>> listReports(int limit) {
        return persistence.listRecent(limit);
    }

    @Override
    public Mono<DigestReport> getReport(String name) {
        return persistence.fetch(name);
    }

    @Override
    public Mono<Void> deleteReport(String name) {
        return persistence.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalStateException("日报记录不存在：" + name)))
            .flatMap(report -> {
                if ("RUNNING".equals(report.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "该记录正在运行中，不能删除；等它跑完或状态变为失败后再删"));
                }
                log.info("[UniHalo][Digest] 删除运行记录 name={} date={} status={}",
                    name, report.getDate(), report.getStatus());
                return persistence.delete(report);
            })
            .then();
    }

    @Override
    public Mono<DigestReport> run(String slot, boolean publish) {
        if (!running.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("已有一次日报生成任务在执行中，请稍后再试"));
        }
        return loadConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    return Mono.error(new IllegalStateException(
                        "AI 日报未开启，请先在插件设置「AI 日报」中打开总开关"));
                }
                aiClient.resetForNewRun();
                // 价格表为空时后台去官网读一次：不阻塞本轮（本轮用内置表，数值同源），
                // 下次运行就能按官网价算
                // defer + onErrorResume：这段是「顺手做的事」，任何异常都不许影响日报本身
                reactor.core.publisher.Mono.defer(() -> aiModelService.fillPricesIfEmpty())
                    .onErrorResume(ex -> reactor.core.publisher.Mono.empty())
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .subscribe(ignored -> {
                        // 成功已由服务层记日志
                    }, ex -> log.debug("[UniHalo][Digest] 自动读取单价结束：{}", ex.getMessage()));
                var now = ZonedDateTime.now(DigestConfigHelper.zoneOf(config.getTimezone()));
                var date = now.format(DATE_FORMAT);
                progressTracker.generating("开始抓取 RSS 源（时间窗 " + config.getHours() + " 小时）…");
                log.info("[UniHalo][Digest] ===== 开始生成日报 ===== date={} slot={} 是否发布={} "
                        + "时间窗={}h 精选={}篇 每类至少={}篇 每篇≈{}字 语言={} 通道={} "
                        + "图片={} 配图兜底={}",
                    date, slot, publish, config.getHours(), config.getTopN(),
                    config.getMinPerCategory(), config.getWords(),
                    config.getLanguage(), DigestAiChannelResolver.describe(config),
                    config.isImageEnabled(),
                    DigestConfigHelper.coverFallbackLabel(config.getCoverFallbackMode()));
                // 同一日期+时段只有一条记录（资源名固定）。重试必须复用它，
                // 否则 create 会撞「已存在」，重试永远不会真正执行、也永远到不了次数上限。
                return persistence.fetch(DigestPersistence.resourceName(date, slot))
                    // 记录不存在时 fetch 会报错，按「没有旧记录」处理
                    .onErrorResume(ex -> Mono.empty())
                    .flatMap(existing -> prepareRetry(existing, date, slot))
                    .switchIfEmpty(Mono.defer(() ->
                        persistence.create(DigestPersistence.newReport(date, slot))))
                    .flatMap(saved -> execute(saved, config, publish, now)
                        .flatMap(output -> markSuccess(saved, output, config))
                        .onErrorResume(ex -> markFailed(saved, ex)))
                    // 无论成功还是失败都要收尾进度：否则页面上会一直显示「进行中」
                    .doOnNext(this::finishProgress)
                    .doOnError(ex -> progressTracker.failed(describeFailure(ex)));
            })
            .doFinally(signal -> running.set(false));
    }

    @Override
    public Mono<DigestReport> publishReport(String name) {
        if (!running.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("已有一次日报生成任务在执行中，请稍后再试"));
        }
        log.info("[UniHalo][Digest] 收到重新发布请求：name={}", name);
        return persistence.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalStateException("日报记录不存在：" + name)))
            .flatMap(report -> loadConfig().flatMap(config -> {
                // 先校验有没有可发的内容，再落库改状态：避免点了个空记录却把状态改成「发布中」
                var payloads = parsePayloads(report.getPostPayloads());
                if (payloads.isEmpty()) {
                    if (!StringUtils.hasText(report.getMarkdown())) {
                        return Mono.error(new IllegalStateException(
                            "该日报既没有备好的发布内容，也没有可发布的正文"));
                    }
                    // 发布内容固化之前生成的老记录：退回用日报正文补发一篇
                    log.warn("[UniHalo][Digest] 该记录生成于逐篇发布能力上线之前（没有随报告固化的"
                        + "逐篇内容），只能按日报正文整体补发一篇；如需逐篇发布，请重新生成一期");
                    var title = buildTitle(config, report.getDate());
                    payloads = List.of(DigestPostPayloadDTO.builder()
                        .title(title)
                        .slug(buildSlug(title))
                        .markdown(report.getMarkdown())
                        .cover(report.getCover() == null ? "" : report.getCover())
                        .build());
                    // 随「发布中」那一次写入一起落库，之后就不用再走这条兜底
                    report.setPostPayloads(serializePayloads(payloads));
                }
                var resolved = payloads;
                return markPublishing(report)
                    .flatMap(publishing -> republishAll(resolved,
                            parsePosts(publishing.getPosts()), config)
                        .flatMap(results -> updatePublishResult(publishing, results))
                        .onErrorResume(ex -> markPublishFailed(publishing, ex)));
            }))
            .doFinally(signal -> running.set(false));
    }

    /**
     * 发布或补发全部备好的内容。
     *
     * <p>已经建过稿的（结果里带 postName）只重新触发发布，没建过稿的才走建稿；
     * 因此「生成了但没发布」和「发了一半失败」都能用同一个按钮救回来。</p>
     */
    private Mono<List<DigestPostResultDTO>> republishAll(List<DigestPostPayloadDTO> payloads,
        List<DigestPostResultDTO> existingResults, DigestConfigDTO config) {
        var createdCount = existingResults.stream()
            .filter(result -> StringUtils.hasText(result.getPostName()))
            .count();
        log.info("[UniHalo][Digest] 开始发布 {} 篇（其中 {} 篇已建稿，将直接重新触发发布）",
            payloads.size(), createdCount);
        var total = payloads.size();
        return Flux.range(0, total)
            .concatMap(index -> {
                var payload = payloads.get(index);
                // 结果与内容按下标一一对应：第 N 篇失败不会打乱后面几篇的对应关系
                var existing = index < existingResults.size() ? existingResults.get(index) : null;
                var postName = existing == null ? null : existing.getPostName();
                progressTracker.publishing(total, index, payload.getTitle());
                return publishOrRepublish(payload, postName, config)
                    .onErrorResume(ex -> {
                        log.error("[UniHalo][Digest] 第 {}/{} 篇「{}」发布失败：{}", index + 1,
                            total, payload.getTitle(), ex.getMessage());
                        return Mono.just(failedPost(payload.getTitle(), ex));
                    });
            })
            .collectList()
            .doOnNext(results -> progressTracker.publishingFinished(total,
                (int) countPublished(results)));
    }

    /**
     * 标记「正在发布」并清掉上一次的失败原因。
     *
     * <p>点发布之后页面上不该还挂着上一次的红字原因——那会让人以为这次也失败了；
     * 这里先把原因清空并改成发布中，等结果出来再写新的。</p>
     */
    private Mono<DigestReport> markPublishing(DigestReport report) {
        report.setError(null);
        report.setPublishState("PUBLISHING");
        // 用 update 返回的实例继续后续操作（resourceVersion 已刷新）
        return persistence.update(report);
    }

    /** 发布过程整体失败时也要把状态落定，不能让记录一直停在「发布中」 */
    private Mono<DigestReport> markPublishFailed(DigestReport report, Throwable ex) {
        report.setPublishState("FAILED");
        report.setError(StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
            : ex.getClass().getSimpleName());
        log.error("[UniHalo][Digest] 发布失败：{}", report.getError());
        return persistence.update(report);
    }

    private Mono<DigestReport> updatePublishResult(DigestReport report,
        List<DigestPostResultDTO> results) {
        report.setPublishState(PipelineOutput.resolveState(results));
        report.setPosts(serializePosts(results));
        report.setPostCount(results.size());
        var first = results.stream().filter(post -> StringUtils.hasText(post.getUrl()))
            .findFirst().orElse(null);
        report.setPostName(first == null ? null : first.getPostName());
        report.setPostUrl(first == null ? null : first.getUrl());
        report.setError(PipelineOutput.failureSummary(results));
        log.info("[UniHalo][Digest] 重新发布结果：状态={} 已发布 {}/{} 篇{}",
            report.getPublishState(), countPublished(results), results.size(),
            StringUtils.hasText(report.getError()) ? " 未发布原因：" + report.getError() : "");
        return persistence.update(report);
    }

    // ── 流水线 ──

    private Mono<PipelineOutput> execute(DigestReport report, DigestConfigDTO config, boolean publish,
        ZonedDateTime startedAt) {
        var startedMs = System.currentTimeMillis();
        var catalog = catalogOf(config);
        var trace = new RunTrace(config);
        return feedService.fetchRecent(config.getHours())
            .flatMap(feeds -> {
                // 去重放在评分之前：转载的同一篇文章没必要重复评分，也不该占两个名额
                var articles = deduplicate(feeds.getArticles(), trace);
                return ensureHasArticles(feeds)
                    .then(scoreAll(articles, config, trace))
                    .map(scored -> selectCandidates(scored, config))
                    // 先给候选池解析头图，没图的才能被后面有图的文章顶上来
                    .flatMap(candidates -> preResolveImages(candidates, config))
                    .map(candidates -> DigestSelection.selectByCategory(candidates, catalog,
                        config.getMinPerCategory(), config.getMaxPerCategory(), config.getTopN(),
                        config.isRequireImage()))
                    .flatMap(selection -> supplementMissingCategories(selection, catalog,
                        feeds.getArticles(), config, trace))
                    .flatMap(selection -> ensureHasSelection(selection, config)
                        .then(summarizeBatchwise(selection.articles(), config, trace))
                        .flatMap(selected -> generateOverview(selected, config, trace)
                            .flatMap(overview -> uploadImages(selected, config)
                                .flatMap(imageCount -> generateFallbackCovers(selected, config,
                                    catalog, startedAt, trace)
                                    .flatMap(placeholderCount -> enforceImageRequirement(selected,
                                        config)
                                        .map(finalArticles -> buildReport(finalArticles, overview,
                                            feeds, selection, trace, imageCount, placeholderCount,
                                            config, startedAt, startedMs))))))
                        .flatMap(output -> persistDraft(report, output, config))
                        .flatMap(output -> publishIfEnabled(output, config, publish)));
            });
    }

    /**
     * 候选池：全局高分 + 每个分类的头部文章。
     *
     * <p>只取全局前 N 篇是不够的：冷门分类可能整类都进不了池子，
     * 「每个分类至少几篇」也就无从谈起（配额再高，池子里没素材也选不出来）。
     * 所以先把每个分类的前几篇钉进池子，再用全局高分把池子补满到上限。</p>
     *
     * <p>开了「只发布带封面图」时，靠前的文章可能没图，需要后面的文章补位，
     * 因此池子比精选篇数更宽。</p>
     */
    private List<DigestArticleDTO> selectCandidates(List<DigestArticleDTO> scored,
        DigestConfigDTO config) {
        var sorted = new ArrayList<>(scored);
        sorted.sort(Comparator.comparingInt(DigestArticleDTO::getTotalScore).reversed());
        var pool = new java.util.LinkedHashSet<DigestArticleDTO>();
        if (config.getMinPerCategory() > 0) {
            var perCategory = Math.max(MIN_PER_CATEGORY_POOL, config.getMinPerCategory() * 2);
            var counts = new java.util.HashMap<String, Integer>();
            for (var article : sorted) {
                var key = article.getCategory() == null ? "" : article.getCategory();
                if (counts.getOrDefault(key, 0) >= perCategory) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
                pool.add(article);
            }
        }
        for (var article : sorted) {
            if (pool.size() >= MAX_CANDIDATE_POOL) {
                break;
            }
            pool.add(article);
        }
        return new ArrayList<>(pool);
    }

    /**
     * 链接去重：同一篇文章常被多个源转载，重复文章既白花 AI 调用，也会占掉别的文章的名额。
     *
     * <p>同一条去重键保留**先出现**的那一份：去重发生在评分之前，此时还没有分数可比，
     * 而「先出现」至少是可预期、可复现的。</p>
     */
    private List<DigestArticleDTO> deduplicate(List<DigestArticleDTO> articles, RunTrace trace) {
        var kept = new java.util.LinkedHashMap<String, DigestArticleDTO>();
        for (var article : articles) {
            var key = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
            if (!StringUtils.hasText(key)) {
                // 既没有链接也没有标题：无从判断是不是同一篇，按对象身份各留一份，
                // 否则这些「无从判断」的条目会被合成一篇、静默丢掉内容
                key = "@" + System.identityHashCode(article);
            }
            kept.putIfAbsent(key, article);
        }
        var list = new ArrayList<>(kept.values());
        trace.deduped = articles.size() - list.size();
        if (trace.deduped > 0) {
            log.info("[UniHalo][Digest] 链接去重：{} 篇重复转载，保留 {} 篇参与评分",
                trace.deduped, list.size());
        }
        return list;
    }

    private Mono<List<DigestArticleDTO>> preResolveImages(List<DigestArticleDTO> candidates,
        DigestConfigDTO config) {
        if (!config.isImageEnabled() || !config.isRequireImage()) {
            return Mono.just(candidates);
        }
        return feedService.resolveLeadImages(candidates);
    }

    /**
     * 分类有缺口时放宽时间窗补抓一轮。
     *
     * <p>只对「保底篇数没凑够」的分类做，且补抓回来的文章排在时间窗内的文章之后
     * （见 {@code DigestSelection} 的排序口径），因此不会挤掉当天的新文章。
     * 补抓失败不阻断本轮：宁可少一个分类，也不能整期发不出去。</p>
     */
    private Mono<DigestSelection.CategorySelection> supplementMissingCategories(
        DigestSelection.CategorySelection selection, DigestCategoryCatalog catalog,
        List<DigestArticleDTO> fetched, DigestConfigDTO config, RunTrace trace) {
        if (!selection.hasMissingCategories()) {
            return Mono.just(selection);
        }
        var missing = selection.missingCategories();
        if (!config.isCategoryFallbackEnabled()) {
            log.warn("[UniHalo][Digest] 以下分类本期没有凑够 {} 篇：{}（补抓已关闭）",
                selection.minPerCategory(), labels(missing, catalog));
            return Mono.just(selection);
        }
        var hours = Math.max(config.getCategoryFallbackHours(), config.getHours());
        if (hours <= config.getHours()) {
            log.warn("[UniHalo][Digest] 补抓时间窗（{}h）不大于主时间窗（{}h），跳过补抓；"
                + "本期缺口分类：{}", hours, config.getHours(), labels(missing, catalog));
            return Mono.just(selection);
        }
        log.info("[UniHalo][Digest] 分类缺口 {} 个（{}），用 {} 小时窗口补抓一轮",
            missing.size(), labels(missing, catalog), hours);
        progressTracker.generating("分类缺口补抓中（" + labels(missing, catalog) + "）…");
        var seen = new java.util.HashSet<String>();
        for (var article : fetched) {
            seen.add(DigestTextUtils.dedupKey(article.getLink(), article.getTitle()));
        }
        return feedService.fetchRecent(hours)
            .map(result -> pickFallbackCandidates(result.getArticles(), seen,
                config.getCategoryFallbackScanLimit()))
            .flatMap(extra -> {
                if (extra.isEmpty()) {
                    log.warn("[UniHalo][Digest] 补抓没有找到新的候选文章，本期缺口分类：{}",
                        labels(missing, catalog));
                    return Mono.just(selection);
                }
                extra.forEach(article -> article.setSupplementary(true));
                trace.fallbackFetched = extra.size();
                log.info("[UniHalo][Digest] 补抓窗口内新增 {} 篇候选，开始补评（最多 {} 篇）",
                    extra.size(), config.getCategoryFallbackScanLimit());
                return scoreAll(extra, config, trace)
                    .flatMap(scored -> preResolveImages(scored, config))
                    .map(scored -> {
                        var merged = new ArrayList<>(selection.articles());
                        merged.addAll(scored);
                        var reselected = DigestSelection.selectByCategory(merged, catalog,
                            config.getMinPerCategory(), config.getMaxPerCategory(),
                            config.getTopN(), config.isRequireImage());
                        log.info("[UniHalo][Digest] 补抓后重新精选：{} 篇（补位 {} 篇），"
                                + "仍有缺口：{}",
                            reselected.articles().size(), countSupplementary(reselected.articles()),
                            reselected.hasMissingCategories()
                                ? labels(reselected.missingCategories(), catalog) : "无");
                        return reselected;
                    });
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 分类补抓失败，按现有候选继续：{}", ex.getMessage());
                return Mono.just(selection);
            });
    }

    /**
     * 从补抓结果里挑出值得再评的候选。
     *
     * <p>按发布时间倒序（越新越可能被选上），去掉第一轮已经见过的文章，
     * 最多取 {@code limit} 篇——补抓的 AI 成本必须有上限。</p>
     */
    private static List<DigestArticleDTO> pickFallbackCandidates(List<DigestArticleDTO> articles,
        java.util.Set<String> seen, int limit) {
        if (articles == null || articles.isEmpty() || limit <= 0) {
            return List.of();
        }
        var sorted = new ArrayList<>(articles);
        sorted.sort(Comparator.comparing(DigestArticleDTO::getPubDate,
            Comparator.nullsLast(Comparator.reverseOrder())));
        var picked = new ArrayList<DigestArticleDTO>();
        for (var article : sorted) {
            if (picked.size() >= limit) {
                break;
            }
            var key = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
            // 没有可用的去重键时不做「见过」判断（见 deduplicate 的同类处理）
            if (StringUtils.hasText(key) && !seen.add(key)) {
                continue;
            }
            picked.add(article);
        }
        return picked;
    }

    /** 一篇都没选出来时直接失败：这轮没有任何可发内容，发出去也是空日报 */
    private Mono<Void> ensureHasSelection(DigestSelection.CategorySelection selection,
        DigestConfigDTO config) {
        if (!selection.articles().isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new IllegalStateException("时间窗内没有选出任何可用文章"
            + (config.isRequireImage() ? "（已开启「只发布带封面图的文章」，候选都被封面要求挡掉了）" : "")
            + "；可放宽时间窗或精选篇数，或在插件设置里检查分类清单与封面要求"));
    }

    /**
     * 占位封面：给「配额内但配不到原文头图」的文章生成一张标题卡片。
     *
     * <p>这是「每个分类至少一篇带封面」的最后一环：没有它，某个分类只剩无图文章时，
     * 要么发无图文章、要么整个分类没有文章，两条路都不满足站长的要求。
     * 生成失败（字体、附件策略等问题）只跳过该篇，不阻断本轮。</p>
     */
    private Mono<Integer> generateFallbackCovers(List<DigestArticleDTO> articles,
        DigestConfigDTO config, DigestCategoryCatalog catalog, ZonedDateTime generatedAt,
        RunTrace trace) {
        if (!config.isImageEnabled() || !config.isRequireImage()
            || !DigestConfigHelper.COVER_FALLBACK_PLACEHOLDER
                .equals(config.getCoverFallbackMode())) {
            return Mono.just(0);
        }
        var missing = articles.stream()
            .filter(article -> !DigestSelection.hasUploadedImage(article))
            .toList();
        if (missing.isEmpty()) {
            return Mono.just(0);
        }
        log.info("[UniHalo][Digest] {} 篇没有可用的原文头图，按「配图兜底={}」生成标题卡片封面",
            missing.size(), DigestConfigHelper.coverFallbackLabel(config.getCoverFallbackMode()));
        var dateText = generatedAt.format(DATE_FORMAT);
        return siteTitle().flatMap(siteName -> Flux.fromIterable(missing)
            .index()
            .flatMapSequential(tuple -> {
                var index = tuple.getT1().intValue();
                var article = tuple.getT2();
                byte[] bytes;
                try {
                    bytes = DigestCoverGenerator.renderCard(article.getCategory(),
                        catalog.labelOf(article.getCategory()), displayTitle(article),
                        article.getSourceName(), dateText, siteName);
                } catch (Exception ex) {
                    log.warn("[UniHalo][Digest] 第 {} 篇的标题卡片生成失败，跳过：{}",
                        index + 1, ex.getMessage());
                    return Mono.empty();
                }
                var baseName = String.format("cover-%s-%s-%02d", dateText,
                    DigestTextUtils.slugify(article.getCategory()), index + 1);
                return assetUploader.uploadBytes(bytes, "image/png", baseName, config)
                    .doOnNext(permalink -> {
                        article.setUploadedImage(permalink);
                        article.setPlaceholderCover(true);
                        log.info("[UniHalo][Digest] 标题卡片封面已上传：{} → {}",
                            article.getTitle(), permalink);
                    })
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][Digest] 第 {} 篇的标题卡片上传失败，跳过：{}",
                            index + 1, ex.getMessage());
                        return Mono.empty();
                    });
            }, IMAGE_UPLOAD_CONCURRENCY)
            .count()
            .map(Long::intValue)
            .doOnNext(count -> trace.placeholderCoverCount = count));
    }

    /**
     * 站点名（画在卡片右下角）；读不到就留空，不影响出图。
     *
     * <p>{@code defaultIfEmpty} 不能省：{@code flatMap} 链上任何一处返回空 Mono，
     * 整条流水线都会变成「没有结果」，报告会停在运行中而不是失败——比出不了图难查得多。</p>
     */
    private Mono<String> siteTitle() {
        return settingFetcher.get("basic")
            .map(node -> node.path("title").asText("").trim())
            .defaultIfEmpty("")
            .onErrorResume(ex -> Mono.just(""));
    }

    /** 头图上传后仍没图的文章，按配置处理（保证发出去的每篇都有封面） */
    private Mono<List<DigestArticleDTO>> enforceImageRequirement(List<DigestArticleDTO> articles,
        DigestConfigDTO config) {
        if (!config.isImageEnabled() || !config.isRequireImage()) {
            return Mono.just(articles);
        }
        // 「允许无封面」是站长明确选的：不做过滤，这一篇照发
        if (DigestConfigHelper.COVER_FALLBACK_NO_COVER.equals(config.getCoverFallbackMode())) {
            return Mono.just(articles);
        }
        var kept = DigestSelection.keepUploaded(articles);
        if (kept.isEmpty()) {
            return Mono.error(new IllegalStateException(
                "启用了「只发布带封面图的文章」，但本次 " + articles.size()
                    + " 篇的头图都没有拿到可用的封面地址，已按该设置跳过本轮发布。"
                    + "常见原因：附件存储策略名不存在或不可写、图片站点拒绝服务端抓取（防盗链/超时）。"
                    + "具体原因见插件日志里「Halo 转存头图失败」「头图下载被拒绝」「头图下载失败」"
                    + "「标题卡片生成失败」这几行；"
                    + "也可以先在「AI 日报」设置中关闭「只发布带封面图的文章」，让本轮先发出去"));
        }
        if (kept.size() < articles.size()) {
            log.warn("[UniHalo][Digest] {} 篇头图未上传成功（配图兜底={}），过滤后保留 {} 篇",
                articles.size() - kept.size(),
                DigestConfigHelper.coverFallbackLabel(config.getCoverFallbackMode()), kept.size());
        }
        return Mono.just(kept);
    }

    private Mono<Void> ensureHasArticles(DigestFeedResultDTO feeds) {
        if (feeds.getArticles().isEmpty()) {
            return Mono.error(new IllegalStateException("时间窗内没有抓到任何文章（扫描 "
                + feeds.getTotalFeeds() + " 个源），请检查服务器出网能力或调大时间范围"));
        }
        return Mono.empty();
    }

    // ── 1. 评分 ──

    /**
     * 本轮的分类目录（站长配置的分类清单；清单留空 = 内置六类）。
     *
     * <p>不缓存成字段：{@code DigestServiceImpl} 是单例，而清单是站长的配置、
     * 随时可能改；按 config 现算既便宜（最多十几行的小对象）又不会读到上一轮的旧清单。</p>
     */
    private static DigestCategoryCatalog catalogOf(DigestConfigDTO config) {
        return DigestCategoryCatalog.from(config.getDigestCategories());
    }

    /**
     * 评分失败 / 模型漏项时的默认分类。
     *
     * <p>按 {@code unknownCategoryPolicy} 的口径兜底：{@code other} → 清单里的兜底分类，
     * {@code drop} → 空串（不打分类）。</p>
     */
    private static String defaultCategory(DigestConfigDTO config) {
        return DigestAiHelper.normalizeCategory("", catalogOf(config),
            config.getUnknownCategoryPolicy());
    }

    private Mono<List<DigestArticleDTO>> scoreAll(List<DigestArticleDTO> articles,
        DigestConfigDTO config, RunTrace trace) {
        var batches = partition(articles, AI_BATCH_SIZE);
        var startedMs = System.currentTimeMillis();
        progressTracker.generating("Step 2/5 AI 评分中（" + articles.size() + " 篇，"
            + batches.size() + " 批）…");
        log.info("[UniHalo][Digest] Step 2/5 AI 评分：{} 篇 → {} 批（每批最多 {} 篇，并发 {}）",
            articles.size(), batches.size(), AI_BATCH_SIZE, MAX_CONCURRENT_AI);
        return Flux.fromIterable(batches)
            .flatMapSequential(batch -> scoreBatch(articles, batch, config, trace),
                MAX_CONCURRENT_AI)
            .then(Mono.fromSupplier(() -> articles))
            .doOnNext(list -> logDistribution(list, config))
            .doOnNext(list -> log.info("[UniHalo][Digest] Step 2/5 AI 评分完成，用时 {}s",
                secondsSince(startedMs)));
    }

    private Mono<Void> scoreBatch(List<DigestArticleDTO> all, Batch batch, DigestConfigDTO config,
        RunTrace trace) {
        // 先铺默认分：模型漏项、批次失败都不会让文章停在「零分」状态
        var fallbackCategory = defaultCategory(config);
        for (var article : batch.items()) {
            article.setRelevance(5);
            article.setQuality(5);
            article.setTimeliness(5);
            article.setCategory(fallbackCategory);
            article.setKeywords(new ArrayList<>());
        }
        return callScoring(all, batch, config, trace, 0);
    }

    /**
     * 评分请求（失败重试一次）。
     *
     * <p>评分批次失败时改造前只打一行日志就按默认分继续，结果是整批文章挤进兜底分类——
     * 分类覆盖会莫名其妙地塌掉。重试一次的成本很低，收益是「分类判定不因一次网络抖动而失真」。</p>
     */
    private Mono<Void> callScoring(List<DigestArticleDTO> all, Batch batch,
        DigestConfigDTO config, RunTrace trace, int attempt) {
        var prompt = DigestAiHelper.buildScoringPrompt(batch.items(), batch.startIndex(),
            catalogOf(config));
        return aiClient.call(config, prompt, DigestAiTask.SCORE)
            .doOnNext(reply -> {
                trace.usage.record(DigestAiTask.SCORE, reply);
                applyScores(all, reply.text(), config);
            })
            .then()
            .onErrorResume(ex -> {
                if (attempt == 0) {
                    log.warn("[UniHalo][Digest] 评分批次失败（{} 篇），重试一次：{}",
                        batch.items().size(), ex.getMessage());
                    return callScoring(all, batch, config, trace, attempt + 1);
                }
                log.warn("[UniHalo][Digest] 评分批次重试仍失败（{} 篇），按默认分处理：{}",
                    batch.items().size(), ex.getMessage());
                return Mono.empty();
            });
    }

    /** 分类分布：分类塌到兜底一类时一眼能看出来（否则只会看到「今天文章很少」） */
    private void logDistribution(List<DigestArticleDTO> articles, DigestConfigDTO config) {
        var counts = countByCategory(articles);
        log.info("[UniHalo][Digest] 评分后的分类分布：{}",
            describeCounts(counts, catalogOf(config)));
    }

    private void applyScores(List<DigestArticleDTO> all, String response, DigestConfigDTO config) {
        JsonNode root;
        try {
            root = DigestAiHelper.parseJson(response);
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 评分结果解析失败：{}", ex.getMessage());
            return;
        }
        var catalog = catalogOf(config);
        var policy = config.getUnknownCategoryPolicy();
        for (var result : root.path("results")) {
            var index = result.path("index").asInt(-1);
            if (index < 0 || index >= all.size()) {
                continue;
            }
            var article = all.get(index);
            article.setRelevance(DigestAiHelper.clampScore(result.path("relevance").asInt(5)));
            article.setQuality(DigestAiHelper.clampScore(result.path("quality").asInt(5)));
            article.setTimeliness(DigestAiHelper.clampScore(result.path("timeliness").asInt(5)));
            // 清单外的分类按 unknownCategoryPolicy 兜底（other → 兜底分类，drop → 不打分类）
            article.setCategory(DigestAiHelper.normalizeCategory(
                result.path("category").asText(""), catalog, policy));
            var keywords = new ArrayList<String>();
            for (var keyword : result.path("keywords")) {
                if (keywords.size() >= 4) {
                    break;
                }
                var text = keyword.asText("");
                if (StringUtils.hasText(text)) {
                    keywords.add(text.trim());
                }
            }
            article.setKeywords(keywords);
        }
    }

    // ── 2. 摘要 ──

    private Mono<List<DigestArticleDTO>> summarizeBatchwise(List<DigestArticleDTO> articles,
        DigestConfigDTO config, RunTrace trace) {
        // 每批篇数按篇幅反比收缩，避免长文批次把单次响应顶到输出上限
        var batchSize = Math.max(3, Math.min(AI_BATCH_SIZE,
            AI_OUTPUT_CHARS_BUDGET / Math.max(config.getWords(), MIN_WORDS_PER_ARTICLE)));
        var batches = partition(articles, batchSize);
        var startedMs = System.currentTimeMillis();
        progressTracker.generating("Step 3/5 AI 撰写中（精选 " + articles.size() + " 篇，"
            + batches.size() + " 批）…");
        log.info("[UniHalo][Digest] Step 3/5 AI 撰写：精选 {} 篇 → {} 批（每批 {} 篇，目标每篇 {} 字）",
            articles.size(), batches.size(), batchSize, config.getWords());
        return Flux.fromIterable(batches)
            .flatMapSequential(batch -> summarizeOneBatch(articles, batch, config, trace, 0),
                MAX_CONCURRENT_AI)
            .then(Mono.defer(() -> fillMissingSummaries(articles, config, trace)))
            .map(list -> {
                for (var article : list) {
                    if (!StringUtils.hasText(article.getSummary())) {
                        // 兜底用 RSS 描述：宁可短，也不要在报告里留空白
                        article.setSummary(truncate(article.getDescription(),
                            DESCRIPTION_FALLBACK_LIMIT));
                        if (!StringUtils.hasText(article.getTitleZh())) {
                            article.setTitleZh(article.getTitle());
                        }
                    }
                }
                return list;
            })
            .doOnNext(list -> log.info(
                "[UniHalo][Digest] Step 3/5 AI 撰写完成：实测平均 {} 字/篇，用时 {}s",
                actualAvgWords(list), secondsSince(startedMs)));
    }

    /** 批次失败时拆半重试；仍失败则留给「缺失补齐」阶段单独请求 */
    private Mono<Void> summarizeOneBatch(List<DigestArticleDTO> articles, Batch batch,
        DigestConfigDTO config, RunTrace trace, int depth) {
        var prompt = DigestAiHelper.buildSummaryPrompt(batch.items(), batch.startIndex(),
            config.getLanguage(), config.getWords());
        return aiClient.call(config, prompt, DigestAiTask.WRITE)
            .doOnNext(reply -> {
                trace.usage.record(DigestAiTask.WRITE, reply);
                var applied = applySummaries(batch, reply.text());
                if (applied == 0) {
                    throw new IllegalStateException("摘要结果为空");
                }
            })
            .then()
            .onErrorResume(ex -> {
                if (depth < AI_BATCH_SPLIT_DEPTH && batch.items().size() > 1) {
                    var mid = (batch.items().size() + 1) / 2;
                    log.warn("[UniHalo][Digest] 摘要批次（{} 篇）失败，拆半重试：{}",
                        batch.items().size(), ex.getMessage());
                    return summarizeOneBatch(articles,
                        new Batch(batch.startIndex(), batch.items().subList(0, mid)),
                        config, trace, depth + 1)
                        .then(summarizeOneBatch(articles,
                            new Batch(batch.startIndex() + mid,
                                batch.items().subList(mid, batch.items().size())),
                            config, trace, depth + 1));
                }
                log.warn("[UniHalo][Digest] 摘要批次（{} 篇）失败，稍后单篇补请求：{}",
                    batch.items().size(), ex.getMessage());
                return Mono.empty();
            });
    }

    private int applySummaries(Batch batch, String response) {
        JsonNode root;
        try {
            root = DigestAiHelper.parseJson(response);
        } catch (Exception ex) {
            throw new IllegalStateException("摘要 JSON 解析失败：" + ex.getMessage(), ex);
        }
        var results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("摘要返回 results 为空");
        }
        var applied = 0;
        for (var result : results) {
            var index = result.path("index").asInt(-1) - batch.startIndex();
            if (index < 0 || index >= batch.items().size()) {
                continue;
            }
            var article = batch.items().get(index);
            article.setTitleZh(result.path("titleZh").asText(""));
            article.setSummary(result.path("summary").asText(""));
            article.setReason(result.path("reason").asText(""));
            var points = new ArrayList<String>();
            for (var point : result.path("points")) {
                if (points.size() >= 4) {
                    break;
                }
                var text = point.asText("");
                if (StringUtils.hasText(text)) {
                    points.add(text.trim());
                }
            }
            article.setPoints(points);
            if (StringUtils.hasText(article.getSummary())) {
                applied++;
            }
        }
        return applied;
    }

    private Mono<List<DigestArticleDTO>> fillMissingSummaries(List<DigestArticleDTO> articles,
        DigestConfigDTO config, RunTrace trace) {
        var missing = articles.stream()
            .filter(article -> !StringUtils.hasText(article.getSummary()))
            .toList();
        if (missing.isEmpty()) {
            return Mono.just(articles);
        }
        log.warn("[UniHalo][Digest] {} 篇未拿到摘要，逐篇补请求", missing.size());
        return Flux.fromIterable(missing)
            .flatMapSequential(item -> summarizeOneBatch(articles, new Batch(0, List.of(item)),
                config, trace, AI_BATCH_SPLIT_DEPTH), MAX_CONCURRENT_AI)
            .then(Mono.fromSupplier(() -> articles));
    }

    // ── 3. 趋势总结 ──

    private Mono<DigestAiHelper.Overview> generateOverview(List<DigestArticleDTO> articles,
        DigestConfigDTO config, RunTrace trace) {
        var prompt = DigestAiHelper.buildOverviewPrompt(articles, config.getLanguage(),
            config.getWords());
        var startedMs = System.currentTimeMillis();
        progressTracker.generating("Step 4/5 生成今日看点与背景铺垫…");
        log.info("[UniHalo][Digest] Step 4/5 生成今日看点与背景铺垫…");
        return aiClient.call(config, prompt, DigestAiTask.OVERVIEW)
            .doOnNext(reply -> trace.usage.record(DigestAiTask.OVERVIEW, reply))
            .map(reply -> DigestAiHelper.parseOverviewResponse(reply.text()))
            .doOnNext(overview -> log.info(
                "[UniHalo][Digest] Step 4/5 趋势总结完成：看点 {} 字 / 背景 {} 字，用时 {}s",
                length(overview.trend()), length(overview.background()), secondsSince(startedMs)))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 趋势总结生成失败，跳过该板块：{}", ex.getMessage());
                return Mono.just(new DigestAiHelper.Overview("", ""));
            });
    }

    // ── 4. 配图 ──

    private Mono<Integer> uploadImages(List<DigestArticleDTO> articles, DigestConfigDTO config) {
        if (!config.isImageEnabled()) {
            log.info("[UniHalo][Digest] Step 5/5 配图已关闭，跳过");
            return Mono.just(0);
        }
        var startedMs = System.currentTimeMillis();
        progressTracker.generating("Step 5/5 抓取并上传头图…");
        return feedService.resolveLeadImages(articles)
            .flatMap(resolved -> Flux.fromIterable(resolved)
                .index()
                .flatMapSequential(tuple -> {
                    var index = tuple.getT1().intValue();
                    var article = tuple.getT2();
                    if (!DigestTextUtils.isUsableImageUrl(article.getImageUrl())) {
                        return Mono.empty();
                    }
                    var baseName = String.format("%02d-%s", index + 1,
                        DigestTextUtils.slugify(article.getSourceName()));
                    return assetUploader.uploadImage(article.getImageUrl(), baseName, config)
                        .doOnNext(permalink -> {
                            article.setUploadedImage(permalink);
                            log.info("[UniHalo][Digest] 头图已上传附件库：{} → {}",
                                article.getSourceName(), permalink);
                        })
                        .onErrorResume(ex -> {
                            log.warn("[UniHalo][Digest] 第 {} 篇头图上传失败，跳过：{}",
                                index + 1, ex.getMessage());
                            return Mono.empty();
                        });
                }, IMAGE_UPLOAD_CONCURRENCY)
                .count()
                .map(Long::intValue)
                .doOnNext(count -> log.info(
                    "[UniHalo][Digest] Step 5/5 配图完成：成功上传 {}/{} 张，用时 {}s",
                    count, articles.size(), secondsSince(startedMs))));
    }

    // ── 5. 成稿 ──

    private PipelineOutput buildReport(List<DigestArticleDTO> articles,
        DigestAiHelper.Overview overview, DigestFeedResultDTO feeds,
        DigestSelection.CategorySelection selection, RunTrace trace, int imageCount,
        int placeholderCount, DigestConfigDTO config, ZonedDateTime generatedAt, long startedMs) {
        var catalog = catalogOf(config);
        var counts = countByCategory(articles);
        var stats = DigestStatsDTO.builder()
            .totalFeeds(feeds.getTotalFeeds())
            .successFeeds(feeds.getSuccessFeeds())
            .totalArticles(feeds.getArticles().size())
            .filteredArticles(Math.max(0, feeds.getArticles().size() - trace.deduped))
            .selected(articles.size())
            .hours(config.getHours())
            .words(config.getWords())
            .actualAvgWords(actualAvgWords(articles))
            .imageCount(imageCount)
            .placeholderCoverCount(placeholderCount)
            .dedupedCount(trace.deduped)
            .fallbackFetched(trace.fallbackFetched)
            .supplementCount(countSupplementary(articles))
            .minPerCategory(config.getMinPerCategory())
            .categoryCounts(counts)
            .categorySummary(describeCounts(counts, catalog))
            .requiredCategories(selection.requiredCategories())
            .missingCategories(shortfall(selection.requiredCategories(), counts,
                config.getMinPerCategory()))
            .language(config.getLanguage())
            .durationMs(System.currentTimeMillis() - startedMs)
            .aiCost(trace.usage.snapshot())
            .build();
        var markdown = renderer.render(articles, overview, stats, config.isMermaidEnabled(),
            config.getFooterNote(), generatedAt, catalog);
        var cover = articles.stream()
            .map(DigestArticleDTO::getUploadedImage)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("");
        log.info("[UniHalo][Digest] Markdown 成稿完成：{} 字符，精选 {} 篇，配图 {} 张"
                + "（其中标题卡片 {} 张），扫描源 {}/{}，抓取 {} 篇",
            markdown.length(), articles.size(), imageCount, placeholderCount,
            stats.getSuccessFeeds(), stats.getTotalFeeds(), stats.getTotalArticles());
        logCoverage(stats, catalog);
        progressTracker.generating("正在生成 Markdown 成稿…");
        var payloads = buildPostPayloads(articles, markdown, config, stats, generatedAt, catalog);
        log.info("[UniHalo][Digest] 待发布内容已备好：{} 篇（发布方式={}）",
            payloads.size(), config.getPublishMode());
        return new PipelineOutput(markdown, stats, articles.size(), cover,
            generatedAt.format(DATE_FORMAT), generatedAt, articles, payloads);
    }

    /** 分类覆盖度：本期每个分类各几篇、哪些没凑够（后台面板与日志都看这一份） */
    private static void logCoverage(DigestStatsDTO stats, DigestCategoryCatalog catalog) {
        if (stats.getMinPerCategory() <= 0) {
            return;
        }
        var covered = stats.getRequiredCategories().size() - stats.getMissingCategories().size();
        log.info("[UniHalo][Digest] 分类覆盖 {}/{}：{}", covered,
            stats.getRequiredCategories().size(), describeCounts(stats.getCategoryCounts(), catalog));
        if (!stats.getMissingCategories().isEmpty()) {
            log.warn("[UniHalo][Digest] 以下分类本期不足 {} 篇：{}", stats.getMinPerCategory(),
                labels(stats.getMissingCategories(), catalog));
        }
    }

    /** 「AI / ML 3 · 安全 1」这样的中文可读分布，日志与后台都用同一口径 */
    private static String describeCounts(java.util.Map<String, Integer> counts,
        DigestCategoryCatalog catalog) {
        if (counts == null || counts.isEmpty()) {
            return "（无）";
        }
        return counts.entrySet().stream()
            .map(entry -> catalog.labelOf(entry.getKey()) + " " + entry.getValue())
            .collect(Collectors.joining(" · "));
    }

    /** 分类 key → 篇数（保持清单顺序，旧分类排在后面） */
    private static java.util.Map<String, Integer> countByCategory(List<DigestArticleDTO> articles) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var article : articles) {
            var key = article.getCategory() == null ? "" : article.getCategory().trim();
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /** 清单里没凑够保底篇数的分类 */
    private static List<String> shortfall(List<String> required,
        java.util.Map<String, Integer> counts, int minPerCategory) {
        if (required == null || required.isEmpty() || minPerCategory <= 0) {
            return List.of();
        }
        return required.stream()
            .filter(key -> counts.getOrDefault(key, 0) < minPerCategory)
            .toList();
    }

    private static int countSupplementary(List<DigestArticleDTO> articles) {
        return (int) articles.stream().filter(DigestArticleDTO::isSupplementary).count();
    }

    /** 分类 key 列表 → 中文名（日志里给人看） */
    private static String labels(List<String> keys, DigestCategoryCatalog catalog) {
        return keys.stream().map(catalog::labelOf).collect(Collectors.joining("、"));
    }

    /**
     * 生成阶段就把要发布的文章内容定下来。
     *
     * <p>这样「只生成不发布」之后再点发布、或只补发失败篇目时，直接拿这份内容发布即可，
     * 不需要重跑抓取与 AI，也保证发出去的和当时生成的一致。</p>
     */
    /**
     * 复用同日同时段的记录做重试：重置状态并累加尝试次数。
     *
     * <p>不新建记录是有意的——资源名按「日期 + 时段」固定，新建必然撞名，
     * 而调度器正是靠这条记录上的尝试次数来决定「还要不要再试」。</p>
     */
    private Mono<DigestReport> prepareRetry(DigestReport existing, String date, String slot) {
        var attempts = existing.getAttempts() == null ? 1 : existing.getAttempts();
        log.info("[UniHalo][Digest] 复用 {}/{} 的日报记录进行第 {} 次尝试（上次状态={}）",
            date, slot, attempts + 1, existing.getStatus());
        existing.setAttempts(attempts + 1);
        existing.setStatus("RUNNING");
        existing.setPublishState("PENDING");
        existing.setStartedAt(Instant.now());
        existing.setFinishedAt(null);
        existing.setError(null);
        return persistence.update(existing);
    }

    /** 一轮结束后的进度收尾：成功与失败都要清掉「进行中」，否则页面会一直显示在跑 */
    private void finishProgress(DigestReport report) {
        if ("SUCCESS".equals(report.getStatus())) {
            progressTracker.completed("生成完成");
        } else {
            progressTracker.failed(StringUtils.hasText(report.getError())
                ? report.getError() : "生成失败，详情见插件日志");
        }
    }

    private static String describeFailure(Throwable ex) {
        if (ex == null) {
            return "生成失败";
        }
        return StringUtils.hasText(ex.getMessage())
            ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    /**
     * 把文章列表序列化成用户侧的条目 JSON。
     *
     * <p>分类名与图标取自同一份分类目录（站长配置的清单），避免前端各维护一份映射。</p>
     */
    private static String serializeArticleEntries(List<DigestArticleDTO> articles,
        DigestCategoryCatalog catalog) {
        if (articles == null || articles.isEmpty()) {
            return "[]";
        }
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var entries = new ArrayList<DigestArticleEntryDTO>();
        for (var article : articles) {
            var category = article.getCategory() == null ? "" : article.getCategory();
            entries.add(DigestArticleEntryDTO.builder()
                .title(DigestRenderer.displayTitle(article))
                .cover(article.getUploadedImage() == null ? "" : article.getUploadedImage())
                .category(category)
                .categoryLabel(directory.labelOf(category))
                .categoryEmoji(directory.emojiOf(category))
                .keywords(article.getKeywords() == null ? List.of() : article.getKeywords())
                .sourceName(article.getSourceName())
                .sourceUrl(article.getSourceUrl())
                .summary(article.getSummary())
                .link(article.getLink())
                .build());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(entries);
        } catch (Exception ex) {
            // 序列化失败不该让整个日报生成失败：正文与发布都更重要
            log.warn("[UniHalo][Digest] 文章条目序列化失败，小程序端将看不到本次的条目明细：{}",
                ex.getMessage());
            return "[]";
        }
    }

    private List<DigestPostPayloadDTO> buildPostPayloads(List<DigestArticleDTO> articles,
        String digestMarkdown, DigestConfigDTO config, DigestStatsDTO stats,
        ZonedDateTime generatedAt, DigestCategoryCatalog catalog) {
        var payloads = new ArrayList<DigestPostPayloadDTO>();
        var mode = config.getPublishMode();
        var wantDigest = DigestConfigHelper.PUBLISH_MODE_DIGEST.equals(mode)
            || DigestConfigHelper.PUBLISH_MODE_BOTH.equals(mode);

        // 标签的频次门槛按「整轮」统计，再看每篇命中哪些。
        // 逐篇统计是行不通的：每篇只有自己那 2-4 个关键词、次数都是 1，门槛永远达不到。
        var allKeywords = new ArrayList<String>();
        for (var article : articles) {
            if (article.getKeywords() != null) {
                allKeywords.addAll(article.getKeywords());
            }
        }
        var qualified = config.isAutoTagEnabled()
            ? DigestTaxonomyService.qualifyingKeywords(allKeywords, config.getAutoTagMinCount())
            : java.util.Set.<String>of();
        var autoCategory = config.isAutoCategoryEnabled();

        if (wantDigest) {
            var title = buildTitle(config, generatedAt.format(DATE_FORMAT));
            payloads.add(DigestPostPayloadDTO.builder()
                .title(title)
                .slug(buildSlug(title))
                .markdown(digestMarkdown)
                .cover(articles.stream().map(DigestArticleDTO::getUploadedImage)
                    .filter(StringUtils::hasText).findFirst().orElse(""))
                // 合并成一篇时，它涵盖的分类就是本期所有文章的分类集合
                .aiCategories(autoCategory ? distinctCategories(articles) : List.of())
                .keywords(filterQualified(allKeywords, qualified))
                .build());
        }
        if (!DigestConfigHelper.PUBLISH_MODE_DIGEST.equals(mode)) {
            for (var article : articles) {
                payloads.add(DigestPostPayloadDTO.builder()
                    .title(displayTitle(article))
                    .slug(buildArticleSlug(article))
                    .markdown(renderer.renderArticle(article, stats, config.getFooterNote(),
                        generatedAt, catalogOf(config)))
                    .cover(article.getUploadedImage() == null ? "" : article.getUploadedImage())
                    // 逐篇时每篇带自己的分类，这样它才会出现在对应的站点分类下
                    .aiCategories(autoCategory && StringUtils.hasText(article.getCategory())
                        ? List.of(article.getCategory()) : List.of())
                    .keywords(filterQualified(article.getKeywords(), qualified))
                    .build());
            }
        }
        return payloads;
    }

    /** 本期出现过的分类（去重、去空） */
    private static List<String> distinctCategories(List<DigestArticleDTO> articles) {
        var categories = new java.util.LinkedHashSet<String>();
        for (var article : articles) {
            if (StringUtils.hasText(article.getCategory())) {
                categories.add(article.getCategory().trim());
            }
        }
        return List.copyOf(categories);
    }

    /** 只保留达到门槛的关键词（去重，保留原始写法） */
    private static List<String> filterQualified(List<String> keywords,
        java.util.Set<String> qualified) {
        if (keywords == null || keywords.isEmpty() || qualified.isEmpty()) {
            return List.of();
        }
        var kept = new java.util.LinkedHashSet<String>();
        for (var keyword : keywords) {
            if (StringUtils.hasText(keyword)
                && qualified.contains(keyword.trim().toLowerCase(java.util.Locale.ROOT))) {
                kept.add(keyword.trim());
            }
        }
        return List.copyOf(kept);
    }

    private static int actualAvgWords(List<DigestArticleDTO> articles) {
        if (articles.isEmpty()) {
            return 0;
        }
        var total = 0;
        for (var article : articles) {
            total += countChars(article.getSummary());
            if (article.getPoints() != null) {
                for (var point : article.getPoints()) {
                    total += countChars(point);
                }
            }
        }
        return Math.round((float) total / articles.size());
    }

    private static int countChars(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** 先把正文与待发布内容落库：即使随后发布失败或卡住，报告本身已经可以查看与补发 */
    private Mono<PipelineOutput> persistDraft(DigestReport report, PipelineOutput output,
        DigestConfigDTO config) {
        report.setMarkdown(output.markdown());
        report.setStats(serializeStats(output.stats()));
        report.setArticleCount(output.articleCount());
        report.setCover(output.cover());
        report.setPostPayloads(serializePayloads(output.payloads()));
        // 一并保存「用户侧」的文章条目：AI 分类、关键词、摘要、封面这些是
        // 小程序端让用户自己挑内容看的依据，而发布结果与待发布载荷里都没有
        report.setArticleEntries(serializeArticleEntries(output.articles(), catalogOf(config)));
        log.info("[UniHalo][Digest] 日报正文与待发布内容已保存到插件数据（name={}，{}/{} 篇已备好），"
                + "准备发布",
            report.getMetadata().getName(), output.posts().size(), output.payloads().size());
        return persistence.update(report).thenReturn(output);
    }

    // ── 6. 发布 ──

    /**
     * 是否发布由调用方决定：定时任务传的是配置里的「自动发布」开关，
     * 后台手动触发传的是站长这次点选的按钮，两者不互相覆盖。
     *
     * <p>发布内容在生成阶段就已经备好（payloads），这里只负责把它们写进 Halo。
     * 所以「只生成不发布」之后再点发布、或只补发失败篇目，都不需要重跑流水线。</p>
     */
    private Mono<PipelineOutput> publishIfEnabled(PipelineOutput output, DigestConfigDTO config,
        boolean publish) {
        if (!publish) {
            log.info("[UniHalo][Digest] 本次只生成不发布：{} 篇内容已备好，可在后台点「重新发布」",
                output.payloads().size());
            return Mono.just(output.skipped());
        }
        if (output.payloads().isEmpty()) {
            log.warn("[UniHalo][Digest] 没有待发布内容，跳过发布");
            return Mono.just(output.skipped());
        }
        log.info("[UniHalo][Digest] 开始写入 Halo：共 {} 篇，发布方式={} 作者={} 分类={} 标签={}",
            output.payloads().size(), config.getPublishMode(), config.getPostOwner(),
            splitNames(config.getPostCategories()), splitNames(config.getPostTags()));
        var total = output.payloads().size();
        return Flux.range(0, total)
            .concatMap(index -> {
                var payload = output.payloads().get(index);
                progressTracker.publishing(total, index, payload.getTitle());
                return publishOrRepublish(payload, null, config)
                    .onErrorResume(ex -> {
                        log.error("[UniHalo][Digest] 文章「{}」发布失败：{}", payload.getTitle(),
                            ex.getMessage());
                        return Mono.just(failedPost(payload.getTitle(), ex));
                    });
            })
            .collectList()
            .map(output::appendPosts)
            .doOnNext(withResults -> progressTracker.publishingFinished(total,
                (int) countPublished(withResults.posts())));
    }

    /**
     * 发一篇：Halo 里已经有这篇文章（有 postName）时只重新触发发布，否则先建稿再发布。
     *
     * <p>这两条路都走官方接口；已有文章时不重新提交正文，避免把正文改回旧版本。</p>
     */
    private Mono<DigestPostResultDTO> publishOrRepublish(DigestPostPayloadDTO payload,
        String existingPostName, DigestConfigDTO config) {
        if (StringUtils.hasText(existingPostName)) {
            log.info("[UniHalo][Digest] 重新触发发布：标题=「{}」 name={}", payload.getTitle(),
                existingPostName);
            return publisher.republish(existingPostName, config.getPublishWaitSeconds())
                .map(result -> toPostResult(payload.getTitle(), result));
        }
        var dto = HaloPostPublishDTO.builder()
            .title(payload.getTitle())
            .slug(payload.getSlug())
            .markdown(payload.getMarkdown())
            .cover(payload.getCover())
            .owner(config.getPostOwner())
            // 手填的额外附加项：匹配不到就忽略（手误不该造出分类）
            .categoryNames(splitNames(config.getPostCategories()))
            .tagNames(splitNames(config.getPostTags()))
            // AI 判定的分类与关键词：默认不存在则自动创建（这才是「不需要手工配置」的部分），
            // 也可以按站长选的模式改成「只复用已有」或「按清单映射」
            .aiCategories(payload.getAiCategories())
            .catalog(catalogOf(config))
            .categoryMode(config.getCategoryMode())
            .keywords(payload.getKeywords())
            .build();
        log.info("[UniHalo][Digest] 发布文章：标题=「{}」 slug={} 正文 {} 字符 封面={}",
            dto.getTitle(), dto.getSlug(), length(dto.getMarkdown()),
            StringUtils.hasText(dto.getCover()) ? dto.getCover() : "（无）");
        // 单篇正文只有几 KB，发布确认不需要等日报那么久；给个上限，避免多篇串行时总体拖太久
        var waitSeconds = Math.min(config.getPublishWaitSeconds(), ARTICLE_PUBLISH_WAIT_SECONDS);
        return publisher.publish(dto, waitSeconds)
            .map(result -> toPostResult(payload.getTitle(), result));
    }

    private static DigestPostResultDTO toPostResult(String title, HaloPostPublishResultDTO result) {
        return DigestPostResultDTO.builder()
            .title(title)
            .postName(result.getPostName())
            .url(result.getPermalink())
            .state(result.getState())
            .message(result.getMessage())
            .build();
    }

    private static DigestPostResultDTO failedPost(String title, Throwable ex) {
        return DigestPostResultDTO.builder()
            .title(title)
            .state("FAILED")
            .message(StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
                : ex.getClass().getSimpleName())
            .build();
    }

    private static String buildTitle(DigestConfigDTO config, String reportDate) {
        var template = config.getPostTitleTemplate();
        if (!StringUtils.hasText(template)) {
            return "AI 日报";
        }
        var date = StringUtils.hasText(reportDate)
            ? reportDate : ZonedDateTime.now().format(DATE_FORMAT);
        return template.replace("{date}", date);
    }

    private static String buildSlug(String title) {
        return DigestTextUtils.slugify(title);
    }

    /**
     * 逐篇发布时的 slug。
     *
     * <p>中文标题会被 slugify 抹掉，同一博客又可能有多篇入选，所以补上原文链接的哈希，
     * 保证同一天多篇文章的 slug 不冲突。</p>
     */
    private static String buildArticleSlug(DigestArticleDTO article) {
        var base = DigestTextUtils.slugify(article.getSourceName() + "-" + article.getTitle());
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        var hash = Integer.toHexString(article.getLink() == null ? 0 : article.getLink().hashCode());
        return base + "-" + hash;
    }

    private static List<String> splitNames(String value) {
        return DigestConfigHelper.splitNames(value);
    }

    // ── 收尾 ──

    private Mono<DigestReport> markSuccess(DigestReport report, PipelineOutput output,
        DigestConfigDTO config) {
        // 重新取一次再写：persistDraft 已经更新过一轮，同一实例的 resourceVersion 已过期，
        // 直接用旧实例提交会命中乐观锁冲突
        return reload(report).flatMap(current -> {
            current.setStatus("SUCCESS");
            current.setMarkdown(output.markdown());
            current.setStats(serializeStats(output.stats()));
            current.setArticleCount(output.articleCount());
            current.setCover(output.cover());
            current.setPublishState(output.publishState());
            current.setPosts(serializePosts(output.posts()));
            current.setArticleEntries(serializeArticleEntries(output.articles(), catalogOf(config)));
            current.setPostCount(output.posts().size());
            var first = output.posts().stream()
                .filter(post -> StringUtils.hasText(post.getUrl()))
                .findFirst()
                .orElse(null);
            if (first != null) {
                current.setPostName(first.getPostName());
                current.setPostUrl(first.getUrl());
            }
            current.setError(output.publishMessage());
            current.setFinishedAt(Instant.now());
            log.info("[UniHalo][Digest] ===== 日报生成完成 ===== date={} 精选 {} 篇 发布 {} 篇 "
                    + "发布状态={} 总耗时 {}s{}",
                current.getDate(), output.articleCount(), countPublished(output.posts()),
                output.publishState(), output.stats().getDurationMs() / 1000,
                StringUtils.hasText(output.publishMessage())
                    ? " 未发布原因：" + output.publishMessage() : "");
            return persistence.update(current);
        });
    }

    private static long countPublished(List<DigestPostResultDTO> posts) {
        return posts == null ? 0
            : posts.stream().filter(post -> "PUBLISHED".equals(post.getState())).count();
    }

    private Mono<DigestReport> markFailed(DigestReport report, Throwable ex) {
        return reload(report).flatMap(current -> {
            current.setStatus("FAILED");
            current.setError(StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
                : ex.getClass().getSimpleName());
            current.setPublishState("FAILED");
            current.setFinishedAt(Instant.now());
            log.error("[UniHalo][Digest] ===== 日报生成失败 ===== date={} 原因：{}",
                current.getDate(), current.getError(), ex);
            return persistence.update(current);
        });
    }

    /** 按资源名重新读取，拿到最新的 resourceVersion 后再更新 */
    private Mono<DigestReport> reload(DigestReport report) {
        if (report.getMetadata() == null || !StringUtils.hasText(report.getMetadata().getName())) {
            return Mono.just(report);
        }
        return persistence.fetch(report.getMetadata().getName()).defaultIfEmpty(report);
    }

    // ── 工具 ──

    private Mono<DigestConfigDTO> loadConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }

    private String serializeStats(DigestStatsDTO stats) {
        try {
            return OBJECT_MAPPER.writeValueAsString(stats);
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 统计信息序列化失败：{}", ex.getMessage());
            return "{}";
        }
    }

    private String serializePosts(List<DigestPostResultDTO> posts) {
        if (posts == null || posts.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(posts);
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 发布结果序列化失败：{}", ex.getMessage());
            return "[]";
        }
    }

    private String serializePayloads(List<DigestPostPayloadDTO> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(payloads);
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 发布内容序列化失败：{}", ex.getMessage());
            return "[]";
        }
    }

    private static List<DigestPostPayloadDTO> parsePayloads(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class,
                    DigestPostPayloadDTO.class));
        } catch (Exception ex) {
            // 不能静默吞掉：读不出来会让「发布」什么都不做，看起来就像点了没反应
            log.error("[UniHalo][Digest] 待发布内容反序列化失败，将按旧记录处理：{}", ex.getMessage());
            return List.of();
        }
    }

    private static List<DigestPostResultDTO> parsePosts(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class,
                    DigestPostResultDTO.class));
        } catch (Exception ex) {
            log.error("[UniHalo][Digest] 发布结果反序列化失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private static String displayTitle(DigestArticleDTO article) {
        return DigestRenderer.displayTitle(article);
    }

    private static List<Batch> partition(List<DigestArticleDTO> articles, int size) {
        var batches = new ArrayList<Batch>();
        for (var i = 0; i < articles.size(); i += size) {
            batches.add(new Batch(i, new ArrayList<>(
                articles.subList(i, Math.min(i + size, articles.size())))));
        }
        return batches;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 已耗时（秒），用于日志 */
    private static long secondsSince(long startedMs) {
        return Math.max(0, (System.currentTimeMillis() - startedMs) / 1000);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    /** 一批文章 + 这批在全局列表中的起始下标（提示词里的 Index 用全局下标，便于回填） */
    private record Batch(int startIndex, List<DigestArticleDTO> items) {
    }

    /**
     * 本轮运行的中间量：去重、补抓、占位封面这些不是每轮都会发生，
     * 但统计与日志都要它们，所以集中放一处，避免到处传参。
     */
    private static final class RunTrace {

        /**
         * 本轮的 AI 用量账本：收尾时按任务/通道算成钱写进报告。
         *
         * <p>放在 RunTrace 里而不是客户端单例上：客户端是共享的（付费简报、旧文归类也会调用它），
         * 计数挂在单例上会把别人的开销算进这一轮，甚至清掉已累计的。</p>
         */
        private final DigestAiUsageLog usage;

        RunTrace(DigestConfigDTO config) {
            this.usage = DigestAiUsageLog.forConfig(config);
        }

        /** 因重复转载被去掉的篇数 */
        private int deduped;

        /** 补抓窗口里额外参与评分的篇数 */
        private int fallbackFetched;

        /** 成功生成的标题卡片封面数 */
        private int placeholderCoverCount;
    }

    /** 流水线产物 */
    private record PipelineOutput(String markdown, DigestStatsDTO stats, int articleCount,
                                  String cover, String reportDate, ZonedDateTime generatedAt,
                                  List<DigestArticleDTO> articles,
                                  List<DigestPostPayloadDTO> payloads, String publishState,
                                  List<DigestPostResultDTO> posts, String publishMessage) {

        PipelineOutput(String markdown, DigestStatsDTO stats, int articleCount, String cover,
            String reportDate, ZonedDateTime generatedAt, List<DigestArticleDTO> articles,
            List<DigestPostPayloadDTO> payloads) {
            this(markdown, stats, articleCount, cover, reportDate, generatedAt, articles, payloads,
                "SKIPPED", List.of(), null);
        }

        /** 只生成不发布 */
        PipelineOutput skipped() {
            return new PipelineOutput(markdown, stats, articleCount, cover, reportDate,
                generatedAt, articles, payloads, "SKIPPED", List.of(), null);
        }

        PipelineOutput appendPost(DigestPostResultDTO post) {
            return appendPosts(List.of(post));
        }

        /** 追加发布结果并重算整体发布状态 */
        PipelineOutput appendPosts(List<DigestPostResultDTO> more) {
            var merged = new ArrayList<>(posts);
            merged.addAll(more);
            return new PipelineOutput(markdown, stats, articleCount, cover, reportDate,
                generatedAt, articles, payloads, resolveState(merged), merged,
                failureSummary(merged));
        }

        /** PUBLISHED（全成功）/ PARTIAL（部分成功）/ PENDING（都还没确认）/ FAILED（全失败） */
        static String resolveState(List<DigestPostResultDTO> posts) {
            if (posts == null || posts.isEmpty()) {
                return "SKIPPED";
            }
            var published = posts.stream().filter(post -> "PUBLISHED".equals(post.getState())).count();
            if (published == posts.size()) {
                return "PUBLISHED";
            }
            if (published > 0) {
                return "PARTIAL";
            }
            var pending = posts.stream().filter(post -> "PENDING".equals(post.getState())).count();
            return pending > 0 ? "PENDING" : "FAILED";
        }

        /** 把失败/待确认的原因汇总成一句话，放进报告的 error 字段供后台直接查看 */
        static String failureSummary(List<DigestPostResultDTO> posts) {
            if (posts == null || posts.isEmpty()) {
                return null;
            }
            var summary = posts.stream()
                .filter(post -> !"PUBLISHED".equals(post.getState()))
                .limit(3)
                .map(post -> (StringUtils.hasText(post.getTitle()) ? post.getTitle() + "：" : "")
                    + (StringUtils.hasText(post.getMessage()) ? post.getMessage() : post.getState()))
                .collect(Collectors.joining("；"));
            return StringUtils.hasText(summary) ? summary : null;
        }
    }
}
