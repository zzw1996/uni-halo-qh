package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentFeedDTO;
import pro.uhalo.uni.dto.AiContentItemDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.utils.AiContentPromptHelper;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestPreferenceMatcher;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 「我的 AI 简报」：按用户偏好从已生成的日报内容里筛，再用一次 AI 调用写导语。
 *
 * <p><b>成本模型（本功能唯一需要盯住的数字）</b></p>
 * <ul>
 *   <li>筛选是纯函数、零成本：复用 {@link DigestPreferenceMatcher}（分类 3 / 来源 2 / 关键词权重），
 *       结果确定，同样的偏好每次算出来都一样；</li>
 *   <li>AI 只在「当天还没有这份简报」时调用一次，生成后落库 {@link AiContentIssue}，
 *       当天反复打开直接读库——因此<b>每位有效用户每天最多 1 次 AI 调用</b>；</li>
 *   <li>站长关掉导语生成（{@code aiIntroEnabled=false}）即退化为零 AI 成本的纯筛选。</li>
 * </ul>
 *
 * <p><b>失败策略：宁可没有导语，也不能没有简报。</b>AI 调用超时、返回非 JSON、字段缺失，
 * 都降级为「纯筛选结果 + 空导语」并照常落库——用户打开的是他今天那份列表，
 * 只是少了开头那段话。</p>
 *
 * <p><b>为什么不做定时预生成</b>：给所有付费用户每天定时生成会给不活跃用户白烧 AI。
 * 读时生成 + 当天缓存既省钱，又让「今天这份」在用户第一次打开时才诞生。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentFeedService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一次请求最多等 AI 多久；超时就先给纯筛选结果（用户不该为一段导语干等） */
    private static final int AI_TIMEOUT_SECONDS = 45;

    /** 主题检索「素材充足」的门槛：命中少于这个数就交给 AI 直接生成 */
    private static final int MIN_TOPIC_MATCHES = 3;

    /** AI 直接生成短文的字数区间：从导语字数推导，太短说不了事、太长超出简报体量 */
    private static final int MIN_GENERATE_WORDS = 200;
    private static final int MAX_GENERATE_WORDS = 800;

    private final ReactiveExtensionClient client;
    private final DigestPublicService digestPublicService;
    private final DigestAiClient aiClient;
    private final AiContentEntitlementService entitlementService;
    private final AiContentPreferenceService preferenceService;
    private final ReactiveSettingFetcher settingFetcher;

    /**
     * 同一用户当天的在途生成任务。
     *
     * <p>用 {@code Mono.cache()} 合并在途请求：用户连点两次、或前后端同时请求时，
     * 底层只会生成一份、也只会调用一次 AI（否则就是双倍成本 + 两份内容不一致）。</p>
     */
    private final ConcurrentHashMap<String, Mono<AiContentFeedDTO>> inFlight = new ConcurrentHashMap<>();

    /**
     * 读取（必要时生成）某个用户某天的简报。
     *
     * @param date 指定日期可回看历史；为空表示今天
     */
    public Mono<AiContentFeedDTO> feed(String openid, String date) {
        var day = StringUtils.hasText(date) ? date.trim() : LocalDate.now(ZONE).toString();
        return entitlementService.loadConfig().flatMap(config -> {
            if (!config.isEnabled() || !StringUtils.hasText(openid)) {
                return Mono.just(emptyFeed(config, day, false));
            }
            var normalizedOpenid = openid.trim();
            return entitlementService.isActive(normalizedOpenid).flatMap(active -> {
                if (!active) {
                    // 未开通/已过期：不给内容（也就不产生任何 AI 调用），由前端引导购买
                    return Mono.just(emptyFeed(config, day, false));
                }
                var isToday = LocalDate.now(ZONE).toString().equals(day);
                return findIssue(normalizedOpenid, day)
                    .map(issue -> toFeed(issue, config))
                    // 历史日期只读：不补生成，避免「翻到某一天就触发一次 AI 调用」
                    .switchIfEmpty(Mono.defer(() -> isToday
                        ? generate(normalizedOpenid, day, config)
                        : Mono.just(emptyFeed(config, day, true))));
            });
        });
    }

    /**
     * 作废当天那份简报：关注方向改了之后，用户期待立刻看到新方向的结果。
     *
     * <p>做成「作废」而不是「立刻重算」：重算发生在用户下一次打开时（读时生成的既定口径），
     * 因此改完方向但没打开页面的用户不会白烧一次 AI 调用。</p>
     *
     * <p>调用方（{@code AiContentService.save}）只在偏好<b>真的变了</b>时调它——
     * 否则反复提交同一份偏好就能把当天的 AI 额度反复消耗掉。</p>
     */
    public Mono<Boolean> invalidateToday(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(false);
        }
        var normalizedOpenid = openid.trim();
        var date = LocalDate.now(ZONE).toString();
        // 在途任务也要放掉：否则它可能在本方法删除之后又把旧方向的简报写回去
        inFlight.remove(normalizedOpenid + ":" + date);
        return client.fetch(AiContentIssue.class, resourceName(normalizedOpenid, date))
            .flatMap(client::delete)
            .then(Mono.just(true))
            .onErrorResume(ex -> {
                // 当天还没有生成过（fetch 报「不存在」是常态）：不是错误
                log.info("[UniHalo][AiContent] 无需作废当天简报（可能尚未生成）：openid={} {}",
                    mask(normalizedOpenid), ex.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * 确保某用户今天的简报已生成，返回它（已有则直接复用，零额外 AI 成本）。
     *
     * <p>供「立即推送」与每日定时批量生成调用：内部走的是与 {@link #feed} 同一套逻辑
     * （同一份 {@code inFlight} 合并 + 同一套幂等资源名），因此用户此刻正好打开页面
     * 也不会双跑 AI。</p>
     */
    public Mono<AiContentIssue> ensureToday(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }
        var normalized = openid.trim();
        var today = LocalDate.now(ZONE).toString();
        return findIssue(normalized, today)
            .switchIfEmpty(Mono.defer(() -> entitlementService.loadConfig()
                .flatMap(config -> entitlementService.isActive(normalized)
                    .flatMap(active -> active
                        ? generate(normalized, today, config)
                            .flatMap(feed -> findIssue(normalized, today))
                        : Mono.empty()))));
    }

    /**
     * 批量确保今天已生成（定时线用）。
     *
     * <p>候选池只查一次并复用给所有人——逐个用户各查一次日报，几十个用户就是几十次查询，
     * 这是批量生成唯一需要额外小心的地方。</p>
     *
     * @param openids 目标用户（调用方已按「开关开 + 整点匹配 + 权益有效 + 还有额度」筛过）
     */
    public Mono<Integer> ensureTodayBatch(List<String> openids) {
        if (openids == null || openids.isEmpty()) {
            return Mono.just(0);
        }
        var targets = openids.stream().filter(StringUtils::hasText)
            .map(String::trim).distinct().toList();
        if (targets.isEmpty()) {
            return Mono.just(0);
        }
        return entitlementService.loadConfig()
            .flatMap(config -> digestPublicService.candidateArticles(config.getWindowDays())
                .flatMap(candidates -> reactor.core.publisher.Flux.fromIterable(targets)
                    // 并发 2：AI 调用是瓶颈，太高只会互相拖慢且更容易触发上游限流
                    .flatMap(openid -> generateForUser(openid, config, candidates)
                        .onErrorResume(ex -> {
                            log.warn("[UniHalo][AiContent] 定时生成失败（跳过该用户）：openid={} {}",
                                mask(openid), ex.getMessage());
                            return Mono.just(false);
                        }), 2)
                    .filter(Boolean::booleanValue)
                    .count()
                    .map(Long::intValue)));
    }

    /** 单个用户的生成（候选池由调用方传入复用） */
    private Mono<Boolean> generateForUser(String openid, AiContentConfigDTO config,
        List<DigestPublicArticleDTO> candidates) {
        var today = LocalDate.now(ZONE).toString();
        return entitlementService.isActive(openid).flatMap(active -> {
            if (!active) {
                return Mono.just(false);
            }
            return generateWithCandidates(openid, today, config, candidates)
                .map(feed -> true)
                .defaultIfEmpty(false);
        });
    }

    /** 历史简报（按日期倒序，最多 limit 条） */
    public Mono<List<AiContentIssue>> listIssues(String openid, int limit) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(List.of());
        }
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid.trim())));
        return client.listAll(AiContentIssue.class, listOptions, null)
            .collectList()
            .map(issues -> issues.stream()
                .sorted((a, b) -> nullToEmpty(b.getDate()).compareTo(nullToEmpty(a.getDate())))
                .limit(Math.max(1, limit))
                .toList())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取历史简报失败：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    // ── 生成 ──

    private Mono<AiContentFeedDTO> generate(String openid, String date, AiContentConfigDTO config) {
        var key = openid + ":" + date;
        var task = inFlight.computeIfAbsent(key, ignored -> doGenerate(openid, date, config)
            // 任务结束立刻移除：否则该用户后续请求会一直拿到这一次的缓存结果
            .doFinally(signal -> inFlight.remove(key))
            .cache());
        return task;
    }

    /**
     * 同上，但候选池由调用方传入（批量场景查一次复用）。
     *
     * <p>同样走 {@code inFlight} 合并：与用户请求并发时只跑一次。</p>
     */
    private Mono<AiContentFeedDTO> generateWithCandidates(String openid, String date,
        AiContentConfigDTO config, List<DigestPublicArticleDTO> candidates) {
        var key = openid + ":" + date;
        return inFlight.computeIfAbsent(key, ignored -> doGenerate(openid, date, config, candidates)
            .doFinally(signal -> inFlight.remove(key))
            .cache());
    }

    private Mono<AiContentFeedDTO> doGenerate(String openid, String date,
        AiContentConfigDTO config) {
        return digestPublicService.candidateArticles(config.getWindowDays())
            .flatMap(candidates -> doGenerate(openid, date, config, candidates));
    }

    private Mono<AiContentFeedDTO> doGenerate(String openid, String date,
        AiContentConfigDTO config, List<DigestPublicArticleDTO> candidates) {
        return preferenceService.load(openid)
            .flatMap(preference -> {
                var topics = preference.getCustomTopics() == null
                    ? List.<String>of() : preference.getCustomTopics();
                if (!topics.isEmpty()) {
                    // 用户手输了定制主题：优先围绕它做「检索→重写/兜底生成」，
                    // 普通偏好筛选（分类/来源/关键词）退居其次
                    return generateWithTopics(openid, date, config, preference, candidates, topics);
                }
                var picked = select(candidates, preference, config);
                if (picked.isEmpty() || !config.isAiIntroEnabled()) {
                    if (picked.isEmpty()) {
                        log.info("[UniHalo][AiContent] 候选为空，生成空简报（不调用 AI）：openid={} "
                            + "date={} 候选池={} 条", mask(openid), date, candidates.size());
                    }
                    // 没有内容或站长关掉了导语：一次 AI 都不调用
                    return save(openid, date, "", picked, candidates.size(), false)
                        .map(saved -> toFeed(saved, config));
                }
                return enhance(picked, preference, config)
                    .flatMap(enhanced -> save(openid, date, enhanced.intro(), enhanced.items(),
                        candidates.size(), enhanced.aiGenerated()))
                    .map(saved -> toFeed(saved, config));
            })
            .onErrorResume(ex -> {
                // 生成失败也不能让页面打不开：给一份空简报并留下日志
                log.warn("[UniHalo][AiContent] 生成简报失败（返回空简报）：openid={} date={} {}",
                    mask(openid), date, ex.getMessage());
                return Mono.just(emptyFeed(config, date, true));
            });
    }

    /**
     * 带定制主题的生成：先检索、素材足够就 AI 重写，不足时（且站长允许）AI 直接生成。
     *
     * <p>「足够」的门槛是 {@value #MIN_TOPIC_MATCHES} 篇——主题命中的文章太少时，
     * 围绕它组织的简报会很空，宁可让 AI 按主题直接写一篇原创短文
     * （约束不编造事实，见 {@code AiContentPromptHelper.buildGeneratePrompt}）。</p>
     */
    private Mono<AiContentFeedDTO> generateWithTopics(String openid, String date,
        AiContentConfigDTO config, AiContentPreferenceDTO preference,
        List<DigestPublicArticleDTO> candidates, List<String> topics) {
        var matched = topicSearch(candidates, topics);
        log.info("[UniHalo][AiContent] 定制主题检索：openid={} date={} 主题={} 命中 {} 篇",
            mask(openid), date, String.join("、", topics), matched.size());
        if (matched.size() >= MIN_TOPIC_MATCHES) {
            // 素材充足：以主题命中的文章为准（它们比宽泛偏好筛选更贴合用户的输入），
            // AI 围绕主题重写导语与每篇理由——事实来自素材，观点是原创，防侵权口径与日报一致
            var picked = matched.stream().limit(config.getMaxItems())
                .map(AiContentFeedService::toItem)
                .toList();
            return enhance(picked, preference, config)
                .flatMap(enhanced -> save(openid, date, enhanced.intro(), enhanced.items(),
                    candidates.size(), enhanced.aiGenerated()))
                .map(saved -> toFeed(saved, config));
        }
        if (!config.isFallbackGenerateEnabled() || !config.isAiIntroEnabled()) {
            // 兜底生成被关掉：退回普通筛选，宁可内容没那么贴题也不凭空生成
            var picked = select(candidates, preference, config);
            return save(openid, date, "", picked, candidates.size(), false)
                .map(saved -> toFeed(saved, config));
        }
        return generateDirect(openid, date, config, preference, candidates, topics);
    }

    /**
     * AI 直接按定制主题生成一篇原创短文。
     *
     * <p>生成的条目放在简报最前面（它是用户这次输入最直接的回应）；
     * 普通偏好筛出的文章补在后面凑满 maxItems——简报不该因为主题冷门就只剩一段话。</p>
     */
    private Mono<AiContentFeedDTO> generateDirect(String openid, String date,
        AiContentConfigDTO config, AiContentPreferenceDTO preference,
        List<DigestPublicArticleDTO> candidates, List<String> topics) {
        var words = Math.min(MAX_GENERATE_WORDS,
            Math.max(MIN_GENERATE_WORDS, config.getIntroWords() * 2));
        var prompt = AiContentPromptHelper.buildGeneratePrompt(topics, words);
        return loadAiConfig()
            .flatMap(aiConfig -> aiClient.call(aiConfig, prompt, DigestAiTask.CONTENT)
                .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .map(reply -> AiContentPromptHelper.parseGenerated(reply.text()))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][AiContent] 主题生成失败，降级为普通筛选：{}", ex.getMessage());
                    return Mono.just(AiContentPromptHelper.Generated.empty());
                }))
            .defaultIfEmpty(AiContentPromptHelper.Generated.empty())
            .flatMap(generated -> {
                var items = new ArrayList<AiContentItemDTO>();
                if (generated.hasBody()) {
                    items.add(generatedItem(topics, generated));
                }
                var remain = Math.max(0, config.getMaxItems() - items.size());
                select(candidates, preference, config).stream().limit(remain).forEach(items::add);
                var aiGenerated = items.stream()
                    .anyMatch(item -> Boolean.TRUE.equals(item.getGenerated()));
                return save(openid, date, "", items, candidates.size(), aiGenerated)
                    .map(saved -> toFeed(saved, config));
            });
    }

    /** AI 生成内容的条目形态：没有原文出处，正文存在 body 里，前端渲染成「定制文章卡」 */
    private static AiContentItemDTO generatedItem(List<String> topics,
        AiContentPromptHelper.Generated generated) {
        var title = StringUtils.hasText(generated.title())
            ? generated.title() : "关于「" + String.join(" · ", topics) + "」";
        return AiContentItemDTO.builder()
            .title(title)
            .generated(true)
            .body(generated.body())
            .summary("根据你的定制主题原创生成，仅你自己可见")
            .build();
    }

    /**
     * 主题检索：定制主题在标题（×3）/关键词（×2）/摘要（×1）中的命中打分。
     *
     * <p>中文主题没有可靠的分词器，子串匹配就是够用的口径——主题本来就是用户
     * 从真实兴趣里提炼的短语（如「大模型 教育」），它出现在标题里基本就是命中。
     * 稳定排序保证同分时保持候选池的原有顺序（评分高的在前）。</p>
     */
    private static List<DigestPublicArticleDTO> topicSearch(
        List<DigestPublicArticleDTO> candidates, List<String> topics) {
        var cleaned = topics.stream()
            .filter(StringUtils::hasText)
            .map(topic -> topic.toLowerCase().trim())
            .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        record Scored(DigestPublicArticleDTO article, int score) {
        }
        return candidates.stream()
            .map(article -> {
                var title = lowerOrNull(article.getTitle());
                var summary = lowerOrNull(article.getSummary());
                var keywords = article.getKeywords() == null ? ""
                    : lowerOrNull(String.join(" ", article.getKeywords()));
                var score = 0;
                for (var topic : cleaned) {
                    if (title != null && title.contains(topic)) {
                        score += 3;
                    }
                    if (keywords.contains(topic)) {
                        score += 2;
                    }
                    if (summary != null && summary.contains(topic)) {
                        score += 1;
                    }
                }
                return score > 0 ? new Scored(article, score) : null;
            })
            .filter(java.util.Objects::nonNull)
            .sorted(java.util.Comparator.comparingInt(Scored::score).reversed())
            .map(Scored::article)
            .toList();
    }

    private static String lowerOrNull(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase() : null;
    }

    /**
     * 纯筛选：把用户偏好套到候选池上。
     *
     * <p>没配任何条件时取「窗口内全部精选」而不是空——用户刚买还没配就打开简报，
     * 看到一片空白会以为功能坏了。</p>
     */
    private static List<AiContentItemDTO> select(List<DigestPublicArticleDTO> candidates,
        AiContentPreferenceDTO preference, AiContentConfigDTO config) {
        var picked = DigestPreferenceMatcher.apply(candidates, asSubscription(preference));
        return picked.stream().limit(config.getMaxItems())
            .map(AiContentFeedService::toItem)
            .toList();
    }

    /**
     * 把付费偏好适配成匹配器认识的形状。
     *
     * <p>刻意复用同一套匹配规则（而不是为付费功能另写一份）：免费订阅与付费定制
     * 对「命中」的理解必须一致，否则两边给出的结果会互相矛盾。</p>
     */
    /** 公开给推送调度：算「今天命中几篇」必须与生成简报用同一套口径 */
    public static DigestSubscriptionDTO asSubscription(AiContentPreferenceDTO preference) {
        return DigestSubscriptionDTO.builder()
            .enabled(true)
            .categories(preference.getCategories())
            .sources(preference.getSources())
            .keywords(preference.getKeywords())
            .postCategories(List.of())
            .build();
    }

    /** AI 增强：一次调用拿导语 + 顺序 + 每篇推荐理由；任何失败都降级为纯筛选结果 */
    private Mono<Enhanced> enhance(List<AiContentItemDTO> picked, AiContentPreferenceDTO preference,
        AiContentConfigDTO config) {
        var preferences = AiContentPromptHelper.describe(preference.getCategories(),
            preference.getSources(), preference.getKeywords(), preference.getCustomTopics());
        var prompt = AiContentPromptHelper.buildIntroPrompt(picked, preferences,
            config.getIntroWords());
        return loadAiConfig()
            .flatMap(aiConfig -> aiClient.call(aiConfig, prompt, DigestAiTask.CONTENT)
                .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .map(reply -> applyVerdict(reply.text(), picked))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][AiContent] 导语生成失败，降级为纯筛选结果：{}", ex.getMessage());
                    return Mono.just(new Enhanced(picked, "", false));
                }))
            .defaultIfEmpty(new Enhanced(picked, "", false));
    }

    /** 应用模型返回的顺序与推荐理由；任何解析问题都退回原顺序 */
    private static Enhanced applyVerdict(String aiText, List<AiContentItemDTO> picked) {
        AiContentPromptHelper.Verdict verdict;
        try {
            verdict = AiContentPromptHelper.parse(aiText, picked.size());
        } catch (Exception ex) {
            // 非 JSON（模型拒答/输出被截断）：导语没有就没有，列表照给
            return new Enhanced(picked, "", false);
        }
        if (verdict.picks().isEmpty()) {
            return new Enhanced(picked, verdict.intro(), verdict.hasIntro());
        }
        var reordered = new ArrayList<AiContentItemDTO>(picked.size());
        var used = new HashSet<Integer>();
        for (var pick : verdict.picks()) {
            var item = picked.get(pick.index());
            item.setReason(pick.reasonOrEmpty());
            reordered.add(item);
            used.add(pick.index());
        }
        // 模型漏掉的条目补在后面：它们本来就是筛出来的，不该因为模型没提就消失
        for (var i = 0; i < picked.size(); i++) {
            if (!used.contains(i)) {
                reordered.add(picked.get(i));
            }
        }
        return new Enhanced(List.copyOf(reordered), verdict.intro(), verdict.hasIntro());
    }

    /**
     * 读 AI 通道配置。
     *
     * <p>复用「AI 日报」的接口地址/密钥/模型，不新增一套 AI 配置：站长已经配好的通道
     * 不该让他再填一遍，额度与计费口径也统一。</p>
     */
    private Mono<DigestConfigDTO> loadAiConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }

    // ── 落库与读取 ──

    private Mono<AiContentIssue> save(String openid, String date, String intro,
        List<AiContentItemDTO> items, int candidateCount, boolean aiGenerated) {
        return persist(issueOf(openid, date, intro, items, candidateCount, items.size(), aiGenerated))
            .doOnNext(saved -> {
                log.info("[UniHalo][AiContent] 简报已生成：openid={} date={} 条数={} 导语={}",
                    mask(openid), date, items.size(), StringUtils.hasText(intro) ? "有" : "无");
                preferenceService.touchIssueDate(openid, date).subscribe();
            });
    }

    private Mono<AiContentIssue> persist(AiContentIssue issue) {
        return client.create(issue).onErrorResume(ex -> {
            // 并发下可能已被另一个请求写入（资源名按 openid+日期派生）：回读复用
            log.info("[UniHalo][AiContent] 简报写入未成功（可能已存在），改为回读：{}", ex.getMessage());
            return findIssue(issue.getWxOpenid(), issue.getDate());
        });
    }

    private Mono<AiContentIssue> findIssue(String openid, String date) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(AiContentIssue.class, listOptions, null)
            .filter(issue -> date.equals(issue.getDate()))
            .next();
    }

    private static AiContentIssue issueOf(String openid, String date, String intro,
        List<AiContentItemDTO> items, int candidateCount, int matchedCount, boolean aiGenerated) {
        var issue = new AiContentIssue();
        issue.setWxOpenid(openid);
        issue.setDate(date);
        issue.setIntro(intro == null ? "" : intro);
        issue.setItems(serializeItems(items));
        issue.setCandidateCount(candidateCount);
        issue.setMatchedCount(matchedCount);
        issue.setAiGenerated(aiGenerated);
        var now = Instant.now();
        issue.setCreatedAt(now);
        var metadata = new Metadata();
        metadata.setName(resourceName(openid, date));
        metadata.setCreationTimestamp(now);
        issue.setMetadata(metadata);
        return issue;
    }

    private static AiContentFeedDTO toFeed(AiContentIssue issue, AiContentConfigDTO config) {
        var items = parseItems(issue.getItems());
        var matched = issue.getMatchedCount() == null ? items.size() : issue.getMatchedCount();
        return AiContentFeedDTO.builder()
            .enabled(true)
            .active(true)
            .date(issue.getDate())
            .intro(issue.getIntro())
            .aiGenerated(Boolean.TRUE.equals(issue.getAiGenerated()))
            .candidateCount(issue.getCandidateCount() == null ? 0 : issue.getCandidateCount())
            .matchedCount(matched)
            .windowDays(config.getWindowDays())
            .summary(summaryOf(issue, items, config))
            .items(items)
            .build();
    }

    /**
     * 一句话说明：解释这一份是怎么来的、为空时为什么为空。
     *
     * <p>用户花钱买的功能最怕「看不懂为什么没有内容」，因此空态要能区分
     * 「最近没有可筛的精选」与「偏好太窄」。前端还会用可选分类给出一键放宽的入口。</p>
     */
    private static String summaryOf(AiContentIssue issue, List<AiContentItemDTO> items,
        AiContentConfigDTO config) {
        var candidates = issue.getCandidateCount() == null ? 0 : issue.getCandidateCount();
        if (candidates == 0) {
            return "最近 " + config.getWindowDays() + " 天还没有可筛的精选内容，"
                + "等站长生成新一期日报后这里就会有内容";
        }
        if (items.isEmpty()) {
            return "最近 " + config.getWindowDays() + " 天的 " + candidates
                + " 篇精选里没有命中你关注的方向，可以放宽关注条件试试";
        }
        if (items.stream().anyMatch(item -> Boolean.TRUE.equals(item.getGenerated()))) {
            return "已按你的定制主题生成专属内容"
                + (items.size() > 1 ? "，并附上站内相关的精选文章" : "");
        }
        var noIntro = Boolean.TRUE.equals(issue.getAiGenerated())
            && StringUtils.hasText(issue.getIntro()) ? "" : "（本次未生成导语，以下是按你的方向筛出的结果）";
        return "从最近 " + config.getWindowDays() + " 天的 " + candidates + " 篇精选里筛出 "
            + items.size() + " 篇" + noIntro;
    }

    private static AiContentFeedDTO emptyFeed(AiContentConfigDTO config, String date,
        boolean active) {
        return AiContentFeedDTO.builder()
            .enabled(config.isEnabled())
            .active(active)
            .date(date)
            .intro("")
            .aiGenerated(false)
            .configured(false)
            .candidateCount(0)
            .matchedCount(0)
            .windowDays(config.getWindowDays())
            .summary(active ? "" : "还没有开通「AI 定制内容」，开通后即可按自己的方向收每日简报")
            .items(List.of())
            .build();
    }

    private static AiContentItemDTO toItem(DigestPublicArticleDTO article) {
        return AiContentItemDTO.builder()
            .title(article.getTitle())
            .cover(article.getCover())
            .category(article.getCategory())
            .categoryLabel(article.getCategoryLabel())
            .categoryEmoji(article.getCategoryEmoji())
            .keywords(article.getKeywords())
            .sourceName(article.getSourceName())
            .sourceUrl(article.getSourceUrl())
            .summary(article.getSummary())
            .postName(article.getPostName())
            .link(article.getLink())
            .matchedReason(article.getMatchedReason())
            .build();
    }

    private static String serializeItems(List<AiContentItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (Exception ex) {
            log.warn("[UniHalo][AiContent] 简报条目序列化失败，按空列表存储：{}", ex.getMessage());
            return "[]";
        }
    }

    /** 解析落库的条目（端点列历史摘要时也要用，因此是 public） */
    public static List<AiContentItemDTO> parseItems(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            var type = OBJECT_MAPPER.getTypeFactory()
                .constructCollectionType(List.class, AiContentItemDTO.class);
            return OBJECT_MAPPER.readValue(json, type);
        } catch (Exception ex) {
            log.warn("[UniHalo][AiContent] 简报条目解析失败，按空列表处理：{}", ex.getMessage());
            return List.of();
        }
    }

    /** 资源名：同一用户同一天只有一条（既是幂等，也是「每天最多一次 AI 调用」的落点） */
    static String resourceName(String openid, String date) {
        return "aci-" + sha256Hex(openid.trim() + ":" + date);
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }

    /** 增强结果：条目（可能已重排并带上理由）+ 导语 + 导语是否来自 AI */
    private record Enhanced(List<AiContentItemDTO> items, String intro, boolean aiGenerated) {
    }
}
