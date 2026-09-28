package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.scheme.PushRecord;
import pro.uhalo.uni.scheme.PushState;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.services.WxSubscribeMessageService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.DigestPreferenceMatcher;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推送派发：决定「今天该给谁发什么消息」。
 *
 * <p><b>两条线</b>：</p>
 * <ul>
 *   <li><b>新文章线</b>：按水位取新发布的文章 → 反查订阅了这些分类的用户 → 每人每天合并成一条；</li>
 *   <li><b>简报线</b>：今天的 AI 日报已发布成功 → 对有权益的用户推「今日精选已就绪」。
 *       文案里的「N 篇命中你的关注方向」用<b>纯筛选</b>算（零 AI 成本），用户点进去才生成简报。</li>
 * </ul>
 *
 * <p><b>三条自我约束</b>（一次性订阅的额度一次授权只有一条，浪费不起）：</p>
 * <ol>
 *   <li>没内容不推、不扣额度；</li>
 *   <li>每人每天每类最多一条——幂等键含日期，重复触发只会撞名（按资源名直接查，不做全表扫描）；</li>
 *   <li>额度为 0 静默跳过；整站日上限到顶则停止并把原因写进日志。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushDispatchService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 单轮最多处理多少篇新文章：防止停机很久后一次性刷屏 */
    private static final int MAX_ARTICLES_PER_RUN = 50;

    /** 单轮最多处理多少个用户（串行发送，避免长时间占用事件循环） */
    /** 读不到额度信息时的兜底上限：按免费额度算，宁可少推也不要越权多推 */
    private static final int FREE_QUOTA_FALLBACK = 5;

    private static final int MAX_USERS_PER_RUN = 200;

    private static final String PAGE_HOME = "pages/tabbar/home/home";
    private static final String PAGE_AI_CONTENT = "pagesA/ai-content/ai-content";
    private static final String PAGE_ARTICLE = "pagesA/article-detail/article-detail";

    private final ReactiveExtensionClient client;
    private final PushSubscriptionService subscriptionService;
    private final WxSubscribeMessageService messageService;
    private final DigestPersistence digestPersistence;
    private final DigestPublicService digestPublicService;
    private final AiContentEntitlementService entitlementService;
    private final pro.uhalo.uni.services.impl.SubscriptionQuotaService quotaService;

    // ── 新文章线 ──

    /**
     * 检查新文章并推送。
     *
     * @return 本轮成功发送条数
     */
    public Mono<Integer> dispatchNewPosts(PushConfigDTO config) {
        return loadState().flatMap(state -> newArticlesSince(config, state)
            .flatMap(articles -> articles.isEmpty() ? Mono.just(0)
                : deliverNewPosts(config, state, articles)));
    }

    /** 水位之后发布的、公众可见的文章（按发布时间升序，「第一篇」才有意义） */
    private Mono<List<Post>> newArticlesSince(PushConfigDTO config, PushState state) {
        var now = Instant.now();
        var fallback = now.minus(Duration.ofHours(config.getLookbackHours()));
        var watermark = state.getLastCheckedPublishTime();
        var since = watermark == null || watermark.isBefore(fallback) ? fallback : watermark;
        return client.listAll(Post.class, new ListOptions(), null)
            .filter(post -> isVisibleToPublic(post, now))
            .filter(post -> post.getSpec().getPublishTime().isAfter(since))
            .sort(Comparator.comparing(post -> post.getSpec().getPublishTime()))
            .take(MAX_ARTICLES_PER_RUN)
            .collectList();
    }

    private Mono<Integer> deliverNewPosts(PushConfigDTO config, PushState state,
        List<Post> articles) {
        var hitCategories = new LinkedHashSet<String>();
        for (var article : articles) {
            var categories = article.getSpec().getCategories();
            if (categories != null) {
                hitCategories.addAll(categories);
            }
        }
        if (hitCategories.isEmpty()) {
            // 有文章但都没分类：没法定向推送，仍然推进水位，避免下次重复扫描
            return advanceWatermark(state, articles).thenReturn(0);
        }
        return listSubscriptions()
            .flatMap(subscriptions -> reactor.core.publisher.Mono.zip(
                    subscriptionService.listAll(),
                    // 一次把订阅额度算完：逐个用户查账户会让一次推送变成几百次查询
                    quotaService.limitsByOpenid(subscriptions.stream()
                        .map(DigestSubscription::getWxOpenid)
                        .filter(java.util.Objects::nonNull)
                        .toList()))
                .map(tuple -> {
                var pushSubscriptions = tuple.getT1();
                var limits = tuple.getT2();
                var pushByOpenid = new LinkedHashMap<String, PushSubscription>();
                for (var item : pushSubscriptions) {
                    if (StringUtils.hasText(item.getWxOpenid())) {
                        pushByOpenid.put(item.getWxOpenid(), item);
                    }
                }
                var targets = new ArrayList<Target>();
                for (var subscription : subscriptions) {
                    if (Boolean.FALSE.equals(subscription.getEnabled())
                        || !StringUtils.hasText(subscription.getWxOpenid())) {
                        continue;
                    }
                    var effective = effectiveCategories(subscription, limits.getOrDefault(
                        subscription.getWxOpenid(), FREE_QUOTA_FALLBACK));
                    if (effective.isEmpty() || !effective.stream().anyMatch(hitCategories::contains)) {
                        continue;
                    }
                    var push = pushByOpenid.get(subscription.getWxOpenid());
                    // 没额度就不推：一次性订阅的额度用完了，推了也发不出去（43101）
                    if (!PushSubscriptionService.hasQuota(push,
                        PushConfigHelper.KIND_NEW_POST)) {
                        continue;
                    }
                    targets.add(new Target(push, effective));
                }
                return targets;
            }))
            .flatMap(targets -> {
                if (targets.isEmpty()) {
                    return advanceWatermark(state, articles).thenReturn(0);
                }
                log.info("[UniHalo][Push] 新文章 {} 篇命中 {} 个分类，{} 位订阅用户有额度待推送",
                    articles.size(), hitCategories.size(), targets.size());
                return pushToTargets(config, state, targets, articles)
                    .flatMap(sent -> advanceWatermark(state, articles).thenReturn(sent));
            });
    }

    /** 逐用户发送（串行）：合并当天所有命中文章成一条 */
    private Mono<Integer> pushToTargets(PushConfigDTO config, PushState state,
        List<Target> targets, List<Post> articles) {
        var today = LocalDate.now(ZONE).toString();
        var bizKey = "newpost:" + today;
        var templateId = PushConfigHelper.templateOf(config, PushConfigHelper.KIND_NEW_POST);
        return Flux.fromIterable(targets.stream().limit(MAX_USERS_PER_RUN).toList())
            .concatMap(target -> alreadyPushed(target.openid(), PushConfigHelper.KIND_NEW_POST,
                    bizKey)
                .flatMap(exists -> {
                    if (exists) {
                        // 今天已经收到过更新提醒：不再打扰（这是「每人每天一条」的落点）
                        return Mono.just(0);
                    }
                    var matched = articlesHitting(target.categories(), articles);
                    if (matched.isEmpty()) {
                        return Mono.just(0);
                    }
                    return sendNewPost(config, state, target, templateId, bizKey, matched);
                }))
            .reduce(0, Integer::sum);
    }

    private Mono<Integer> sendNewPost(PushConfigDTO config, PushState state, Target target,
        String templateId, String bizKey, List<Post> matched) {
        var first = matched.get(0);
        var categories = joiningCategories(matched);
        var message = WxSubscribeMessageService.Message.of(
            first.getSpec().getTitle(),
            matched.size() > 1 ? "共 " + matched.size() + " 篇新文章" : "你订阅的分类有新内容",
            first.getSpec().getPublishTime(),
            categories.isEmpty() ? "站点更新" : categories,
            "你订阅的分类有更新，点击查看");
        var page = matched.size() == 1
            ? PAGE_ARTICLE + "?name=" + first.getMetadata().getName() : PAGE_HOME;
        return deliver(config, state, target.push(), PushConfigHelper.KIND_NEW_POST, templateId,
            bizKey, page, message);
    }

    // ── 简报线 ──

    /**
     * 检查今天的 AI 日报是否已就绪并推送。
     *
     * <p>「就绪」= 今天有一条发布成功的日报记录。这样日报流水线不需要加钩子：
     * 调度器每轮看一眼，发布完成后的下一轮自然就会推。</p>
     */
    public Mono<Integer> dispatchAiDigest(PushConfigDTO config) {
        var today = LocalDate.now(ZONE).toString();
        return entitlementService.loadConfig()
            .flatMap(aiConfig -> digestPersistence.listByDate(today)
                .flatMap(reports -> {
                    var ready = reports.stream()
                        .anyMatch(report -> "SUCCESS".equalsIgnoreCase(report.getStatus())
                            && report.getPostCount() != null && report.getPostCount() > 0);
                    if (!ready) {
                        return Mono.just(0);
                    }
                    return loadState().flatMap(state ->
                        dispatchAiDigestToUsers(config, aiConfig, state, today));
                }));
    }

    private Mono<Integer> dispatchAiDigestToUsers(PushConfigDTO config,
        AiContentConfigDTO aiConfig, PushState state, String today) {
        var templateId = PushConfigHelper.templateOf(config, PushConfigHelper.KIND_AI_DIGEST);
        return digestPublicService.candidateArticles(aiConfig.getWindowDays())
            .flatMap(candidates -> Mono.zip(
                    subscriptionService.listAll(),
                    aiContentMatchCounts(candidates))
                .flatMap(tuple -> {
                    var quotas = tuple.getT1().stream()
                        .filter(item -> PushSubscriptionService.hasQuota(item,
                            PushConfigHelper.KIND_AI_DIGEST))
                        .toList();
                    var matchedByOpenid = tuple.getT2();
                    if (quotas.isEmpty() || matchedByOpenid.isEmpty()) {
                        return Mono.just(0);
                    }
                    var bizKey = "aidigest:" + today;
                    return Flux.fromIterable(quotas.stream().limit(MAX_USERS_PER_RUN).toList())
                        .concatMap(target -> {
                            var matched = matchedByOpenid.get(target.getWxOpenid());
                            if (matched == null) {
                                // 没有有效权益（或已过期）：不推
                                return Mono.just(0);
                            }
                            return alreadyPushed(target.getWxOpenid(),
                                    PushConfigHelper.KIND_AI_DIGEST, bizKey)
                                .flatMap(exists -> exists ? Mono.just(0)
                                    : sendAiDigest(config, state, target, templateId, bizKey,
                                        matched));
                        })
                        .reduce(0, Integer::sum);
                }));
    }

    private Mono<Integer> sendAiDigest(PushConfigDTO config, PushState state,
        PushSubscription target, String templateId, String bizKey, int matched) {
        var message = WxSubscribeMessageService.Message.of(
            matched > 0 ? "今日精选 · " + matched + " 篇" : "今日精选已更新",
            matched > 0 ? "命中你的关注方向" : "看看今天为你挑了什么",
            Instant.now(),
            "AI 定制内容",
            "点击查看你的专属简报");
        return deliver(config, state, target, PushConfigHelper.KIND_AI_DIGEST, templateId, bizKey,
            PAGE_AI_CONTENT, message);
    }

    /**
     * 有效权益用户 → 「今天命中几篇」。
     *
     * <p><b>纯筛选、零 AI 成本</b>：复用与生成简报同一套匹配规则（分类 3 / 来源 2 / 关键词），
     * 用户点进消息才真正生成导语——那条链路每天仍然只花一次 AI 调用。</p>
     */
    private Mono<Map<String, Integer>> aiContentMatchCounts(
        List<DigestPublicArticleDTO> candidates) {
        return client.listAll(UserPoints.class, new ListOptions(), null)
            .filter(account -> StringUtils.hasText(account.getWxOpenid())
                && AiContentConfigHelper.isActive(account.getAiContentExpireAt()))
            .take(MAX_USERS_PER_RUN)
            .collectList()
            .flatMap(accounts -> {
                if (accounts.isEmpty()) {
                    return Mono.just(Map.<String, Integer>of());
                }
                var openids = new HashSet<String>();
                accounts.forEach(account -> openids.add(account.getWxOpenid()));
                return client.listAll(AiContentPreference.class, new ListOptions(), null)
                    .filter(preference -> openids.contains(preference.getWxOpenid()))
                    .collectMap(AiContentPreference::getWxOpenid,
                        PushDispatchService::toPreferenceDto)
                    .map(preferences -> {
                        var result = new LinkedHashMap<String, Integer>();
                        for (var account : accounts) {
                            var preference = preferences.get(account.getWxOpenid());
                            var matched = preference == null
                                ? candidates.size()
                                : DigestPreferenceMatcher.apply(candidates,
                                    AiContentFeedService.asSubscription(preference)).size();
                            result.put(account.getWxOpenid(), matched);
                        }
                        return Map.copyOf(result);
                    });
            });
    }

    private static AiContentPreferenceDTO toPreferenceDto(AiContentPreference preference) {
        return AiContentPreferenceDTO.builder()
            .enabled(Boolean.TRUE.equals(preference.getEnabled()))
            .categories(orEmpty(preference.getCategories()))
            .sources(orEmpty(preference.getSources()))
            .keywords(orEmpty(preference.getKeywords()))
            .configured(true)
            .build();
    }

    // ── 共用发送链路 ──

    /** 一次发送：先看整站日上限 → 调微信 → 按结果记账（扣额度 / 清零 / 记错误码） */
    private Mono<Integer> deliver(PushConfigDTO config, PushState state, PushSubscription target,
        String kind, String templateId, String bizKey, String page,
        WxSubscribeMessageService.Message message) {
        var today = LocalDate.now(ZONE).toString();
        var sameDay = today.equals(state.getDailyDate());
        var sentToday = sameDay ? orZero(state.getDailySent()) : 0;
        if (sentToday >= config.getDailySendLimit()) {
            log.warn("[UniHalo][Push] 已达整站每日发送上限 {}，本轮停止发送"
                + "（检查间隔 { } 分钟，可在「消息推送」设置里调整）",
                config.getDailySendLimit(), config.getCheckIntervalMinutes());
            return Mono.just(0);
        }
        return messageService.send(target.getWxOpenid(), templateId, message, page,
                config.getMiniprogramState())
            .flatMap(result -> writeRecord(target.getWxOpenid(), kind, bizKey, page, result)
                .then(settle(target, kind, result))
                .flatMap(counted -> bumpDailyCounter(state, today, result)
                    .thenReturn(counted ? 1 : 0)));
    }

    /** 成功扣额度 / 43101 清零；其余情况不动额度（下次可能就好了） */
    private Mono<Boolean> settle(PushSubscription target, String kind,
        WxSubscribeMessageService.SendResult result) {
        if (result.ok()) {
            return subscriptionService.consume(target.getWxOpenid(), kind).thenReturn(true);
        }
        if (result.quotaExhausted()) {
            return subscriptionService.clearQuota(target.getWxOpenid(), kind).thenReturn(false);
        }
        return Mono.just(false);
    }

    private Mono<Void> bumpDailyCounter(PushState state, String today,
        WxSubscribeMessageService.SendResult result) {
        var sameDay = today.equals(state.getDailyDate());
        state.setDailyDate(today);
        state.setDailySent((sameDay ? orZero(state.getDailySent()) : 0) + (result.ok() ? 1 : 0));
        state.setDailySkipped((sameDay ? orZero(state.getDailySkipped()) : 0)
            + (result.ok() ? 0 : 1));
        return saveState(state);
    }

    /** 审计记录：资源名固定，因此重复写入会撞名（并发/重跑都只留一条） */
    private Mono<Void> writeRecord(String openid, String kind, String bizKey, String page,
        WxSubscribeMessageService.SendResult result) {
        var record = new PushRecord();
        record.setWxOpenid(openid);
        record.setKind(kind);
        record.setBizKey(bizKey);
        record.setPage(page);
        record.setStatus(result.ok() ? "SENT" : "FAILED");
        record.setErrorCode(result.errorCode());
        record.setMessage(truncate(result.message(), 200));
        record.setSentAt(Instant.now());
        var metadata = new Metadata();
        metadata.setName(recordName(openid, kind, bizKey));
        metadata.setCreationTimestamp(Instant.now());
        record.setMetadata(metadata);
        return client.create(record).then()
            .onErrorResume(ex -> {
                log.info("[UniHalo][Push] 推送记录已存在，跳过重复写入：{}", ex.getMessage());
                return Mono.empty();
            });
    }

    /** 是否已处理过：按资源名直接取（幂等键就是资源名，不做全表扫描） */
    private Mono<Boolean> alreadyPushed(String openid, String kind, String bizKey) {
        return client.fetch(PushRecord.class, recordName(openid, kind, bizKey))
            .map(record -> true)
            .onErrorResume(ex -> Mono.just(false))
            .defaultIfEmpty(false);
    }

    // ── 状态与水位 ──

    /** 读取调度状态（单例）；首次运行时创建 */
    Mono<PushState> loadState() {
        return client.fetch(PushState.class, PushState.RESOURCE_NAME)
            .onErrorResume(ex -> Mono.empty())
            .switchIfEmpty(Mono.defer(() -> {
                var state = new PushState();
                var metadata = new Metadata();
                metadata.setName(PushState.RESOURCE_NAME);
                metadata.setCreationTimestamp(Instant.now());
                state.setMetadata(metadata);
                state.setDailyDate(LocalDate.now(ZONE).toString());
                state.setDailySent(0);
                state.setDailySkipped(0);
                return client.create(state);
            }));
    }

    private Mono<Void> saveState(PushState state) {
        return client.update(state).then()
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Push] 调度状态写入失败（不影响本次推送）：{}", ex.getMessage());
                return Mono.empty();
            });
    }

    /** 推进水位到本批最新文章：即使这一批没发出去，也不该反复扫同一批 */
    private Mono<Void> advanceWatermark(PushState state, List<Post> articles) {
        var newest = articles.stream()
            .map(post -> post.getSpec().getPublishTime())
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        return Mono.defer(() -> {
            if (newest != null) {
                state.setLastCheckedPublishTime(newest);
            }
            state.setLastCheckAt(Instant.now());
            return saveState(state);
        });
    }

    // ── 订阅侧匹配 ──

    private Mono<List<DigestSubscription>> listSubscriptions() {
        return client.listAll(DigestSubscription.class, new ListOptions(), null).collectList();
    }

    /**
     * 用户生效的站点分类（自己 ≤5 + 继承 ≤3，去重后 ≤8）。
     *
     * <p>复用 {@code DigestSubscriptionService} 的同名规则而不是重写一遍：推送目标必须与
     * 「首页按订阅筛选」看到的一致，否则会出现「首页有这篇、却没提醒我」。</p>
     */
    private static List<String> effectiveCategories(DigestSubscription subscription, int maxOwn) {
        return DigestSubscriptionService.effectiveOf(
            DigestSubscriptionService.ownCategoriesOf(subscription),
            subscription.getInheritedCategories(), maxOwn);
    }

    private static List<Post> articlesHitting(List<String> effectiveCategories,
        List<Post> articles) {
        var wanted = new HashSet<>(effectiveCategories);
        return articles.stream()
            .filter(post -> post.getSpec().getCategories() != null
                && post.getSpec().getCategories().stream().anyMatch(wanted::contains))
            .toList();
    }

    private static String joiningCategories(List<Post> articles) {
        var names = new LinkedHashSet<String>();
        for (var article : articles) {
            var categories = article.getSpec().getCategories();
            if (categories != null) {
                names.addAll(categories);
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        var list = new ArrayList<>(names);
        return list.size() == 1 ? list.get(0) : list.get(0) + " 等 " + list.size() + " 个分类";
    }

    /** 与 {@code PostRelationServiceImpl.isVisibleToPublic} 同一口径：草稿/回收站/定时/仅登录都不算 */
    private static boolean isVisibleToPublic(Post post, Instant now) {
        if (post == null || post.getSpec() == null) {
            return false;
        }
        var spec = post.getSpec();
        if (spec.getPublishTime() == null || spec.getPublishTime().isAfter(now)) {
            return false;
        }
        if (Boolean.TRUE.equals(spec.getDeleted())) {
            return false;
        }
        if (!Boolean.TRUE.equals(spec.getPublish())) {
            return false;
        }
        var visible = spec.getVisible();
        return visible == null || visible == Post.VisibleEnum.PUBLIC;
    }

    private static String recordName(String openid, String kind, String bizKey) {
        return "push-" + sha256Hex(openid + ":" + kind + ":" + bizKey);
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 一次派发的目标：推送设置（额度）+ 该用户生效的分类 */
    private record Target(PushSubscription push, List<String> categories) {

        String openid() {
            return push.getWxOpenid();
        }
    }
}
