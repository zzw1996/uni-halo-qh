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

    private Mono<AiContentFeedDTO> doGenerate(String openid, String date,
        AiContentConfigDTO config) {
        return Mono.zip(preferenceService.load(openid),
                digestPublicService.candidateArticles(config.getWindowDays()))
            .flatMap(tuple -> {
                var preference = tuple.getT1();
                var candidates = tuple.getT2();
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
            preference.getSources(), preference.getKeywords());
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
