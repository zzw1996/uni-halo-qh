package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.scheme.PushRecord;
import pro.uhalo.uni.scheme.PushState;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.scheme.UserPoints;
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
import run.halo.app.plugin.ReactiveSettingFetcher;

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
    private final ReactiveSettingFetcher settingFetcher;
    private final PushSubscriptionService subscriptionService;
    private final WxSubscribeMessageService messageService;
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
            bizKey, page, message)
            .map(attempt -> attempt.outcome() == PushOutcome.SENT ? 1 : 0);
    }

    // ── 简报线 ──

    /** 读取推送配置（失败按未开启处理）：立即推送与定时生成都要用同一份口径 */
    public Mono<PushConfigDTO> loadPushConfigSafely() {
        return settingFetcher.get(PushConfigHelper.groupName())
            .map(PushConfigHelper::parse)
            .defaultIfEmpty(PushConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Push] 读取推送配置失败，按未开启处理：{}", ex.getMessage());
                return Mono.just(PushConfigHelper.defaults());
            });
    }

    /**
     * 推送「你的定制简报已生成」。
     *
     * <p>与旧实现的关键差别：旧版在「站长日报就绪」时就群发给所有 AI 定制用户——用户点进去
     * 可能还要等生成；现在只在<b>该用户自己的简报已生成</b>时推送，消息里带真实条数，
     * 点开就是成品。</p>
     *
     * <p>额度不足（一次性订阅没授权过 / 用完了）时不发送也不报错——调用方据 outcome
     * 给出确切原因，而不是一律说成「额度不足」。</p>
     *
     * @param manual true = 用户在页面里点的「立即推送」：只看额度，不受用户总开关与
     *               「今天已经推过」的幂等限制之外的影响
     */
    public Mono<PushAttempt> pushAiContentReady(String openid, PushConfigDTO config,
        AiContentIssue issue, AiContentPreferenceDTO preference, boolean manual) {
        if (!StringUtils.hasText(openid) || issue == null) {
            return Mono.just(PushAttempt.of(PushOutcome.SEND_FAILED, "缺少推送目标或简报"));
        }
        var date = StringUtils.hasText(issue.getDate())
            ? issue.getDate() : LocalDate.now(ZONE).toString();
        var bizKey = "aidigest:" + date;
        return subscriptionService.load(openid.trim()).flatMap(subscription -> {
            var quota = PushSubscriptionService.quotaOf(subscription,
                PushConfigHelper.KIND_AI_DIGEST);
            if (quota <= 0) {
                log.info("[UniHalo][Push] 用户没有简报推送额度，跳过：openid={}", mask(openid));
                return Mono.just(PushAttempt.of(PushOutcome.NO_QUOTA,
                    "剩余额度 0 条"));
            }
            // 自动推送尊重用户的总开关；手动点击是一次明确请求，只看额度
            if (!manual && Boolean.FALSE.equals(subscription.getEnabled())) {
                log.info("[UniHalo][Push] 用户已关闭推送，定时线跳过：openid={}", mask(openid));
                return Mono.just(PushAttempt.of(PushOutcome.DISABLED, "用户已关闭推送"));
            }
            return alreadyPushed(openid.trim(), PushConfigHelper.KIND_AI_DIGEST, bizKey)
                .flatMap(exists -> exists
                    ? Mono.just(PushAttempt.of(PushOutcome.ALREADY_PUSHED,
                        "今天这份简报已经推送过"))
                    : loadState().flatMap(state -> sendAiContentReady(config, state, subscription,
                        issue, preference, bizKey)));
        });
    }

    private Mono<PushAttempt> sendAiContentReady(PushConfigDTO config, PushState state,
        PushSubscription target, AiContentIssue issue, AiContentPreferenceDTO preference,
        String bizKey) {
        var items = AiContentFeedService.parseItems(issue.getItems());
        var count = items.isEmpty() && issue.getMatchedCount() != null
            ? issue.getMatchedCount() : items.size();
        var topics = topicsOf(preference);
        var message = WxSubscribeMessageService.Message.of(
            "你的定制简报已生成",
            count > 0 ? count + " 条内容 · " + topics : "点击看看今天为你挑的内容",
            issue.getCreatedAt() == null ? Instant.now() : issue.getCreatedAt(),
            "",
            "点击查看你的专属简报");
        return deliver(config, state, target, PushConfigHelper.KIND_AI_DIGEST,
            PushConfigHelper.templateOf(config, PushConfigHelper.KIND_AI_DIGEST), bizKey,
            PAGE_AI_CONTENT, message, config.aiDigestFieldMap());
    }

    /** 一次推送尝试的结果：发出去了，或是因为哪一个原因没发出去 */
    public record PushAttempt(PushOutcome outcome, String detail) {

        static PushAttempt of(PushOutcome outcome, String detail) {
            return new PushAttempt(outcome, detail == null ? "" : detail);
        }

        public boolean sent() {
            return outcome == PushOutcome.SENT;
        }
    }

    /** 推送没能发出的原因（分开是为了给用户可操作的话，而不是一律「额度不足」） */
    public enum PushOutcome {
        /** 已发出 */
        SENT,
        /** 没有可用额度：需要用户去微信里授权一次 */
        NO_QUOTA,
        /** 今天这份已经推送过（幂等键撞名） */
        ALREADY_PUSHED,
        /** 整站当日发送量到顶 */
        DAILY_LIMIT,
        /** 用户关闭了自动推送 */
        DISABLED,
        /** 微信侧发送失败（模板/网络/字段映射等原因） */
        SEND_FAILED
    }

    /**
     * 消息里露出的主题名：优先用户手输的定制主题，没有就退到分类/来源，再没有就说「你的关注方向」。
     *
     * <p>thing 字段只有 20 字，这里只取第一个主题——超长会由发送侧统一截断。</p>
     */
    private static String topicsOf(AiContentPreferenceDTO preference) {
        var topics = preference == null ? null : preference.getCustomTopics();
        if (topics != null && !topics.isEmpty() && StringUtils.hasText(topics.get(0))) {
            return topics.get(0).trim();
        }
        var categories = preference == null ? null : preference.getCategories();
        if (categories != null && !categories.isEmpty() && StringUtils.hasText(categories.get(0))) {
            return categories.get(0).trim();
        }
        return "你的关注方向";
    }

    /** 一次发送：先看整站日上限 → 调微信 → 按结果记账（扣额度 / 清零 / 记错误码） */
    private Mono<PushAttempt> deliver(PushConfigDTO config, PushState state,
        PushSubscription target, String kind, String templateId, String bizKey, String page,
        WxSubscribeMessageService.Message message) {
        return deliver(config, state, target, kind, templateId, bizKey, page, message,
            PushConfigDTO.FieldMap.NEW_POST_DEFAULT);
    }

    /** 同上，指定模板字段映射（简报模板的字段名由站长配置） */
    private Mono<PushAttempt> deliver(PushConfigDTO config, PushState state,
        PushSubscription target, String kind, String templateId, String bizKey, String page,
        WxSubscribeMessageService.Message message, PushConfigDTO.FieldMap fields) {
        var today = LocalDate.now(ZONE).toString();
        var sameDay = today.equals(state.getDailyDate());
        var sentToday = sameDay ? orZero(state.getDailySent()) : 0;
        if (sentToday >= config.getDailySendLimit()) {
            log.warn("[UniHalo][Push] 已达整站每日发送上限 {}，本轮停止发送"
                    + "（检查间隔 { } 分钟，可在「消息推送」设置里调整）",
                config.getDailySendLimit(), config.getCheckIntervalMinutes());
            return Mono.just(PushAttempt.of(PushOutcome.DAILY_LIMIT,
                "站点今日推送量已达上限"));
        }
        return messageService.send(target.getWxOpenid(), templateId, message, page,
                config.getMiniprogramState(), fields)
            .flatMap(result -> writeRecord(target.getWxOpenid(), kind, bizKey, page, result)
                .then(settle(target, kind, result))
                .flatMap(counted -> bumpDailyCounter(state, today, result)
                    .thenReturn(result.ok()
                        ? PushAttempt.of(PushOutcome.SENT, "")
                        // 微信给出的原因（43101 用户拒收/无额度、47003 模板字段不合法…）
                        : PushAttempt.of(result.quotaExhausted()
                                ? PushOutcome.NO_QUOTA : PushOutcome.SEND_FAILED,
                            describeSendFailure(result)))));
    }

    /** 把微信的失败回执压成一句能读的话（errorCode + 原始 message） */
    private static String describeSendFailure(WxSubscribeMessageService.SendResult result) {
        var code = result.errorCode() > 0 ? "错误码 " + result.errorCode() : "";
        var message = result.message();
        if (!code.isEmpty() && StringUtils.hasText(message)) {
            return code + "：" + message;
        }
        if (!code.isEmpty()) {
            return code;
        }
        return StringUtils.hasText(message) ? message : "微信未接受该条消息";
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

    /**
     * 审计记录：资源名固定，因此重复写入会撞名（并发/重跑都只留一条）。
     *
     * <p>写入失败<b>不能一律当成「已存在」</b>：这里曾经把「字段名踩了 Halo 的 kind」
     * 这类错误也吞成一句 info（详见 {@code PushRecord.sourceKind} 的注释），
     * 导致记录表一直是空的、幂等静默失效。现在按错误类型分开处理：
     * 撞名（已存在）静默跳过，其它错误按 warn 记下来。</p>
     */
    private Mono<Void> writeRecord(String openid, String kind, String bizKey, String page,
        WxSubscribeMessageService.SendResult result) {
        var record = new PushRecord();
        record.setWxOpenid(openid);
        record.setSourceKind(kind);
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
                if (isDuplicateNameError(ex)) {
                    log.info("[UniHalo][Push] 推送记录已存在，跳过重复写入：{}", ex.getMessage());
                } else {
                    // 其它失败要留痕：这条曾经被吞成 info，掩盖了「记录一条都没写进去」的事实
                    log.warn("[UniHalo][Push] 推送记录写入失败（不影响本次下发，但幂等会失效）：{}",
                        ex.getMessage(), ex);
                }
                return Mono.empty();
            });
    }

    /** 资源名冲突（同一件事重复写入）——这是唯一可以静默的情况 */
    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("already exists") || normalized.contains("duplicate")
            || normalized.contains("已存在");
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

    /** openid 是用户标识，日志里只留前几位 */
    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未知)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***"
            : trimmed.substring(0, 6) + "***";
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
