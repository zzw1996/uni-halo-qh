package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestCategoryConfigDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.infra.DigestSeenStore;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.MpIssue;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiHelper;
import pro.uhalo.uni.utils.DigestAiUsageLog;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestCoverGenerator;
import pro.uhalo.uni.utils.DigestTextUtils;
import pro.uhalo.uni.utils.MpAiHelper;
import pro.uhalo.uni.utils.MpConfigHelper;
import pro.uhalo.uni.utils.MpTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公众号专属文章生成（「每方向每天一篇」的精选聚合稿）。
 *
 * <p>与站点 AI 日报共用同一批零件（RSS 抓取、AI 评分/成稿、头图转存、封面卡片），
 * 但流水线独立：</p>
 * <ul>
 *   <li><b>只按方向选材</b>：AI 只能从「发布方向清单」里选分类，越界内容落到「其他」桶、
 *       不生成文章（清单窄的时候硬塞会让文章跑题）；</li>
 *   <li><b>去重独立</b>：已发记录用 {@code mp-} 前缀命名空间，与站点日报互不抢占素材——
 *       否则日报跑在前头会把好文章全「吃掉」，公众号只剩边角料；</li>
 *   <li><b>成稿形态不同</b>：一篇 = 导语 + 若干条精选点评（公众号只吃内联样式，
 *       不走博客那套 Mermaid/表格）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpIssueService {

    /** 评分批大小与并发：与日报同一口径（一批 10 篇、并发 2 批） */
    private static final int AI_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENT_AI = 2;
    private static final int AI_BATCH_SPLIT_DEPTH = 1;
    private static final int IMAGE_UPLOAD_CONCURRENCY = 4;

    /** 单次响应里能装下的点评字数预算（与日报同口径：按字数反比收缩批大小） */
    private static final int AI_OUTPUT_CHARS_BUDGET = 8000;
    private static final int MIN_WORDS_PER_ARTICLE = 150;

    /**
     * 内容不完整时的补请求次数。
     *
     * <p>模型只回一半（有概述、没小编看法）、整批返回空、通道限流、输出被 token
     * 上限截断……这些都会让某一批成稿「部分成功」。过去只有整批失败才会重试，
     * 于是半篇内容会一路写进公众号草稿——补请求按篇数算，成本可以接受。</p>
     */
    private static final int REPAIR_ATTEMPTS = 2;

    /** 摘要兜底截断长度（AI 没写出概述时用 RSS 描述） */
    private static final int DESCRIPTION_FALLBACK_LIMIT = 400;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final DigestFeedService feedService;
    private final DigestAiClient aiClient;
    private final DigestSeenStore seenStore;
    private final DigestAssetUploader assetUploader;
    private final MpImageService imageService;
    private final MpHtmlRenderer htmlRenderer;
    private final MpIssueRenderer issueRenderer;
    private final MpIssueStore store;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Progress progress = Progress.idle();

    /** 生成进度（后台页面轮询展示） */
    public record Progress(boolean running, String stage, Instant startedAt, Instant finishedAt,
                           String error) {

        static Progress idle() {
            return new Progress(false, "", null, null, null);
        }
    }

    public Progress progress() {
        return progress;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 生成指定方向的公众号稿（方向为空 = 全部方向）。
     *
     * <p>抓取与评分只跑一轮（评分本来就覆盖所有候选），然后逐方向成稿；
     * 每个方向独立落库、独立失败——某个方向没素材或成稿失败，不影响其它方向。</p>
     *
     * @param trigger 触发来源（manual / scheduled），记在稿件上供页面分辨
     */
    public Mono<List<MpIssue>> generate(String onlyDirection, boolean force) {
        return generate(onlyDirection, force, "manual");
    }

    public Mono<List<MpIssue>> generate(String onlyDirection, boolean force, String trigger) {
        if (!running.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("已有一次公众号文章生成任务在执行中，请稍后再试"));
        }
        var startedAt = Instant.now();
        progress = new Progress(true, "准备中…", startedAt, null, null);
        return doGenerate(onlyDirection, trigger)
            .doOnNext(issues -> {
                var summary = issues.stream()
                    .map(issue -> issue.getDirectionLabel() + "=" + issue.getState())
                    .collect(java.util.stream.Collectors.joining("、"));
                log.info("[UniHalo][Mp] 本轮生成结束（触发={}）：{}", trigger, summary);
                progress = new Progress(false, "", startedAt, Instant.now(), null);
            })
            .doOnError(ex -> progress = new Progress(false, "", startedAt, Instant.now(),
                StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName()))
            .doFinally(signal -> running.set(false));
    }

    private Mono<List<MpIssue>> doGenerate(String onlyDirection, String trigger) {
        return loadMpConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return Mono.error(new IllegalStateException(
                    "微信公众号功能未开启：请先在插件设置「微信公众号」中打开总开关"));
            }
            var directions = MpConfigHelper.publishingDirections(config).stream()
                .filter(direction -> !StringUtils.hasText(onlyDirection)
                    || direction.key().equalsIgnoreCase(onlyDirection.trim()))
                .toList();
            if (directions.isEmpty()) {
                return Mono.error(new IllegalStateException(StringUtils.hasText(onlyDirection)
                    ? "发布方向清单里没有「" + onlyDirection + "」这个方向，请检查插件设置"
                    : "发布方向清单为空：请在插件设置「微信公众号 → 发布方向清单」里至少保留一个方向"));
            }
            return loadDigestConfig().flatMap(aiConfig -> {
                if (!hasAiChannel(aiConfig)) {
                    return Mono.error(new IllegalStateException(
                        "没有可用的大模型通道：请先在插件设置「AI 日报 → 大模型通道」里配置至少一条启用的通道"));
                }
                aiClient.resetForNewRun();
                var usage = DigestAiUsageLog.forConfig(aiConfig);
                var catalog = MpConfigHelper.directionCatalog(config);
                var zone = MpConfigHelper.zoneOf(config.getTimezone());
                var now = ZonedDateTime.now(zone);
                var date = now.format(DATE_FORMAT);
                updateStage("开始抓取 RSS 源（时间窗 " + config.getHours() + " 小时）…");
                log.info("[UniHalo][Mp] ===== 开始生成公众号文章 ===== date={} 方向={} "
                        + "时间窗={}h 每方向精选={}篇 每篇≈{}字 摘要目标={}字 通道={}",
                    date, directions.stream().map(DigestCategoryConfigDTO::label).toList(),
                    config.getHours(), config.getPerDirectionTopN(), config.getWords(),
                    config.getDigestWords(), DigestAiChannelResolver.describe(aiConfig));
                return feedService.fetchRecent(config.getHours())
                    .flatMap(feeds -> {
                        if (feeds.getArticles().isEmpty()) {
                            return Mono.error(new IllegalStateException(
                                "时间窗内没有抓到任何文章（扫描 " + feeds.getTotalFeeds()
                                    + " 个源）：请检查服务器出网能力或调大时间窗"));
                        }
                        return seenStore.recentKeysFor(MpIssueStore.KIND_PREFIX)
                            .flatMap(historicalKeys -> {
                                var candidates = deduplicate(feeds.getArticles(), historicalKeys);
                                if (candidates.isEmpty()) {
                                    return Mono.error(new IllegalStateException(
                                        "候选文章都被「近期已发」去重过滤掉了：说明近 14 天"
                                            + "公众号已覆盖过这批内容，可稍后再试或放宽时间窗"));
                                }
                                return scoreAll(candidates, aiConfig, catalog, usage)
                                    .flatMap(scored -> {
                                        // 跨方向不重复选：一篇只能被一个方向收走
                                        var used = new java.util.HashSet<String>();
                                        return Flux.fromIterable(directions)
                                            .concatMap(direction -> materialize(direction, scored,
                                                    config, aiConfig, catalog, date, now, usage, used,
                                                    trigger)
                                                .onErrorResume(ex -> {
                                                    log.warn("[UniHalo][Mp] 方向「{}」生成失败：{}",
                                                        direction.label(), ex.getMessage());
                                                    return persistEmptyOrFailed(direction, date, now,
                                                        trigger, ex);
                                                }))
                                            .collectList();
                                    });
                            });
                    });
            });
        });
    }

    // ── 单个方向成稿 ──

    /**
     * 一个方向的成稿。
     *
     * <p>选材顺序：明确判为该方向的（按分数）→ 备选方向命中它的（方向保底，可关）。
     * 已被其它方向收走的文章不会再选（{@code used}）。</p>
     */
    private Mono<MpIssue> materialize(DigestCategoryConfigDTO direction,
        List<DigestArticleDTO> scored, MpConfigDTO config, DigestConfigDTO aiConfig,
        DigestCategoryCatalog catalog, String date, ZonedDateTime now, DigestAiUsageLog usage,
        Set<String> used, String trigger) {
        var selected = selectForDirection(direction.key(), scored, config.getPerDirectionTopN(),
            config.isDirectionFallbackEnabled(), used);
        var primaryCount = (int) selected.stream()
            .filter(article -> direction.key().equals(article.getCategory())).count();
        if (!selected.isEmpty()) {
            selected.forEach(article -> used.add(articleKey(article)));
        }
        if (selected.isEmpty()) {
            log.info("[UniHalo][Mp] 方向「{}」本期没有候选文章，记录为空稿", direction.label());
            var empty = MpIssueStore.newIssue(MpIssueStore.issueName(date, direction.key()),
                MpIssueStore.KIND_ISSUE, date + "|" + direction.key(), date, direction.key(),
                direction.label());
            empty.setTrigger(trigger);
            empty.setState("EMPTY");
            empty.setError("本期该方向没有可选文章：时间窗内的候选都被其它方向收走、已去重过滤，"
                + "或与方向不够相关（可在设置里打开「方向保底」或补充方向的判定说明）");
            empty.setFinishedAt(Instant.now());
            return saveOrUpdate(empty);
        }
        log.info("[UniHalo][Mp] 方向「{}」选中 {} 篇（明确命中 {} 篇、备选补位 {} 篇）；粒度={}：{}",
            direction.label(), selected.size(), primaryCount, selected.size() - primaryCount,
            MpConfigHelper.granularityLabel(config.getContentGranularity()),
            selected.stream().map(DigestArticleDTO::getTitle).toList());
        updateStage("正在撰写「" + direction.label() + "」的点评（" + selected.size() + " 篇）…");
        var finalSelection = selected;
        var perArticle = MpConfigHelper.GRANULARITY_ARTICLE.equals(config.getContentGranularity());
        // 每篇目标字数用的是「微信公众号 → 每篇字数」（过去这里误用了站点日报的字数，
        // 于是后台改公众号字数完全不生效）
        return summarizeBatchwise(finalSelection, aiConfig, config.getWords(), usage)
            .flatMap(summarized -> {
                var core = coreCount(summarized);
                if (core == 0) {
                    // 一篇都没成稿：与其产出「只有标题 + 原文摘要」的空稿推进公众号，
                    // 不如按失败报出来（站长重试一次通常就好——这类失败多是通道限流/截断）
                    return Mono.error(new IllegalStateException("AI 成稿这 " + summarized.size()
                        + " 篇都没拿到概述与小编看法（模型只回了一半、输出被长度上限截断或"
                        + "通道不可用都会这样）：已按失败处理，没有产出只有标题的空稿；"
                        + "请稍等片刻点「立即生成」重试"));
                }
                var note = contentNoteOf(summarized);
                if (StringUtils.hasText(note)) {
                    log.warn("[UniHalo][Mp] 方向「{}」成稿不完整：{}", direction.label(), note);
                }
                return Mono.just(summarized);
            })
            .flatMap(summarized -> uploadImages(summarized, aiConfig))
            .flatMap(withImages -> ensureConvertibleImages(withImages, direction, date, aiConfig,
                config))
            .flatMap(prepared -> {
                if (perArticle) {
                    // 每篇精选各自一篇：标题/摘要用该篇自己的（不再多花一次 AI 调用写三件套）
                    return persistPerArticle(direction, prepared.articles(), config, catalog, date,
                        now, trigger, prepared.replacedNote(), usage);
                }
                return buildHeader(direction, prepared.articles(), config, aiConfig, usage)
                    .map(header -> toIssue(direction, prepared.articles(), header, config, catalog,
                        date, now, trigger, prepared.replacedNote(), usage))
                    .flatMap(issue -> persistWithCost(issue, usage))
                    .flatMap(issue -> cleanupSuperseded(
                        java.util.Set.of(issue.getMetadata().getName()), date, direction.key())
                        .thenReturn(issue));
            })
            .flatMap(issue -> {
                if ("GENERATED".equals(issue.getState())) {
                    // 已发记录按「mp-」命名空间记账：只影响公众号自己的跨天去重
                    return seenStore.recordAll(finalSelection,
                        MpIssueStore.KIND_PREFIX + direction.key())
                        .thenReturn(issue);
                }
                return Mono.just(issue);
            });
    }

    /**
     * 「每篇精选各一篇」粒度：为每篇精选落一条独立稿件。
     *
     * <p>每篇的标题取其中文标题（≤32 字）、摘要取该篇概述（≤配置字数）、封面用它自己的配图，
     * 推送时就是各自的独立草稿——站长要的「分开推送」。</p>
     */
    private Mono<MpIssue> persistPerArticle(DigestCategoryConfigDTO direction,
        List<DigestArticleDTO> articles, MpConfigDTO config, DigestCategoryCatalog catalog,
        String date, ZonedDateTime now, String trigger, String imageNote, DigestAiUsageLog usage) {
        // 先定「哪几篇不产出」，再逐篇落稿：
        // ① 没有可用配图（公众号文章必须有图）；② 没拿到 AI 成稿（只有标题的空稿不能推上线）
        var skips = new java.util.LinkedHashMap<Integer, String>();
        for (var index = 0; index < articles.size(); index++) {
            var article = articles.get(index);
            if (!StringUtils.hasText(article.getUploadedImage())) {
                skips.put(index, "没有可用配图");
            } else if (!hasCoreContent(article)) {
                skips.put(index, "缺" + missingParts(article));
            }
        }
        // 被跳过的篇目写进同批稿件的说明里：只留在日志里站长看不到，
        // 表现就是「今天这个方向怎么少了几篇」
        var skipNote = "";
        if (!skips.isEmpty()) {
            var detail = skips.entrySet().stream().limit(3)
                .map(entry -> shortTitle(articles.get(entry.getKey())) + "（" + entry.getValue() + "）")
                .collect(java.util.stream.Collectors.joining("；"));
            skipNote = "同批另有 " + skips.size() + " 篇未生成：" + detail
                + (skips.size() > 3 ? " 等" : "");
            log.warn("[UniHalo][Mp] 方向「{}」{}", direction.label(), skipNote);
        }
        var finalSkipNote = skipNote;
        var issues = Flux.range(0, articles.size())
            .concatMap(index -> {
                if (skips.containsKey(index)) {
                    return Mono.empty();
                }
                var article = articles.get(index);
                var title = MpTextUtils.clipTitle(DigestRenderer.displayTitle(article),
                    MpTextUtils.MAX_TITLE_CHARS);
                var digest = digestOf(article, config);
                var markdown = issueRenderer.renderSingle(article, date, catalog);
                var issue = MpIssueStore.newIssue(
                    MpIssueStore.articleIssueName(date, direction.key(), index),
                    MpIssueStore.KIND_ISSUE, date + "|" + direction.key() + "|" + (index + 1), date,
                    direction.key(), direction.label());
                issue.setTrigger(trigger);
                issue.setTitle(title);
                issue.setDigest(digest);
                issue.setDigestWords(MpTextUtils.count(digest));
                issue.setMarkdown(markdown);
                issue.setHtml(htmlRenderer.render(markdown));
                issue.setArticleCount(1);
                issue.setArticles(serializeArticles(List.of(article), catalog));
                issue.setCover(article.getUploadedImage());
                issue.setCoverPlaceholder(article.isPlaceholderCover());
                issue.setImageNote(StringUtils.hasText(imageNote) ? imageNote : null);
                issue.setContentNote(issueNote(joinNotes(partialNoteOf(article), finalSkipNote),
                    issue.getHtml()));
                issue.setState("GENERATED");
                issue.setFinishedAt(Instant.now());
                issue.setUpdatedAt(Instant.now());
                return persistWithCost(issue, usage);
            })
            .collectList();
        return issues.flatMap(list -> {
            if (list.isEmpty()) {
                return Mono.error(new IllegalStateException(
                    "本期该方向的候选都没能成稿（没拿到 AI 概述/小编看法，或没有可用配图）："
                        + "未生成任何一篇；被跳过的 " + skips.size() + " 篇："
                        + String.join("；", skips.values()) + "。稍后点「立即生成」可重试"));
            }
            log.info("[UniHalo][Mp] 方向「{}」按「每篇一篇」生成 {} 篇独立稿{}", direction.label(),
                list.size(), skips.isEmpty() ? "" : "（跳过 " + skips.size() + " 篇）");
            var keep = new java.util.HashSet<String>();
            list.forEach(issue -> keep.add(issue.getMetadata().getName()));
            return cleanupSuperseded(keep, date, direction.key()).thenReturn(list.get(0));
        });
    }

    /** 把两段说明拼成一句（都为空时返回空串，便于「有才写库」） */
    static String joinNotes(String first, String second) {
        return java.util.stream.Stream
            .of(first == null ? "" : first, second == null ? "" : second)
            .filter(StringUtils::hasText)
            .collect(java.util.stream.Collectors.joining("。"));
    }

    /** 摘要取值顺序：概述 → 小编看法 → 原文摘要 → 标题（缺概述时也要给出能看的摘要） */
    private static String digestOf(DigestArticleDTO article, MpConfigDTO config) {
        var words = config.getDigestWords();
        for (var candidate : List.of(article.getSummary(), article.getAiTake(),
            article.getDescription())) {
            if (StringUtils.hasText(candidate)) {
                return MpTextUtils.tidyTail(MpTextUtils.clip(candidate, words));
            }
        }
        return MpTextUtils.clip(article.getTitle(), words);
    }

    /**
     * 清理「被本次生成取代」的旧稿：同一天、同一方向、没推过草稿、也不在生成中的记录。
     *
     * <p>典型场景是切换文章粒度（每方向一篇 ↔ 每篇各一篇）：旧形态的记录留着会让人以为
     * 重复生成了。已经推进过草稿箱的记录<b>不动</b>——那是真实发生过的推送历史。</p>
     */
    private Mono<Void> cleanupSuperseded(java.util.Set<String> keepNames, String date,
        String directionKey) {
        return store.listByDate(date)
            .flatMapMany(Flux::fromIterable)
            .filter(issue -> directionKey.equals(issue.getDirection()))
            .filter(issue -> !keepNames.contains(issue.getMetadata().getName()))
            .filter(issue -> !StringUtils.hasText(issue.getDraftMediaId()))
            .filter(issue -> !"GENERATING".equals(issue.getState()))
            .concatMap(issue -> store.delete(issue)
                .doOnNext(deleted -> log.info("[UniHalo][Mp] 已清理被新粒度取代的旧稿：{}",
                    deleted.getMetadata().getName()))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Mp] 清理旧稿失败（不影响本次生成）：{}", ex.getMessage());
                    return Mono.empty();
                }))
            .then();
    }

    /** 文章去重键（无链接/标题时退化为对象身份，保证不误判为同一篇） */
    private static String articleKey(DigestArticleDTO article) {
        var key = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
        return StringUtils.hasText(key) ? key : "@" + System.identityHashCode(article);
    }

    /**
     * 一个方向的选材（包内可见，便于单测）。
     *
     * <p>顺序即优先级：<b>明确判为该方向的</b>按分数取；不足时再用<b>备选方向命中它的</b>
     * 补位（方向保底，可关）。已被其它方向收走的文章（{@code used}）一律跳过——
     * 同一篇文章不能出现在两篇公众号文章里。</p>
     */
    static List<DigestArticleDTO> selectForDirection(String directionKey,
        List<DigestArticleDTO> scored, int topN, boolean fallbackEnabled, Set<String> used) {
        var primary = new ArrayList<DigestArticleDTO>();
        var secondary = new ArrayList<DigestArticleDTO>();
        for (var article : scored) {
            if (used.contains(articleKey(article))) {
                continue;
            }
            if (directionKey.equals(article.getCategory())) {
                primary.add(article);
            } else if (fallbackEnabled && directionKey.equals(article.getAltCategory())) {
                secondary.add(article);
            }
        }
        var byScore = Comparator.comparingInt(DigestArticleDTO::getTotalScore).reversed();
        primary.sort(byScore);
        secondary.sort(byScore);
        var selected = new ArrayList<DigestArticleDTO>();
        for (var article : primary) {
            if (selected.size() >= topN) {
                break;
            }
            selected.add(article);
        }
        for (var article : secondary) {
            if (selected.size() >= topN) {
                break;
            }
            selected.add(article);
        }
        return selected;
    }

    /** 该方向成稿失败时：已有旧稿就保留旧稿、只记错误；否则落一条 FAILED 记录 */
    private Mono<MpIssue> persistEmptyOrFailed(DigestCategoryConfigDTO direction, String date,
        ZonedDateTime now, String trigger, Throwable ex) {
        var name = MpIssueStore.issueName(date, direction.key());
        var message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
            : ex.getClass().getSimpleName();
        return store.fetch(name)
            .onErrorResume(fetchError -> Mono.empty())
            .flatMap(existing -> {
                existing.setError(message);
                existing.setUpdatedAt(Instant.now());
                return store.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var failed = MpIssueStore.newIssue(name, MpIssueStore.KIND_ISSUE,
                    date + "|" + direction.key(), date, direction.key(), direction.label());
                failed.setTrigger(trigger);
                failed.setState("FAILED");
                failed.setError(message);
                failed.setFinishedAt(Instant.now());
                return saveOrUpdate(failed);
            }));
    }

    private MpIssue toIssue(DigestCategoryConfigDTO direction, List<DigestArticleDTO> articles,
        MpAiHelper.Header header, MpConfigDTO config, DigestCategoryCatalog catalog,
        String date, ZonedDateTime now, String trigger, String imageNote,
        DigestAiUsageLog usage) {
        var name = MpIssueStore.issueName(date, direction.key());
        var issue = MpIssueStore.newIssue(name, MpIssueStore.KIND_ISSUE,
            date + "|" + direction.key(), date, direction.key(), direction.label());
        issue.setTrigger(trigger);

        var title = StringUtils.hasText(header.title()) ? header.title()
            : MpTextUtils.clipTitle(applyTitleTemplate(config.getTitleTemplate(),
                direction.label(), date), MpTextUtils.MAX_TITLE_CHARS);
        var digest = StringUtils.hasText(header.digest()) ? header.digest()
            : fallbackDigest(articles, config);

        issue.setTitle(title);
        issue.setDigest(digest);
        issue.setDigestWords(MpTextUtils.count(digest));
        var markdown = issueRenderer.render(direction.label(), header.intro(), articles, date, catalog);
        issue.setMarkdown(markdown);
        issue.setHtml(htmlRenderer.render(markdown));
        issue.setArticleCount(articles.size());
        issue.setArticles(serializeArticles(articles, catalog));
        // 封面优先用真实头图（不是标题卡片）；全是卡片时用第一张卡片
        var realImage = articles.stream()
            .filter(article -> !article.isPlaceholderCover())
            .map(DigestArticleDTO::getUploadedImage)
            .filter(StringUtils::hasText)
            .findFirst();
        var anyImage = articles.stream().map(DigestArticleDTO::getUploadedImage)
            .filter(StringUtils::hasText).findFirst();
        var cover = realImage.orElse(anyImage.orElse(""));
        issue.setCover(cover);
        issue.setCoverPlaceholder(realImage.isEmpty());
        issue.setImageNote(StringUtils.hasText(imageNote) ? imageNote : null);
        // 内容完整性说明（哪几篇缺了概述/小编看法、正文是否超长）：列表页可见，
        // 免得「今天这篇怎么只有一半」只能靠打开文章才发现
        issue.setContentNote(issueNote(contentNoteOf(articles), issue.getHtml()));
        // 每篇公众号文章必须有可显示的配图（草稿封面还必须有永久素材）
        if (!StringUtils.hasText(cover)) {
            issue.setState("FAILED");
            issue.setError("本期没有任何可用的配图（头图转存与标题卡片都失败了）："
                + "公众号文章必须带图，已跳过这一篇；可在站点侧检查附件存储策略后重新生成");
            issue.setFinishedAt(Instant.now());
            issue.setUpdatedAt(Instant.now());
            log.warn("[UniHalo][Mp] 方向「{}」没有可用配图，按缺图处理（不生成）", direction.label());
            return issue;
        }
        issue.setState("GENERATED");
        issue.setError(null);
        issue.setErrcode(null);
        issue.setErrmsg(null);
        issue.setFinishedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issue;
    }

    private static String applyTitleTemplate(String template, String directionLabel, String date) {
        var raw = StringUtils.hasText(template) ? template : "{direction}日报 · {date}";
        return raw.replace("{direction}", directionLabel).replace("{date}", date).trim();
    }

    /** 摘要兜底：AI 没给就取第一篇的概述（总比让微信去抓正文前 54 字强） */
    private String fallbackDigest(List<DigestArticleDTO> articles, MpConfigDTO config) {
        for (var article : articles) {
            if (StringUtils.hasText(article.getSummary())) {
                return MpTextUtils.tidyTail(MpTextUtils.clip(article.getSummary(),
                    config.getDigestWords()));
            }
        }
        return MpTextUtils.clip("本期精选 " + articles.size() + " 篇，详见正文。",
            config.getDigestWords());
    }

    // ── 评分 ──

    private Mono<List<DigestArticleDTO>> scoreAll(List<DigestArticleDTO> articles,
        DigestConfigDTO aiConfig, DigestCategoryCatalog catalog, DigestAiUsageLog usage) {
        var batches = partition(articles, AI_BATCH_SIZE);
        updateStage("AI 评分中（" + articles.size() + " 篇，" + batches.size() + " 批）…");
        log.info("[UniHalo][Mp] AI 评分：{} 篇 → {} 批（每批最多 {} 篇，并发 {}）",
            articles.size(), batches.size(), AI_BATCH_SIZE, MAX_CONCURRENT_AI);
        return Flux.fromIterable(batches)
            .flatMapSequential(batch -> scoreBatch(articles, batch, aiConfig, catalog, usage),
                MAX_CONCURRENT_AI)
            .then(Mono.fromSupplier(() -> articles));
    }

    private Mono<Void> scoreBatch(List<DigestArticleDTO> all, Batch batch,
        DigestConfigDTO aiConfig, DigestCategoryCatalog catalog, DigestAiUsageLog usage) {
        for (var article : batch.items()) {
            article.setRelevance(5);
            article.setQuality(5);
            article.setTimeliness(5);
            // 兜底分类：与方向都不沾边的内容落到「其他」，不会生成文章
            article.setCategory(DigestAiHelper.normalizeCategory("", catalog,
                DigestConfigHelper.UNKNOWN_CATEGORY_OTHER));
            article.setAltCategory("");
            article.setKeywords(new ArrayList<>());
        }
        return callScoring(all, batch, aiConfig, catalog, usage, 0);
    }

    private Mono<Void> callScoring(List<DigestArticleDTO> all, Batch batch,
        DigestConfigDTO aiConfig, DigestCategoryCatalog catalog, DigestAiUsageLog usage,
        int attempt) {
        // 公众号专用提示词：多要一个「备选方向」（方向保底靠它补位），原版提示词一个字不改
        var prompt = DigestAiHelper.buildScoringPromptForMp(batch.items(), batch.startIndex(),
            catalog);
        return aiClient.call(aiConfig, prompt, DigestAiTask.SCORE)
            .doOnNext(reply -> {
                usage.record(DigestAiTask.SCORE, reply);
                applyScores(all, reply.text(), catalog);
            })
            .then()
            .onErrorResume(ex -> {
                if (attempt == 0) {
                    log.warn("[UniHalo][Mp] 评分批次失败（{} 篇），重试一次：{}",
                        batch.items().size(), ex.getMessage());
                    return callScoring(all, batch, aiConfig, catalog, usage, attempt + 1);
                }
                log.warn("[UniHalo][Mp] 评分批次重试仍失败（{} 篇），按默认分继续：{}",
                    batch.items().size(), ex.getMessage());
                return Mono.empty();
            });
    }

    private void applyScores(List<DigestArticleDTO> all, String response,
        DigestCategoryCatalog catalog) {
        JsonNode root;
        try {
            root = DigestAiHelper.parseJson(response);
        } catch (Exception ex) {
            log.warn("[UniHalo][Mp] 评分结果解析失败：{}", ex.getMessage());
            return;
        }
        for (var result : root.path("results")) {
            var index = result.path("index").asInt(-1);
            if (index < 0 || index >= all.size()) {
                continue;
            }
            var article = all.get(index);
            article.setRelevance(DigestAiHelper.clampScore(result.path("relevance").asInt(5)));
            article.setQuality(DigestAiHelper.clampScore(result.path("quality").asInt(5)));
            article.setTimeliness(DigestAiHelper.clampScore(result.path("timeliness").asInt(5)));
            article.setCategory(DigestAiHelper.normalizeCategory(
                result.path("category").asText(""), catalog,
                DigestConfigHelper.UNKNOWN_CATEGORY_OTHER));
            // 备选方向：清单外的值一律丢弃；与主分类相同的也丢掉（没有信息量）
            var alt = catalog.normalize(result.path("altCategory").asText(""));
            article.setAltCategory(alt.equals(article.getCategory()) ? "" : alt);
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

    // ── 成稿（每篇点评，复用日报的成稿提示词） ──

    /**
     * 一篇成稿的「骨架」是否到位：概述 + 小编看法。
     *
     * <p>成稿提示词要的是五件套（中文标题 / 概述 / 核心信息 / 小编看法 / 推荐理由），
     * 但只有概述与小编看法是正文的骨架：少了它们，一个段落在公众号里就只剩标题与原文摘要
     * ——也就是「一篇文章只有个标题」。所以完整性以这两项为准。</p>
     */
    static boolean hasCoreContent(DigestArticleDTO article) {
        return article != null && StringUtils.hasText(article.getSummary())
            && StringUtils.hasText(article.getAiTake());
    }

    /** 还值不值得补请求：骨架缺失，或连核心信息都没有（补请求按篇算，别漏掉明显残缺的） */
    static boolean needsRepair(DigestArticleDTO article) {
        return !hasCoreContent(article)
            || article.getPoints() == null || article.getPoints().isEmpty();
    }

    /** 缺了哪几件（给站长看的说明；全部到位时返回空串） */
    static String missingParts(DigestArticleDTO article) {
        var missing = new ArrayList<String>();
        if (article == null || !StringUtils.hasText(article.getSummary())) {
            missing.add("概述");
        }
        if (article == null || !StringUtils.hasText(article.getAiTake())) {
            missing.add("小编看法");
        }
        if (article == null || article.getPoints() == null || article.getPoints().isEmpty()) {
            missing.add("核心信息");
        }
        if (article == null || !StringUtils.hasText(article.getReason())) {
            missing.add("推荐理由");
        }
        return String.join("、", missing);
    }

    /** 内容完整的篇数 */
    static int coreCount(List<DigestArticleDTO> articles) {
        return (int) articles.stream().filter(MpIssueService::hasCoreContent).count();
    }

    /**
     * 内容完整性说明（空串 = 全篇完整）。
     *
     * <p>落库到稿件的 {@code contentNote} 并在管理页红字显示：过去内容缺一半是<b>静默</b>的，
     * 只有打开文章才发现；现在打开列表就能看到哪几篇缺什么、为什么。</p>
     */
    static String contentNoteOf(List<DigestArticleDTO> articles) {
        var degraded = articles.stream().filter(article -> !hasCoreContent(article)).toList();
        if (degraded.isEmpty()) {
            return "";
        }
        var detail = degraded.stream().limit(3)
            .map(article -> shortTitle(article) + "（缺" + missingParts(article) + "）")
            .collect(java.util.stream.Collectors.joining("；"));
        return "有 " + degraded.size() + " 篇没拿到完整的 AI 成稿：" + detail
            + (degraded.size() > 3 ? " 等" : "")
            + "。多是大模型只回了一半、输出被长度上限截断或通道限流；已用原文摘要顶替，"
            + "稍后点「立即生成」可重试";
    }

    private static String shortTitle(DigestArticleDTO article) {
        var title = DigestRenderer.displayTitle(article);
        return StringUtils.hasText(title) && title.length() > 16 ? title.substring(0, 16) + "…"
            : (StringUtils.hasText(title) ? title : "（无标题）");
    }

    /**
     * 单篇稿件的残缺说明（空串 = 五件套齐）。
     *
     * <p>骨架缺的篇目根本不会成稿（上面直接跳过），所以这里只报「少了分点/推荐理由」
     * 这种半篇情况——正文里会缺这么一块，值得让站长看到。</p>
     */
    static String partialNoteOf(DigestArticleDTO article) {
        if (!hasCoreContent(article)) {
            return "";
        }
        var missing = missingParts(article);
        return StringUtils.hasText(missing)
            ? "本篇没拿到 AI 的「" + missing + "」（模型只回了一半、输出被截断时会出现），"
                + "正文里会缺这一块；稍后点「立即生成」可重试"
            : "";
    }

    /**
     * 稿件的内容说明：残缺说明 + 正文超长提示。
     *
     * <p>正文超长这条必须在生成阶段就报出来——推送时 {@code clipContent} 才会截断，
     * 站长如果只在公众号后台看结果，看到的就是「文章莫名其妙少了一半」。</p>
     */
    static String issueNote(String contentNote, String html) {
        var over = "";
        if (MpTextUtils.exceedsContentLimit(html)) {
            over = "正文约 " + MpTextUtils.count(html) + " 字符，超过微信 2 万字符上限，"
                + "推送时会被截断：建议调小「每篇字数」或「每个方向精选几篇」后重新生成";
        }
        return String.join("。", java.util.stream.Stream
            .of(contentNote == null ? "" : contentNote, over)
            .filter(StringUtils::hasText).toList());
    }

    /**
     * 逐方向成稿（包内可见：单测直接跑「整批 → 补概述 → 补完整性」这条链）。
     *
     * @param words 每篇目标字数：取自「微信公众号 → 每篇字数」，批大小按它反比收缩
     */
    Mono<List<DigestArticleDTO>> summarizeBatchwise(List<DigestArticleDTO> articles,
        DigestConfigDTO aiConfig, int words, DigestAiUsageLog usage) {
        var batchSize = Math.max(3, Math.min(AI_BATCH_SIZE,
            AI_OUTPUT_CHARS_BUDGET / Math.max(words, MIN_WORDS_PER_ARTICLE)));
        var batches = partition(articles, batchSize);
        return Flux.fromIterable(batches)
            .flatMapSequential(batch -> summarizeOneBatch(batch, aiConfig, words, usage, 0),
                MAX_CONCURRENT_AI)
            .then(Mono.defer(() -> fillMissingSummaries(articles, aiConfig, words, usage)))
            .flatMap(list -> repairIncomplete(list, aiConfig, words, usage))
            .map(list -> {
                for (var article : list) {
                    if (!StringUtils.hasText(article.getSummary())) {
                        article.setSummary(DigestAiHelper.truncateForPrompt(
                            article.getDescription(), DESCRIPTION_FALLBACK_LIMIT));
                        if (!StringUtils.hasText(article.getTitleZh())) {
                            article.setTitleZh(article.getTitle());
                        }
                    }
                }
                return list;
            });
    }

    private Mono<Void> summarizeOneBatch(Batch batch, DigestConfigDTO aiConfig, int words,
        DigestAiUsageLog usage, int depth) {
        var prompt = DigestAiHelper.buildSummaryPrompt(batch.items(), batch.startIndex(),
            aiConfig.getLanguage(), words);
        return aiClient.call(aiConfig, prompt, DigestAiTask.WRITE)
            .doOnNext(reply -> {
                usage.record(DigestAiTask.WRITE, reply);
                if (applySummaries(batch, reply.text()) == 0) {
                    throw new IllegalStateException("成稿结果为空"
                        + (DigestAiHelper.isOutputTruncated(reply) ? "（输出被长度上限截断）" : ""));
                }
            })
            .then()
            .onErrorResume(ex -> {
                if (depth < AI_BATCH_SPLIT_DEPTH && batch.items().size() > 1) {
                    var mid = (batch.items().size() + 1) / 2;
                    log.warn("[UniHalo][Mp] 成稿批次（{} 篇）失败，拆半重试：{}",
                        batch.items().size(), ex.getMessage());
                    return summarizeOneBatch(new Batch(batch.startIndex(),
                            batch.items().subList(0, mid)), aiConfig, words, usage, depth + 1)
                        .then(summarizeOneBatch(new Batch(batch.startIndex() + mid,
                            batch.items().subList(mid, batch.items().size())), aiConfig, words,
                            usage, depth + 1));
                }
                log.warn("[UniHalo][Mp] 成稿批次（{} 篇）失败，稍后单篇补请求：{}",
                    batch.items().size(), ex.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 只写非空字段，返回值表示这次响应是否真的带了成稿内容。
     *
     * <p>过去的写法是「无条件覆盖」：模型这次漏了 aiTake，就把上一批已经写好的小编看法
     * 抹成空串——补请求反而把内容越修越少。现在漏回即保留既有值。</p>
     */
    private static boolean writeIfPresent(JsonNode result, String field,
        java.util.function.Consumer<String> setter) {
        var value = result.path(field).asText("");
        if (!StringUtils.hasText(value)) {
            return false;
        }
        setter.accept(value.trim());
        return true;
    }

    /** 按篇写回成稿（包内可见：单测直接喂 AI 的 JSON 断言「漏字段不抹内容」） */
    int applySummaries(Batch batch, String response) {
        JsonNode root;
        try {
            root = DigestAiHelper.parseJson(response);
        } catch (Exception ex) {
            throw new IllegalStateException("成稿 JSON 解析失败：" + ex.getMessage(), ex);
        }
        var results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("成稿返回 results 为空");
        }
        var applied = 0;
        for (var result : results) {
            var index = result.path("index").asInt(-1) - batch.startIndex();
            if (index < 0 || index >= batch.items().size()) {
                continue;
            }
            var article = batch.items().get(index);
            var carried = writeIfPresent(result, "titleZh", article::setTitleZh);
            carried |= writeIfPresent(result, "summary", article::setSummary);
            carried |= writeIfPresent(result, "aiTake", article::setAiTake);
            carried |= writeIfPresent(result, "reason", article::setReason);
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
            if (!points.isEmpty()) {
                article.setPoints(points);
                carried = true;
            }
            if (carried) {
                applied++;
            }
        }
        return applied;
    }

    private Mono<List<DigestArticleDTO>> fillMissingSummaries(List<DigestArticleDTO> articles,
        DigestConfigDTO aiConfig, int words, DigestAiUsageLog usage) {
        var missing = articles.stream()
            .filter(article -> !StringUtils.hasText(article.getSummary()))
            .toList();
        if (missing.isEmpty()) {
            return Mono.just(articles);
        }
        log.warn("[UniHalo][Mp] {} 篇未拿到概述，逐篇补请求", missing.size());
        return Flux.fromIterable(missing)
            .flatMapSequential(item -> summarizeOneBatch(new Batch(0, List.of(item)), aiConfig,
                words, usage, AI_BATCH_SPLIT_DEPTH), MAX_CONCURRENT_AI)
            .then(Mono.fromSupplier(() -> articles));
    }

    /**
     * 完整性修复：把「只回了一半」的篇目逐篇补请求。
     *
     * <p>这是这次线上问题的直接修复点——方向 1 成稿完整、方向 2/3 只拿到概述（甚至什么都没有），
     * 原文里只校验了概述，缺小编看法/核心信息既不会补请求也不会报错，用户拿到的就是半篇文章。</p>
     */
    private Mono<List<DigestArticleDTO>> repairIncomplete(List<DigestArticleDTO> articles,
        DigestConfigDTO aiConfig, int words, DigestAiUsageLog usage) {
        var incomplete = articles.stream().filter(MpIssueService::needsRepair).toList();
        if (incomplete.isEmpty()) {
            return Mono.just(articles);
        }
        log.warn("[UniHalo][Mp] {} 篇成稿不完整（缺 {}），逐篇补请求最多 {} 次",
            incomplete.size(),
            incomplete.stream().map(MpIssueService::missingParts)
                .distinct().collect(java.util.stream.Collectors.joining(" / ")),
            REPAIR_ATTEMPTS);
        return Flux.fromIterable(incomplete)
            .flatMapSequential(item -> repairOne(item, aiConfig, words, usage, 1), MAX_CONCURRENT_AI)
            .then(Mono.fromSupplier(() -> articles));
    }

    /** 补请求一篇（最多 {@value #REPAIR_ATTEMPTS} 次）：内容补齐就停手，不再浪费额度 */
    private Mono<Void> repairOne(DigestArticleDTO article, DigestConfigDTO aiConfig, int words,
        DigestAiUsageLog usage, int attempt) {
        if (!needsRepair(article) || attempt > REPAIR_ATTEMPTS) {
            return Mono.empty();
        }
        var prompt = DigestAiHelper.buildSummaryPrompt(List.of(article), 0,
            aiConfig.getLanguage(), words);
        return aiClient.call(aiConfig, prompt, DigestAiTask.WRITE)
            .doOnNext(reply -> {
                usage.record(DigestAiTask.WRITE, reply);
                applySummaries(new Batch(0, List.of(article)), reply.text());
            })
            .then()
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Mp] 第 {} 次补请求失败（{}）：{}", attempt,
                    shortTitle(article), ex.getMessage());
                return Mono.empty();
            })
            .then(Mono.defer(() -> repairOne(article, aiConfig, words, usage, attempt + 1)));
    }

    // ── 图片与封面 ──

    private Mono<List<DigestArticleDTO>> uploadImages(List<DigestArticleDTO> articles,
        DigestConfigDTO aiConfig) {
        updateStage("抓取并转存头图…");
        return feedService.resolveLeadImages(articles)
            .flatMap(resolved -> Flux.fromIterable(resolved)
                .index()
                .flatMapSequential(tuple -> {
                    var index = tuple.getT1().intValue();
                    var article = tuple.getT2();
                    if (!DigestTextUtils.isUsableImageUrl(article.getImageUrl())) {
                        return Mono.empty();
                    }
                    var baseName = String.format("mp-%02d-%s", index + 1,
                        DigestTextUtils.slugify(article.getSourceName()));
                    return assetUploader.uploadImage(article.getImageUrl(), baseName, aiConfig)
                        .doOnNext(article::setUploadedImage)
                        .onErrorResume(ex -> {
                            log.warn("[UniHalo][Mp] 第 {} 篇头图转存失败，跳过：{}",
                                index + 1, ex.getMessage());
                            return Mono.empty();
                        });
                }, IMAGE_UPLOAD_CONCURRENCY)
                .then(Mono.fromSupplier(() -> resolved)));
    }

    /**
     * 配图保证：每篇的图都必须是微信「认」的（可转成 jpg/png 且压得进 1MB）。
     *
     * <p>为什么在生成阶段就做：等到推送时才发现图用不了，用户拿到的是
     * 「一张图没换过去」的稿子；而且公众号会直接过滤外链图片，正文里那张图
     * 就是白放的。这里逐张预检，不能用的立刻换成 2.35:1 的标题卡片
     * （PNG，必定可转），并在稿件上写明「换了几张、为什么」。</p>
     *
     * <p>卡片同时解决另一个问题：一篇文章的所有配图都不可用时，正文里也必须
     * 至少有一张图——卡片会照常出现在正文里（渲染器不再跳过占位封面）。</p>
     */
    private Mono<PreparedArticles> ensureConvertibleImages(List<DigestArticleDTO> articles,
        DigestCategoryConfigDTO direction, String date, DigestConfigDTO aiConfig,
        MpConfigDTO config) {
        if (articles.isEmpty()) {
            return Mono.just(new PreparedArticles(articles, ""));
        }
        updateStage("检查配图是否满足微信要求…");
        var replaced = new ArrayList<String>();
        return Flux.fromIterable(articles)
            .concatMap(article -> {
                var uploaded = article.getUploadedImage();
                if (!StringUtils.hasText(uploaded)) {
                    return replaceWithCard(article, direction, date, aiConfig, "没有可用头图")
                        .doOnNext(reason -> replaced.add(reason))
                        .thenReturn(article);
                }
                return imageService.checkConvertible(uploaded)
                    .flatMap(check -> {
                        if (check.ok()) {
                            return Mono.just(article);
                        }
                        return replaceWithCard(article, direction, date, aiConfig, check.reason())
                            .doOnNext(reason -> replaced.add(reason))
                            .thenReturn(article);
                    });
            }, IMAGE_UPLOAD_CONCURRENCY)
            .then(Mono.fromSupplier(() -> {
                var note = replaced.isEmpty() ? ""
                    : replaced.size() + " 张配图不符合微信要求，已用标题卡片顶替："
                        + String.join("；", replaced.subList(0, Math.min(3, replaced.size())))
                        + (replaced.size() > 3 ? " 等" : "");
                if (!note.isEmpty()) {
                    log.warn("[UniHalo][Mp] 方向「{}」配图顶替：{}", direction.label(), note);
                }
                return new PreparedArticles(articles, note);
            }));
    }

    /** 把某一篇的配图换成标题卡片；返回一句可展示的原因说明 */
    private Mono<String> replaceWithCard(DigestArticleDTO article,
        DigestCategoryConfigDTO direction, String date, DigestConfigDTO aiConfig, String reason) {
        return siteTitle().flatMap(siteName -> {
            byte[] bytes;
            try {
                bytes = DigestCoverGenerator.renderWideCard(direction.key(), direction.label(),
                    DigestRenderer.displayTitle(article), article.getSourceName(), date, siteName);
            } catch (Exception ex) {
                log.warn("[UniHalo][Mp] 标题卡片生成失败（该篇将没有配图）：{}", ex.getMessage());
                article.setUploadedImage(null);
                article.setPlaceholderCover(true);
                return Mono.just(shortReason(article.getTitle()) + "：" + reason
                    + "（卡片生成也失败）");
            }
            var baseName = "mp-card-" + date + "-" + DigestTextUtils.slugify(direction.key())
                + "-" + DigestTextUtils.slugify(DigestRenderer.displayTitle(article));
            return assetUploader.uploadBytes(bytes, "image/png", baseName, aiConfig)
                .doOnNext(permalink -> {
                    article.setUploadedImage(permalink);
                    article.setPlaceholderCover(true);
                })
                .map(permalink -> shortReason(DigestRenderer.displayTitle(article)) + "：" + reason)
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Mp] 标题卡片上传失败（该篇将没有配图）：{}", ex.getMessage());
                    article.setUploadedImage(null);
                    article.setPlaceholderCover(true);
                    return Mono.just(shortReason(article.getTitle()) + "：" + reason
                        + "（卡片上传失败：" + ex.getMessage() + "）");
                });
        });
    }

    private static String shortReason(String title) {
        return StringUtils.hasText(title) && title.length() > 16
            ? title.substring(0, 16) + "…" : (StringUtils.hasText(title) ? title : "（无标题）");
    }

    /** 配图预检结果：文章列表 + 顶替说明 */
    private record PreparedArticles(List<DigestArticleDTO> articles, String replacedNote) {
    }

    private Mono<String> siteTitle() {
        return settingFetcher.get("basic")
            .map(node -> node.path("title").asText("").trim())
            .defaultIfEmpty("")
            .onErrorResume(ex -> Mono.just(""));
    }

    // ── 标题 / 摘要 / 导语 ──

    private Mono<MpAiHelper.Header> buildHeader(DigestCategoryConfigDTO direction,
        List<DigestArticleDTO> articles, MpConfigDTO config, DigestConfigDTO aiConfig,
        DigestAiUsageLog usage) {
        updateStage("生成「" + direction.label() + "」的标题与摘要…");
        var prompt = MpAiHelper.buildHeaderPrompt(direction.label(), articles,
            config.getDigestWords());
        return aiClient.call(aiConfig, prompt, DigestAiTask.MP)
            .doOnNext(reply -> usage.record(DigestAiTask.MP, reply))
            .map(reply -> MpAiHelper.parseHeader(reply.text(), config.getDigestWords()))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Mp] 标题/摘要生成失败，用模板与首篇概述兜底：{}", ex.getMessage());
                return Mono.just(new MpAiHelper.Header("", "", ""));
            });
    }

    // ── 持久化与工具 ──

    private Mono<MpIssue> persistWithCost(MpIssue issue, DigestAiUsageLog usage) {
        try {
            if (!usage.isEmpty()) {
                issue.setAiCost(OBJECT_MAPPER.writeValueAsString(usage.snapshot()));
            }
        } catch (Exception ex) {
            log.debug("[UniHalo][Mp] AI 花费快照序列化失败（不影响生成）：{}", ex.getMessage());
        }
        return saveOrUpdate(issue);
    }

    /** 新建或覆盖同名记录（同名 = 同一天同一方向，重复生成时覆盖内容、保留推送历史里的媒资 id） */
    Mono<MpIssue> saveOrUpdate(MpIssue fresh) {
        return saveOrUpdate(fresh, true);
    }

    /**
     * 新建或覆盖同名记录；遇到乐观锁冲突（并发写同一条记录）时重读一次再写。
     *
     * <p>生成与推送可能同时在跑（站长点「立即生成」时上一轮推送还没结束），
     * 那样 update 会撞上 Halo 的版本校验；这里重试一次，而不是把内部错误抛给站长。</p>
     */
    private Mono<MpIssue> saveOrUpdate(MpIssue fresh, boolean retryOnConflict) {
        var name = fresh.getMetadata().getName();
        return store.fetch(name)
            .onErrorResume(ex -> Mono.empty())
            .flatMap(existing -> {
                fresh.getMetadata().setVersion(existing.getMetadata().getVersion());
                fresh.getMetadata()
                    .setCreationTimestamp(existing.getMetadata().getCreationTimestamp());
                fresh.setCreatedAt(existing.getCreatedAt());
                if (!StringUtils.hasText(fresh.getCoverMediaId())) {
                    fresh.setCoverMediaId(existing.getCoverMediaId());
                }
                // 尝试次数累加：调度器靠它判断「失败后还值不值得再试一次」
                var previous = existing.getAttempts() == null ? 1 : existing.getAttempts();
                fresh.setAttempts(previous + 1);
                return store.update(fresh);
            })
            .switchIfEmpty(Mono.defer(() -> store.create(fresh)))
            .onErrorResume(ex -> {
                if (retryOnConflict && MpIssueStore.isVersionConflict(ex)) {
                    log.info("[UniHalo][Mp] 稿件 {} 写入遇到并发更新，重读记录后重试一次", name);
                    return saveOrUpdate(fresh, false);
                }
                return Mono.error(ex);
            });
    }

    private static List<DigestArticleDTO> deduplicate(List<DigestArticleDTO> articles,
        Set<String> historicalKeys) {
        var kept = new java.util.LinkedHashMap<String, DigestArticleDTO>();
        var historyFiltered = 0;
        for (var article : articles) {
            var key = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
            if (!StringUtils.hasText(key)) {
                key = "@" + System.identityHashCode(article);
            }
            if (historicalKeys.contains(key)) {
                historyFiltered++;
                continue;
            }
            kept.putIfAbsent(key, article);
        }
        if (historyFiltered > 0) {
            log.info("[UniHalo][Mp] 跨天去重：{} 篇近期已发过，直接跳过", historyFiltered);
        }
        return new ArrayList<>(new LinkedHashSet<>(kept.values()));
    }

    private String serializeArticles(List<DigestArticleDTO> articles,
        DigestCategoryCatalog catalog) {
        var entries = new ArrayList<java.util.Map<String, Object>>();
        for (var article : articles) {
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("title", DigestRenderer.displayTitle(article));
            entry.put("sourceName", article.getSourceName());
            entry.put("link", article.getLink());
            entry.put("summary", article.getSummary());
            entry.put("category", article.getCategory());
            entry.put("categoryLabel", catalog.labelOf(article.getCategory()));
            entry.put("categoryEmoji", catalog.emojiOf(article.getCategory()));
            entry.put("score", article.getTotalScore());
            entries.add(entry);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(entries);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static boolean hasAiChannel(DigestConfigDTO aiConfig) {
        if (aiConfig.getAiProviders() != null && !aiConfig.getAiProviders().isEmpty()) {
            return aiConfig.getAiProviders().stream().anyMatch(DigestAiProviderDTO::enabled);
        }
        return StringUtils.hasText(aiConfig.getAiApiKey());
    }

    private Mono<MpConfigDTO> loadMpConfig() {
        return settingFetcher.get(MpConfigHelper.groupName())
            .map(MpConfigHelper::parse)
            .defaultIfEmpty(MpConfigHelper.defaults());
    }

    private Mono<DigestConfigDTO> loadDigestConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }

    private void updateStage(String stage) {
        var current = progress;
        progress = new Progress(true, stage, current.startedAt(), null, null);
    }

    /** 批次：起始下标 + 本批文章（下标用于把 AI 返回的 index 映射回文章） */
    record Batch(int startIndex, List<DigestArticleDTO> items) {
    }

    static List<Batch> partition(List<DigestArticleDTO> articles, int size) {
        var batches = new ArrayList<Batch>();
        for (var i = 0; i < articles.size(); i += size) {
            batches.add(new Batch(i, articles.subList(i, Math.min(articles.size(), i + size))));
        }
        return batches;
    }
}
