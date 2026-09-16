package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.PostClassifyApplyRequest;
import pro.uhalo.uni.dto.PostClassifyApplyResponse;
import pro.uhalo.uni.dto.PostClassifyCandidateDTO;
import pro.uhalo.uni.dto.PostClassifyPreviewResponse;
import pro.uhalo.uni.dto.PostClassifyStatusDTO;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.PostClassifyPromptHelper;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 文章归类：把插件自己发布、却漏了分类的日报文章补上主题分类与标签。
 *
 * <p><b>为什么需要它</b>：日报文章本该在发布时就带分类与标签，但早前那条扩展客户端
 * 发布路径把 AI 分类与关键词静默丢掉了，于是历史上发布出来的文章
 * {@code spec.categories / spec.tags} 全是空——站点主题与小程序都从内容接口的
 * categories / tags 取数据，文章页上一条分类标签都不显示。这个服务就是把那批存量补齐。</p>
 *
 * <p><b>范围刻意收得很窄</b>：只分析<b>带日报来源标记</b>（{@code uni.uhalo.pro/digest}
 * 标签或 {@code uni.uhalo.pro/digest-generated} 注解）且<b>一篇分类都没有</b>的文章。
 * 两个限制各有原因：</p>
 * <ul>
 *   <li>只认插件自己发布的内容——站长自己写的文章该由他自己归类，机器不该替他决定；</li>
 *   <li>只要已有任意一个分类就跳过，而不是只跳过「已有主题分类」的。站长把文章归进
 *       他自己的分类（记录 / 手册 / 开箱……）同样是「已经分好类」，机器再判一遍
 *       既浪费 AI 额度，也可能往他整理好的分类后面追加一个没必要的主题分类。</li>
 * </ul>
 *
 * <p><b>为什么是「先预览、再应用」两步</b>：归类要花 AI 调用（按批计费），
 * 而写回分类标签是直接改站点数据。让机器先把建议摆出来、站长逐条点头再落库，
 * 比起「点一下批量改掉 200 篇文章」可逆得多，也让站长能第一时间发现判定跑偏。</p>
 *
 * <p><b>只追加，绝不覆盖</b>：写回时只在 {@code spec.categories} / {@code spec.tags}
 * 末尾追加，站长原有的分类标签一个不动，标题、正文、摘要也完全不碰。
 * 这让整个操作在语义上等价于「多打几个标签」，出错了也只是多几个标签，删掉即可。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostTaxonomyService {

    /** 每批分析篇数：一次 AI 调用处理这些篇，提示词规模可控，失败重试的成本也低 */
    public static final int BATCH_SIZE = 15;

    /**
     * 单次预览允许的最大篇数。
     *
     * <p>预览是同步请求（一次 AI 调用就在这个请求里等），篇数越多等待越久、
     * 越可能被网关掐断，所以上限压到 30；超出的部分再点一次即可。</p>
     */
    private static final int MAX_LIMIT = 30;

    /** 预览表里摘要的截断长度（送进提示词的还会再截一次，见 PostClassifyPromptHelper） */
    private static final int PREVIEW_TEXT_LIMIT = 300;

    /** 单篇最多写入几个标签：AI 只给 2-4 个，这里防的是有人构造超长请求把标签墙刷爆 */
    private static final int MAX_TAGS_PER_POST = 6;

    /** 并发写库的乐观锁重试次数（Halo 协调器可能同时在校改这篇文章） */
    private static final int UPDATE_RETRIES = 5;

    /** 最新优先：首页展示的就是最新的文章，先归类它们，访客立刻能看到效果 */
    private static final Comparator<Post> NEWEST_FIRST = Comparator
        .comparing(PostTaxonomyService::publishTimeOf, Comparator.reverseOrder())
        .thenComparing(post -> post.getMetadata().getName());

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final DigestAiClient aiClient;
    private final DigestTaxonomyService taxonomy;

    // ── 进度 ──

    /**
     * AI 日报生成的文章里，还有多少篇没有分类。
     *
     * <p><b>只统计插件自己发布、且一篇分类都没有的文章</b>：站长自己写的文章他会自己分类，
     * 已经分好的文章更不该被机器重新判一遍——那既浪费 AI 额度，也可能把他手工整理的结果
     * 覆盖成另一个主题。</p>
     */
    public Mono<PostClassifyStatusDTO> status() {
        return loadConfig().flatMap(config -> loadPublishedPosts().map(posts -> {
            var digests = posts.stream().filter(PostTaxonomyService::isDigestGenerated).toList();
            var classified = (int) digests.stream()
                .filter(PostTaxonomyService::hasAnyCategory)
                .count();
            return PostClassifyStatusDTO.builder()
                .digestPosts(digests.size())
                .classifiedPosts(classified)
                .pendingPosts(digests.size() - classified)
                .batchSize(BATCH_SIZE)
                .aiReady(StringUtils.hasText(config.getAiApiKey()))
                .autoCategoryEnabled(config.isAutoCategoryEnabled())
                .autoTagEnabled(config.isAutoTagEnabled())
                .build();
        }));
    }

    // ── 预览（不写库） ──

    /**
     * 分析下一批待归类文章，只返回建议，<b>不改动任何文章</b>。
     */
    public Mono<PostClassifyPreviewResponse> preview(int limit) {
        var size = Math.min(MAX_LIMIT, Math.max(1, limit <= 0 ? BATCH_SIZE : limit));
        return loadConfig().flatMap(config -> {
            if (!StringUtils.hasText(config.getAiApiKey())) {
                return Mono.error(new IllegalStateException(
                    "还没配置 AI 接口密钥（插件设置 - AI 日报 - API Key），无法判定文章主题"));
            }
            return Mono.zip(loadPublishedPosts(), taxonomy.categoryLabels(), taxonomy.tagLabels())
                .flatMap(tuple -> {
                    var pending = tuple.getT1().stream()
                        .filter(PostTaxonomyService::isDigestGenerated)
                        .filter(post -> !hasAnyCategory(post))
                        .sorted(NEWEST_FIRST)
                        .toList();
                    if (pending.isEmpty()) {
                        return Mono.just(PostClassifyPreviewResponse.builder()
                            .items(List.of())
                            .analyzed(0)
                            .pendingPosts(0)
                            .hasMore(false)
                            .aiModel(config.getAiModel())
                            .message("AI 日报生成的文章都已有分类，没有需要分析的"
                                + "（你自己写的文章不在这里分析，请到 Halo 后台自行归类）")
                            .build());
                    }
                    var batch = pending.stream().limit(size).toList();
                    var candidates = batch.stream()
                        .map(post -> toCandidate(post, tuple.getT2(), tuple.getT3()))
                        .toList();
                    log.info("[UniHalo][Digest] 旧文章归类：开始分析 {} 篇（剩余待归类 {} 篇）",
                        candidates.size(), pending.size());
                    // 与日报流水线共用同一个客户端：降级状态按轮次记忆，这里也要重置
                    aiClient.resetForNewRun();
                    return aiClient.call(config, PostClassifyPromptHelper.buildPrompt(candidates))
                        .map(text -> applyVerdicts(candidates, text, config))
                        .map(items -> PostClassifyPreviewResponse.builder()
                            .items(items)
                            .analyzed(items.size())
                            .pendingPosts(pending.size())
                            .hasMore(pending.size() > batch.size())
                            .aiModel(config.getAiModel())
                            .message("已分析 " + items.size() + " 篇，勾选要采纳的建议后再应用"
                                + "（应用前不会改动任何文章）")
                            .build())
                        .doOnNext(response -> log.info(
                            "[UniHalo][Digest] 旧文章归类：预览完成 {} 篇", response.getAnalyzed()));
                });
        });
    }

    // ── 应用（写库） ──

    /**
     * 把站长勾选采纳的建议写回文章：只追加分类与标签，逐条回报结果。
     */
    public Mono<PostClassifyApplyResponse> apply(PostClassifyApplyRequest request) {
        var items = request == null ? null : request.getItems();
        if (items == null || items.isEmpty()) {
            return Mono.error(new IllegalArgumentException("没有要应用的条目"));
        }
        log.info("[UniHalo][Digest] 旧文章归类：收到应用请求 {} 条", items.size());
        // concatMap 而不是 flatMap：逐篇串行写，避免同一批文章一起更新时互相撞乐观锁
        return Flux.fromIterable(items)
            .concatMap(this::applyOne)
            .collectList()
            .map(results -> {
                var applied = countOf(results, "APPLIED");
                var skipped = countOf(results, "SKIPPED");
                var failed = countOf(results, "FAILED");
                var message = new StringBuilder("已归类 " + applied + " 篇");
                if (skipped > 0) {
                    message.append("，跳过 ").append(skipped).append(" 篇");
                }
                if (failed > 0) {
                    message.append("，失败 ").append(failed).append(" 篇（可再点一次重试）");
                }
                log.info("[UniHalo][Digest] 旧文章归类结束：成功 {} 跳过 {} 失败 {}",
                    applied, skipped, failed);
                return PostClassifyApplyResponse.builder()
                    .applied(applied)
                    .skipped(skipped)
                    .failed(failed)
                    .items(results)
                    .message(message.toString())
                    .build();
            });
    }

    /** 单篇写入。整个链路套在 defer 里，重试时才会重新读到最新版本。 */
    private Mono<PostClassifyApplyResponse.Item> applyOne(PostClassifyApplyRequest.Item item) {
        if (item == null || !StringUtils.hasText(item.getName())) {
            return Mono.just(result("", "", "FAILED", "", List.of(), "条目缺少文章标识"));
        }
        var name = item.getName().trim();
        return Mono.defer(() -> client.fetch(Post.class, name)
                .flatMap(post -> {
                    if (post.getSpec() == null) {
                        return Mono.just(result(name, "", "SKIPPED", "", List.of(), "文章内容为空"));
                    }
                    var spec = post.getSpec();
                    var title = nullToEmpty(spec.getTitle());
                    return Mono.zip(
                            resolveCategoryRefs(item.getCategory()),
                            resolveTagRefs(item.getKeywords()))
                        .flatMap(refs -> {
                            // 先去重出「这篇还没有的」再判断要不要写：解析结果非空不等于有新增，
                            // 否则重复应用会把同一篇文章再写一遍并回报「已归类」，
                            // 幂等就形同虚设（还会白白撞一次乐观锁）
                            var existingCategories = spec.getCategories() == null
                                ? List.<String>of() : spec.getCategories();
                            var existingTags = spec.getTags() == null
                                ? List.<String>of() : spec.getTags();
                            var newCategories = refs.getT1().stream()
                                .filter(value -> !existingCategories.contains(value))
                                .toList();
                            var newTags = refs.getT2().stream()
                                .filter(value -> !existingTags.contains(value))
                                .toList();
                            if (newCategories.isEmpty() && newTags.isEmpty()) {
                                return Mono.just(result(name, title, "SKIPPED", "",
                                    List.of(), "这篇文章已经有相同的分类或标签，无需重复写入"));
                            }
                            spec.setCategories(append(existingCategories, newCategories));
                            spec.setTags(append(existingTags, newTags));
                            return client.update(post)
                                .map(updated -> {
                                    log.info("[UniHalo][Digest] 旧文章归类：name={} 追加分类={} 标签={}",
                                        name, newCategories, newTags);
                                    return result(name, title, "APPLIED",
                                        firstOf(newCategories), newTags, "已追加，原有分类标签未改动");
                                });
                        });
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("[UniHalo][Digest] 旧文章归类：文章已不存在，跳过 name={}", name);
                    return result(name, "", "SKIPPED", "", List.of(), "文章不存在或已被删除");
                })))
            .retryWhen(Retry.backoff(UPDATE_RETRIES, Duration.ofMillis(200))
                .filter(PostTaxonomyService::isConcurrentModification)
                .doBeforeRetry(signal -> log.info(
                    "[UniHalo][Digest] 旧文章归类：文章正被并发修改，重新读取后再写入 name={}（第 {} 次）",
                    name, signal.totalRetries() + 1)))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 旧文章归类失败 name={}：{}", name,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
                return Mono.just(result(name, "", "FAILED", "", List.of(),
                    "写入失败：" + describe(ex)));
            });
    }

    /**
     * 解析要写入的分类。
     *
     * <p>只认两种值：六个主题 key（不存在就自动创建，与日报链路一致），
     * 或者站点上已存在的分类名。其余一律忽略——这个接口虽然只有站长能调，
     * 但「按请求体随意造分类」这种事没有理由留着。</p>
     */
    private Mono<List<String>> resolveCategoryRefs(String requested) {
        if (!StringUtils.hasText(requested)) {
            return Mono.just(List.of());
        }
        var raw = requested.trim();
        var key = PostClassifyPromptHelper.normalizeCategory(raw);
        if (StringUtils.hasText(key)) {
            return taxonomy.resolveOrCreateCategories(List.of(key));
        }
        return taxonomy.resolveExistingCategories(List.of(raw))
            .doOnNext(matched -> {
                if (matched.isEmpty()) {
                    log.info("[UniHalo][Digest] 旧文章归类：分类「{}」既不是主题 key 也不存在于站点，已忽略",
                        raw);
                }
            });
    }

    /** 解析要写入的标签：关键词先清理去重限量，再交给分类服务建/复用。 */
    private Mono<List<String>> resolveTagRefs(List<String> keywords) {
        var wanted = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        if (keywords != null) {
            for (var keyword : keywords) {
                if (!StringUtils.hasText(keyword) || wanted.size() >= MAX_TAGS_PER_POST) {
                    continue;
                }
                var trimmed = keyword.trim();
                if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                    wanted.add(trimmed);
                }
            }
        }
        if (wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        return taxonomy.resolveOrCreateTags(wanted);
    }

    // ── 组装 ──

    private PostClassifyCandidateDTO toCandidate(Post post, Map<String, String> categoryLabels,
        Map<String, String> tagLabels) {
        var spec = post.getSpec();
        return PostClassifyCandidateDTO.builder()
            .name(post.getMetadata().getName())
            .title(nullToEmpty(spec.getTitle()))
            .excerpt(excerptOf(post))
            .categories(labelOf(spec.getCategories(), categoryLabels))
            .tags(labelOf(spec.getTags(), tagLabels))
            .publishTime(spec.getPublishTime())
            .suggestedKeywords(List.of())
            .build();
    }

    /**
     * 把一批判定结果贴到候选条目上。
     *
     * <p>开关的语义与发布链路完全一致（设置里那两项写的就是「自动创建分类/标签」），
     * 所以关掉开关时这里就不给建议，页面会提示去哪开——而不是照算不误、
     * 让站长以为设置项不生效。</p>
     */
    private static List<PostClassifyCandidateDTO> applyVerdicts(
        List<PostClassifyCandidateDTO> candidates, String aiText, DigestConfigDTO config) {
        var verdicts = PostClassifyPromptHelper.parse(aiText, candidates.size());
        var result = new ArrayList<PostClassifyCandidateDTO>(candidates.size());
        for (var i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            var verdict = verdicts.get(i);
            var category = config.isAutoCategoryEnabled() ? verdict.category() : "";
            var keywords = config.isAutoTagEnabled() ? verdict.keywords() : List.<String>of();
            candidate.setSuggestedCategory(category);
            candidate.setSuggestedCategoryLabel(
                StringUtils.hasText(category) ? DigestRenderer.categoryLabel(category) : "");
            candidate.setSuggestedKeywords(keywords);
            candidate.setNote(noteOf(config, verdict));
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static String noteOf(DigestConfigDTO config, PostClassifyPromptHelper.PostClassifyVerdict verdict) {
        var notes = new ArrayList<String>();
        if (!config.isAutoCategoryEnabled()) {
            notes.add("设置里关闭了「自动分类」，未给出主题建议");
        } else if (!verdict.hasCategory()) {
            notes.add("AI 没给出有效主题，可手工指定或先跳过");
        } else if ("other".equals(verdict.category())) {
            notes.add("AI 判定不属于前五类，归入「其它」");
        }
        if (!config.isAutoTagEnabled()) {
            notes.add("设置里关闭了「自动打标」，未提取关键词");
        }
        return String.join("；", notes);
    }

    // ── 数据读取 ──

    /**
     * 已发布的文章。
     *
     * <p>一次读全量再在内存里过滤：用的是不带字段选择器的 {@code listAll}，
     * 与「支付看板」里的文章统计同一条路。这条链路只在站长点按钮时跑，
     * 不是热路径，全量的代价可以接受。</p>
     */
    private Mono<List<Post>> loadPublishedPosts() {
        return client.listAll(Post.class, new ListOptions(), null)
            .filter(PostTaxonomyService::isPublished)
            .collectList();
    }

    private static boolean isPublished(Post post) {
        if (post == null || post.getMetadata() == null || post.getSpec() == null) {
            return false;
        }
        var spec = post.getSpec();
        if (Boolean.TRUE.equals(spec.getDeleted()) || !Boolean.TRUE.equals(spec.getPublish())) {
            return false;
        }
        // 可见性为空时按公开处理：老数据可能没有这个字段，不该因此被漏掉
        return spec.getVisible() == null || spec.getVisible() == Post.VisibleEnum.PUBLIC;
    }

    /**
     * 是否是「AI 日报生成」的文章。
     *
     * <p>label 与 annotation 任一命中即可：label 是给查询用的对外标记，
     * annotation 是插件写入时打的来源标记，早版本只写了 annotation。</p>
     */
    private static boolean isDigestGenerated(Post post) {
        var metadata = post.getMetadata();
        if (metadata == null) {
            return false;
        }
        var labels = metadata.getLabels();
        if (labels != null && labels.containsKey(DigestPostWriter.DIGEST_LABEL)) {
            return true;
        }
        var annotations = metadata.getAnnotations();
        return annotations != null
            && annotations.containsKey(DigestPostWriter.DIGEST_MARKER_ANNO);
    }

    /**
     * 是否已经有分类——只要有任意一个分类就不再分析。
     *
     * <p>这里刻意不判断「是不是六个主题分类之一」：站长把文章归进他自己的分类
     * （记录 / 手册 / 开箱……）同样是「已经分好类」，机器不该再重新判一遍，
     * 更不该往他整理好的分类后面再追加一个主题分类。</p>
     */
    private static boolean hasAnyCategory(Post post) {
        var categories = post.getSpec().getCategories();
        return categories != null && !categories.isEmpty();
    }

    private static Instant publishTimeOf(Post post) {
        var spec = post.getSpec();
        if (spec == null || spec.getPublishTime() == null) {
            // 没有发布时间的排在最后，而不是当成「最新」
            return Instant.EPOCH;
        }
        return spec.getPublishTime();
    }

    /**
     * 摘要：优先站长手填的（{@code spec.excerpt.raw}），其次是 Halo 解析出来的
     * （{@code status.excerpt}，内容为空时由协调器自动生成）。
     *
     * <p>两处都取不到时留空，提示词里还有标题可用——旧文章往往比新文章更缺摘要，
     * 但标题对「这篇属于哪一类」通常已经够用，不必为此额外去读正文快照。</p>
     */
    private static String excerptOf(Post post) {
        var spec = post.getSpec();
        var excerpt = spec.getExcerpt();
        var raw = excerpt == null ? "" : excerpt.getRaw();
        if (!StringUtils.hasText(raw)) {
            var status = post.getStatus();
            raw = status == null ? "" : status.getExcerpt();
        }
        return sanitize(raw);
    }

    /** 去掉 HTML 标签与多余空白：摘要里混着标签会让 AI 分心，也让预览表不好看 */
    static String sanitize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var plain = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= PREVIEW_TEXT_LIMIT
            ? plain : plain.substring(0, PREVIEW_TEXT_LIMIT);
    }

    private static List<String> labelOf(List<String> names, Map<String, String> labels) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
            .filter(StringUtils::hasText)
            .map(name -> labels.getOrDefault(name, name))
            .toList();
    }

    /** 追加并去重（保留原有顺序，原有的在前） */
    private static List<String> append(List<String> existing, List<String> added) {
        var merged = new ArrayList<String>();
        if (existing != null) {
            merged.addAll(existing);
        }
        for (var value : added) {
            if (!merged.contains(value)) {
                merged.add(value);
            }
        }
        return merged;
    }

    private static String firstOf(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private static int countOf(List<PostClassifyApplyResponse.Item> items, String state) {
        return (int) items.stream().filter(item -> state.equals(item.getState())).count();
    }

    private static PostClassifyApplyResponse.Item result(String name, String title, String state,
        String category, List<String> tags, String message) {
        return PostClassifyApplyResponse.Item.builder()
            .name(name)
            .title(title)
            .state(state)
            .category(category)
            .tags(tags)
            .message(message)
            .build();
    }

    private static String describe(Throwable error) {
        var message = error.getMessage();
        return StringUtils.hasText(message) ? message : error.getClass().getSimpleName();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 是否是并发修改冲突：Halo 乐观锁失败的原文是
     * {@code Failed to update versioned entity ... Was the entity updated or deleted concurrently?}，
     * 其专用异常类在 application 包里，插件编译期拿不到，只能识别消息
     * （与 {@code DigestPostWriter} 里的判断保持一致）。
     */
    private static boolean isConcurrentModification(Throwable error) {
        var message = error.getMessage();
        return message != null && message.contains("concurrently");
    }

    private Mono<DigestConfigDTO> loadConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }
}
