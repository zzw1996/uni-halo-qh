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
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.AiContentPromptHelper;
import pro.uhalo.uni.utils.DigestAiHelper;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.DigestPreferenceMatcher;
import reactor.core.publisher.Flux;
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
import java.util.Locale;
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
    /** 条目上标注的原文发布时间格式（也给 AI 看，所以取「月-日 时:分」而不是 ISO） */
    private static final java.time.format.DateTimeFormatter PUB_TIME_FMT =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 一次请求最多等 AI 多久；超时就先给纯筛选结果（用户不该为一段导语干等） */
    private static final int AI_TIMEOUT_SECONDS = 45;

    /** 主题检索「素材充足」的门槛：命中少于这个数就交给 AI 直接生成 */
    private static final int MIN_TOPIC_MATCHES = 3;

    /** AI 直接生成短文的字数区间：从导语字数推导，太短说不了事、太长超出简报体量 */
    private static final int MIN_GENERATE_WORDS = 200;
    private static final int MAX_GENERATE_WORDS = 800;

    /** 实时素材池的时间窗默认值（小时）：配置留空或非法时用它 */
    private static final int DEFAULT_LIVE_HOURS = 48;

    /**
     * 实时素材池的缓存时长。
     *
     * <p>抓一遍上百个源要几秒到几十秒，而「今天这份」每位用户只生成一次——
     * 缓存把它从「每人一次」降到「每半小时一次」，也让同一时段内的用户看到同一批素材。</p>
     */
    private static final Duration LIVE_POOL_TTL = Duration.ofMinutes(30);

    /**
     * 缓存过期后的宽限期：这段时间内先把旧素材给用户，刷新在后台跑。
     *
     * <p>过期就同步等一次抓取，会让每半小时的第一个用户无端多等十几秒——
     * 而他看到的素材本来就只是「上一轮」，差别可以忽略。</p>
     */
    private static final Duration LIVE_POOL_GRACE = Duration.ofHours(6);

    /** 送进 AI 挑选的素材条数上限（已按发布时间倒序，取最新的这些） */
    private static final int LIVE_PROMPT_LIMIT = 80;

    /** 生成状态（落库在 AiContentIssue.status） */
    private static final String STATUS_NONE = "NONE";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";

    /** 触发来源（写进审计记录，后台按它区分「用户点的 / 定时推的 / 立即推的」） */
    private static final String TRIGGER_MANUAL = "manual";
    private static final String TRIGGER_SCHEDULED = "scheduled";
    private static final String TRIGGER_PUSH = "push";

    /** 两次手动生成之间的最小间隔：防连点，不参与计费 */
    private static final Duration GENERATE_COOLDOWN = Duration.ofSeconds(15);

    /** 手动生成的限流键前缀 */
    private static final String GENERATE_RATE_KEY = "ai-content:generate:";

    /** 「不限次」的表示：特权用户在读模型里看到 -1 */
    private static final int UNLIMITED_CREDITS = -1;

    /** 生成阶段文案（前端直接显示，所以是给用户看的话） */
    private static final String STAGE_PREPARE = "正在抓取最新素材…";
    private static final String STAGE_MATERIAL_READY = "素材已就绪，AI 正在挑选与撰写…";
    /** 关键词检索有结果时的阶段 */
    private static final String STAGE_SEARCHED_AI = "已检索到相关报道，AI 正在挑选与撰写…";
    /** 抓不到实时素材、退回站内日报内容时的阶段（同样要调 AI 写导语与理由） */
    private static final String STAGE_LIGHT_AI = "素材有限，AI 正在按你的方向整理…";
    private static final String STAGE_SAVING = "正在保存简报…";
    private static final String STAGE_DONE = "已完成";
    private static final String STAGE_FAILED = "生成失败";

    /** 进度百分比：只是观感，不参与判定（改成不同数值不影响任何逻辑） */
    private static final int PROGRESS_START = 5;
    private static final int PROGRESS_MATERIAL = 35;
    private static final int PROGRESS_AI = 60;
    private static final int PROGRESS_SAVING = 90;

    /**
     * 「生成中」超过这个时长还没有新进展，就按失败处理。
     *
     * <p>服务重启会让在途任务消失、记录却留在 RUNNING——没有这道判断，用户会一直
     * 盯着一条永远不动的进度条，连重新生成的入口都没有。</p>
     */
    private static final Duration STALE_RUNNING = Duration.ofMinutes(5);

    /** 条目摘要的截断长度（AI 不可用时直接展示，也是海报/列表里的长度） */
    private static final int ITEM_SUMMARY_LIMIT = 160;

    private final ReactiveExtensionClient client;
    private final DigestPublicService digestPublicService;
    private final DigestAiClient aiClient;
    private final DigestFeedService digestFeedService;
    private final AiContentEntitlementService entitlementService;
    private final AiContentPreferenceService preferenceService;
    /** 生成记录（审计 + 今日次数计数） */
    private final AiContentGenerationLogService logService;
    /** 关键词联网检索：用户输入的关键词在内置科技源里命不中，必须先检索公开报道 */
    private final AiContentSearchService searchService;
    /** 生成次数（consumeCredit/loadCredits）也在权益服务里：它与「已开通」共用同一条账户记录 */
    private final RateLimiter rateLimiter;
    private final PrivilegeService privilegeService;
    private final ReactiveSettingFetcher settingFetcher;

    /**
     * 同一用户当天的在途生成任务。
     *
     * <p>用 {@code Mono.cache()} 合并在途请求：用户连点两次、或前后端同时请求时，
     * 底层只会生成一份、也只会调用一次 AI（否则就是双倍成本 + 两份内容不一致）。</p>
     */
    private final ConcurrentHashMap<String, Mono<AiContentFeedDTO>> inFlight = new ConcurrentHashMap<>();

    /** 实时素材池：最近一次成功的抓取结果（所有用户共享） */
    private final java.util.concurrent.atomic.AtomicReference<LivePool> livePool =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** 正在进行的抓取（并发请求合并成一次抓取，避免同时打上百个源） */
    private final java.util.concurrent.atomic.AtomicReference<Mono<List<AiContentItemDTO>>>
        liveInFlight = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 读取某个用户某天的简报（<b>只读，不生成</b>）。
     *
     * <p>生成改成由用户显式触发（{@link #startGeneration}）：页面第一次打开时不该有任何内容，
     * 也不该因为「打开页面」就烧掉一次 AI——用户看到的是他生成过的那一份，
     * 或者一份「还没生成，点按钮开始」的空态。</p>
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
                return loadQuota(normalizedOpenid, config)
                    .flatMap(quota -> findIssue(normalizedOpenid, day)
                        .map(issue -> toFeed(issue, config, isToday, quota))
                        // 今天还没有记录 = 用户还没点过生成；历史日期就是那天没生成
                        .switchIfEmpty(Mono.just(emptyFeed(config, day, true, quota))));
            });
        });
    }

    /**
     * 启动今天的生成（用户点「保存并生成今日简报」或空态里的「生成」按钮）。
     *
     * <p><b>立刻返回、后台跑完</b>：生成在服务端异步执行（页面退出、切后台都不影响），
     * 进度写进这一天的记录，前端轮询 {@link #feed} 就能看到阶段与百分比。</p>
     *
     * <p><b>已生成就不再生成</b>：同一天重复触发只是把现有那份回给调用方——
     * 「每位有效用户每天最多 1 次 AI 调用」这条成本口径靠它守住；
     * 改了关注方向会先作废当天记录（{@link #invalidateToday}），所以改完能重新生成。</p>
     */
    public Mono<AiContentFeedDTO> startGeneration(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("请先完成微信登录"));
        }
        var normalizedOpenid = openid.trim();
        var today = LocalDate.now(ZONE).toString();
        return entitlementService.loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return Mono.error(new WxAuthException("「AI 定制内容」暂未开放"));
            }
            return entitlementService.isActive(normalizedOpenid).flatMap(active -> {
                if (!active) {
                    return Mono.error(new WxAuthException("请先开通「AI 定制内容」再生成简报"));
                }
                // 防连点（不计费、不影响任何人，只是别让手抖变成两次 AI 调用）
                if (!rateLimiter.tryAcquire(GENERATE_RATE_KEY + normalizedOpenid, 1,
                    GENERATE_COOLDOWN)) {
                    return Mono.error(new WxAuthException("生成太频繁了，请稍等十几秒再试"));
                }
                return privilegeService.isPrivileged(normalizedOpenid).flatMap(privileged ->
                    (privileged ? Mono.just("") : quotaProblem(normalizedOpenid, config))
                        .flatMap(problem -> {
                            if (StringUtils.hasText(problem)) {
                                return Mono.error(new WxAuthException(problem));
                            }
                            if (isGenerating(normalizedOpenid, today)) {
                                // 上一次生成还在跑：直接回到进度（不重复扣次数、不再起一次 AI）
                                log.info("[UniHalo][AiContent] 已有在途生成，直接返回进度：openid={}",
                                    mask(normalizedOpenid));
                                return findIssue(normalizedOpenid, today)
                                    .map(issue -> toFeed(issue, config, true))
                                    .switchIfEmpty(Mono.error(new WxAuthException(
                                        "上一次生成还在进行中，请稍候")));
                            }
                            // 先扣次数再开始：次数是「本次生成」的凭证，跑失败不退还
                            // （失败原因多为上游 AI 不可用；重试由用户决定，
                            // 站长在后台能看到每一次失败的记录与原因）
                            var credit = privileged
                                ? Mono.just(UNLIMITED_CREDITS)
                                : entitlementService.consumeCredit(normalizedOpenid);
                            return credit.flatMap(left -> markRunning(normalizedOpenid, today)
                                .doOnNext(issue -> kickOff(normalizedOpenid, today, config,
                                    TRIGGER_MANUAL, left, privileged))
                                .map(issue -> toFeed(issue, config, true,
                                    quotaOf(left, config, privileged))));
                        }));
            });
        });
    }

    /**
     * 配额检查：每日上限 + 剩余次数；返回原因（空串 = 可以生成）。
     *
     * <p>返回原因而不是直接抛错，是因为三处调用方对「不够」的处理不同：
     * 手动生成要报给用户、定时线安静跳过、立即推送要报给用户。</p>
     */
    private Mono<String> quotaProblem(String openid, AiContentConfigDTO config) {
        return logService.countToday(openid).flatMap(today -> {
            var limit = config.getDailyGenerationLimit();
            if (limit > 0 && today >= limit) {
                return Mono.just("今天的生成次数已经用完了（每天最多 " + limit + " 次），明天再来");
            }
            return entitlementService.loadCredits(openid).map(credits -> credits > 0 ? ""
                : "生成次数已用完：可以再购买或积分兑换，每次购买按「有效天数 × 每天 "
                    + config.getCreditsPerDay() + " 次」赠送");
        });
    }

    /** 读当前的配额视图：剩余次数（特权为 -1）与今日已生成次数 */
    private Mono<Quota> loadQuota(String openid, AiContentConfigDTO config) {
        return privilegeService.isPrivileged(openid)
            .flatMap(privileged -> {
                if (privileged) {
                    return Mono.just(new Quota(UNLIMITED_CREDITS, 0, 0));
                }
                return Mono.zip(entitlementService.loadCredits(openid),
                        logService.countToday(openid))
                    .map(tuple -> new Quota(tuple.getT1(), tuple.getT2(),
                        config.getDailyGenerationLimit()))
                    .onErrorResume(ex -> Mono.just(new Quota(-1, 0, 0)));
            });
    }

    /** 读模型上的配额信息（前端显示「本期还可生成 N 次 · 今日 x/y」） */
    private static Quota quotaOf(int remaining, AiContentConfigDTO config, boolean privileged) {
        return new Quota(privileged ? UNLIMITED_CREDITS : remaining, 0,
            privileged ? 0 : config.getDailyGenerationLimit());
    }

    /** 配额视图：remaining 为 {@code -1} 表示不限 */
    private record Quota(int remaining, int todayUsed, int dailyLimit) {
    }

    /** 素材池：条目 + 是否来自关键词联网检索（决定提示词口径与说明文案） */
    private record Pool(List<AiContentItemDTO> items, boolean searched) {
    }

    /**
     * 后台跑一次生成；结果（完成/失败）都写回这一天的记录。
     *
     * <p>用 {@code subscribeOn(boundedElastic)} + {@code subscribe()} 把任务从请求线程上摘下来：
     * 请求早就返回了，生成该跑多久跑多久。任务本身仍走 {@code inFlight} 合并，
     * 因此「定时推送」与「用户手动生成」不会并发跑两次 AI。</p>
     */
    private void kickOff(String openid, String date, AiContentConfigDTO config, String trigger,
        int creditsAfter, boolean privileged) {
        runCharged(openid, date, config, trigger, creditsAfter, privileged)
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe(feed -> log.info(
                    "[UniHalo][AiContent] 后台生成完成：openid={} date={} 条目={} 剩余次数={}",
                    mask(openid), date, feed.getItems() == null ? 0 : feed.getItems().size(),
                    privileged ? "不限" : creditsAfter),
                ex -> log.warn("[UniHalo][AiContent] 后台生成失败：openid={} date={} {}",
                    mask(openid), date, ex.getMessage()));
    }

    /**
     * 跑一次生成并落审计记录。
     *
     * <p><b>次数必须由调用方先扣好</b>（{@code creditsAfter} 是扣减后的剩余）：
     * 扣次数与标 RUNNING 在同一段里完成，返回给用户的读模型里剩余次数才是准的。</p>
     */
    private Mono<AiContentFeedDTO> runCharged(String openid, String date,
        AiContentConfigDTO config, String trigger, int creditsAfter, boolean privileged) {
        return markRunning(openid, date)
            .then(generate(openid, date, config))
            .flatMap(feed -> logService.write(openid, date, trigger, STATUS_READY,
                    titlesOf(feed.getItems()),
                    feed.getItems() == null ? 0 : feed.getItems().size(),
                    privileged ? null : creditsAfter, !privileged, feed.getLiveHours(), "")
                .thenReturn(feed))
            .onErrorResume(ex -> {
                var reason = resolveMessage(ex);
                return logService.write(openid, date, trigger, STATUS_FAILED, List.of(), 0,
                        privileged ? null : creditsAfter, !privileged, null, reason)
                    .then(markFailed(openid, date, reason))
                    .then(Mono.error(ex));
            });
    }

    /** 审计记录里的标题清单（后台一眼看出生成了什么） */
    private static List<String> titlesOf(List<AiContentItemDTO> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().map(AiContentItemDTO::getTitle)
            .filter(StringUtils::hasText).toList();
    }

    private static boolean isReady(AiContentIssue issue) {
        return issue != null && (issue.getStatus() == null || STATUS_READY.equals(issue.getStatus()));
    }

    /**
     * 作废当天那份简报：关注方向改了之后，旧方向的内容不该再显示（也不该被当成「今天已生成」）。
     *
     * <p>做成「作废」而不是「立刻重算」：重算由用户点「保存并生成今日简报」触发，
     * 因此改完方向但没再点生成的用户不会白烧一次 AI 调用。</p>
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
     * <p>供「立即推送」与每日定时批量生成调用：走的是与手动生成同一套逻辑
     * （同一份 {@code inFlight} 合并 + 同一套幂等资源名 + 同一套状态记录），
     * 因此「用户此刻正好打开页面点了生成」也不会双跑 AI——两边拿到的是同一个任务。</p>
     */
    public Mono<AiContentIssue> ensureToday(String openid) {
        return ensureToday(openid, TRIGGER_SCHEDULED);
    }

    /**
     * 确保今天已有简报（推送线用）。
     *
     * <p>与手动生成的关键差别：<b>当天已有内容就直接复用</b>——推送不该再烧一次 AI，
     * 也不该再扣用户的次数。只有确实没有内容时才生成，此时同样要过配额。</p>
     *
     * @param trigger {@code push} = 用户在页面上点「立即推送」（配额不足时把原因给用户看）；
     *                {@code scheduled} = 定时线（配额不足时安静跳过，不打扰任何人）
     */
    public Mono<AiContentIssue> ensureToday(String openid, String trigger) {
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }
        var normalized = openid.trim();
        var today = LocalDate.now(ZONE).toString();
        var quiet = !TRIGGER_PUSH.equals(trigger);
        return findIssue(normalized, today)
            .flatMap(existing -> {
                if (isGenerating(normalized, today)) {
                    // 正在生成（比如用户刚在页面上点过）：等这一份，而不是把上一版推给他
                    return generationMono(normalized, today).then(findIssue(normalized, today));
                }
                if (hasResult(existing)) {
                    return Mono.just(existing);
                }
                // 有记录但没内容（上次失败/中断）：跑完它，推送需要成品
                return generateForPush(normalized, today, trigger, quiet)
                    .flatMap(feed -> findIssue(normalized, today));
            })
            .switchIfEmpty(Mono.defer(() -> entitlementService.loadConfig()
                .flatMap(config -> entitlementService.isActive(normalized)
                    .flatMap(active -> active
                        ? generateForPush(normalized, today, trigger, quiet)
                            .flatMap(feed -> findIssue(normalized, today))
                        : Mono.empty()))));
    }

    /**
     * 推送线的生成：过配额 → 扣一次次数 → 跑完。
     *
     * <p>配额不足时：定时线安静跳过（返回空，本轮不推这位用户），立即推送把原因回给用户。</p>
     */
    private Mono<AiContentFeedDTO> generateForPush(String openid, String date, String trigger,
        boolean quiet) {
        return entitlementService.loadConfig()
            .flatMap(config -> privilegeService.isPrivileged(openid)
                .flatMap(privileged -> {
                    if (privileged) {
                        return generateCharged(openid, date, config, trigger, true);
                    }
                    return quotaProblem(openid, config).flatMap(problem -> {
                        if (StringUtils.hasText(problem)) {
                            log.info("[UniHalo][AiContent] 推送线跳过（{}）：openid={}",
                                problem, mask(openid));
                            return quiet ? Mono.empty()
                                : Mono.error(new WxAuthException(problem));
                        }
                        return generateCharged(openid, date, config, trigger, false);
                    });
                }));
    }

    /** 该用户当天是否有在途生成任务（页面手动触发时会登记） */
    private boolean isGenerating(String openid, String date) {
        return inFlight.containsKey(openid + ":" + date);
    }

    /** 等当前这次生成跑完（与触发它的那次共用同一份 inFlight，不会另起一次 AI 调用） */
    private Mono<AiContentFeedDTO> generationMono(String openid, String date) {
        return entitlementService.loadConfig()
            .flatMap(config -> generate(openid, date, config));
    }

    /** 扣一次次数后跑完生成（特权用户不扣） */
    private Mono<AiContentFeedDTO> generateCharged(String openid, String date,
        AiContentConfigDTO config, String trigger, boolean privileged) {
        return (privileged ? Mono.just(UNLIMITED_CREDITS)
            : entitlementService.consumeCredit(openid))
            .flatMap(left -> runCharged(openid, date, config, trigger, left, privileged));
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
            .flatMap(config -> Flux.fromIterable(targets)
                // 并发 2：AI 调用是瓶颈，太高只会互相拖慢且更容易触发上游限流
                .flatMap(openid -> generateForUser(openid)
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][AiContent] 定时生成失败（跳过该用户）：openid={} {}",
                            mask(openid), ex.getMessage());
                        return Mono.just(false);
                    }), 2)
                .filter(Boolean::booleanValue)
                .count()
                .map(Long::intValue));
    }

    /**
     * 定时线：单个用户的生成。
     *
     * <p>当天已有内容就直接复用（不再烧 AI、不再扣次数）；没有才走推送线的生成
     * （配额不足时安静跳过，本轮不推这位用户）。</p>
     */
    private Mono<Boolean> generateForUser(String openid) {
        var today = LocalDate.now(ZONE).toString();
        return findIssue(openid, today)
            .filter(AiContentFeedService::hasResult)
            .map(issue -> false)
            .switchIfEmpty(Mono.defer(() -> entitlementService.isActive(openid)
                .flatMap(active -> active
                    ? generateForPush(openid, today, TRIGGER_SCHEDULED, true)
                        .map(feed -> true)
                        .defaultIfEmpty(false)
                    : Mono.just(false))));
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
    /**
     * 按「站内已发布的日报内容」生成（兜底路径）。
     *
     * <p>只在实时抓取拿不到任何素材时走这里：宁可内容旧一点，也不给付费用户空简报。</p>
     */
    private Mono<AiContentFeedDTO> doGenerate(String openid, String date,
        AiContentConfigDTO config) {
        // 素材来源按优先级：① 用户关键词的联网检索（娱乐/生活类关键词在内置科技源里必然命不中）
        // ② 实时素材池（内置源最近 N 小时的内容）③ 站内已发布的日报内容（兜底）
        return Mono.defer(() -> {
                report(openid, date, STAGE_PREPARE, PROGRESS_START);
                return preferenceService.load(openid);
            })
            .flatMap(preference -> buildPool(openid, date, config, preference)
                .flatMap(pool -> {
                    if (pool.items().isEmpty()) {
                        report(openid, date, STAGE_LIGHT_AI, PROGRESS_AI);
                        return doGenerateFromDigest(openid, date, config, preference);
                    }
                    report(openid, date, pool.searched() ? STAGE_SEARCHED_AI : STAGE_MATERIAL_READY,
                        pool.searched() ? PROGRESS_AI : PROGRESS_MATERIAL);
                    return generateFromLive(openid, date, config, preference, pool.items(),
                        pool.searched());
                }));
    }

    /**
     * 组装素材池。
     *
     * <p>用户自己输入了关键词/定制主题时先做<b>联网检索</b>（检索结果排在最前），
     * 再用实时素材池补足条数——只靠内置科技源的话，「邓紫棋」这类词一条都命不中，
     * 生成出来的内容自然与关键词无关（实测反馈过）。</p>
     */
    private Mono<Pool> buildPool(String openid, String date, AiContentConfigDTO config,
        AiContentPreferenceDTO preference) {
        var terms = searchTerms(preference);
        if (terms.isEmpty() || !config.isKeywordSearchEnabled()) {
            return livePool(config.getLiveHours()).map(live -> new Pool(live, false));
        }
        return loadAiConfig()
            .flatMap(aiConfig -> searchService.search(terms, config, aiConfig))
            .flatMap(found -> livePool(config.getLiveHours())
                .map(live -> new Pool(mergeSearchFirst(found, live, config), !found.isEmpty())));
    }

    /** 检索优先、实时素材补足；同一链接/标题只留一条 */
    private static List<AiContentItemDTO> mergeSearchFirst(List<AiContentItemDTO> searched,
        List<AiContentItemDTO> live, AiContentConfigDTO config) {
        var merged = new ArrayList<AiContentItemDTO>();
        var seen = new HashSet<String>();
        var limit = Math.max(1, config.getKeywordSearchMaxItems());
        for (var item : searched) {
            if (merged.size() >= limit) {
                break;
            }
            var key = keyOf(item);
            if (seen.add(key)) {
                merged.add(item);
            }
        }
        for (var item : live) {
            if (merged.size() >= limit) {
                break;
            }
            var key = keyOf(item);
            if (seen.add(key)) {
                merged.add(item);
            }
        }
        return List.copyOf(merged);
    }

    private static String keyOf(AiContentItemDTO item) {
        var link = item.getLink() == null ? "" : item.getLink().trim();
        if (!link.isEmpty()) {
            return link;
        }
        return item.getTitle() == null ? "" : item.getTitle().trim().toLowerCase(Locale.ROOT);
    }

    /** 用户自己输入的兴趣词：定制主题优先，其次关键词（分类是「站点内容」的维度，不拿去检索） */
    static List<String> searchTerms(AiContentPreferenceDTO preference) {
        if (preference == null) {
            return List.of();
        }
        var terms = new ArrayList<String>();
        if (preference.getCustomTopics() != null) {
            preference.getCustomTopics().stream().filter(StringUtils::hasText)
                .forEach(terms::add);
        }
        if (preference.getKeywords() != null) {
            preference.getKeywords().stream().filter(StringUtils::hasText).forEach(terms::add);
        }
        return List.copyOf(terms);
    }

    /**
     * 按「站内已发布的日报内容」生成（兜底路径）。
     *
     * <p>只在实时抓取拿不到任何素材时走这里：宁可内容旧一点，也不给付费用户空简报。</p>
     */
    private Mono<AiContentFeedDTO> doGenerateFromDigest(String openid, String date,
        AiContentConfigDTO config, AiContentPreferenceDTO preference) {
        return digestPublicService.candidateArticles(config.getWindowDays())
            .flatMap(candidates -> doGenerateFromDigestCandidates(openid, date, config,
                candidates));
    }

    /** 兜底路径：站内已发布的日报内容（调用方已确认实时素材为空） */
    private Mono<AiContentFeedDTO> doGenerateFromDigestCandidates(String openid, String date,
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
                    return save(openid, date, "", "", picked, candidates.size(), false, 0, false)
                        .map(saved -> toFeed(saved, config, true));
                }
                return enhance(picked, preference, config)
                    .flatMap(enhanced -> save(openid, date, enhanced.intro(),
                        enhanced.outro(), enhanced.items(), candidates.size(),
                        enhanced.aiGenerated(), 0, false))
                    .map(saved -> toFeed(saved, config, true));
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
                .flatMap(enhanced -> save(openid, date, enhanced.intro(), enhanced.outro(),
                    enhanced.items(), candidates.size(), enhanced.aiGenerated(), 0, false))
                .map(saved -> toFeed(saved, config, true));
        }
        if (!config.isFallbackGenerateEnabled() || !config.isAiIntroEnabled()) {
            // 兜底生成被关掉：退回普通筛选，宁可内容没那么贴题也不凭空生成
            var picked = select(candidates, preference, config);
            return save(openid, date, "", "", picked, candidates.size(), false, 0, false)
                .map(saved -> toFeed(saved, config, true));
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
                return save(openid, date, "", "", items, candidates.size(), aiGenerated, 0,
                    false)
                    .map(saved -> toFeed(saved, config, true));
            });
    }

    // ── 实时素材池（简报的主路径） ──

    /**
     * 实时素材池：最近 N 小时从各源抓到的内容，带「抓取时间」缓存。
     *
     * <p>三重保护，避免抓取变成用户可感知的等待：</p>
     * <ol>
     *   <li>TTL 内直接用缓存（默认 30 分钟内的抓取结果全站共享）；</li>
     *   <li>过了 TTL 但在宽限期内：先把旧素材给用户，刷新在后台跑；</li>
     *   <li>同时在抓时复用同一个 {@code Mono}，不会同时打上百个源。</li>
     * </ol>
     */
    private Mono<List<AiContentItemDTO>> livePool(Integer configureHours) {
        var hours = configureHours == null || configureHours <= 0
            ? DEFAULT_LIVE_HOURS : configureHours;
        var cached = livePool.get();
        var now = Instant.now();
        if (cached != null && cached.fetchedAt().isAfter(now.minus(LIVE_POOL_TTL))) {
            return Mono.just(cached.items());
        }
        var refresh = refreshLivePool(hours);
        if (cached != null && cached.fetchedAt().isAfter(now.minus(LIVE_POOL_GRACE))) {
            // 后台刷新：用户这次先用上一轮素材（订阅它只是为了让请求真的发出去）
            refresh.subscribe(items -> log.info("[UniHalo][AiContent] 实时素材池已后台刷新：{} 条",
                    items.size()),
                ex -> log.warn("[UniHalo][AiContent] 后台刷新实时素材失败：{}", ex.getMessage()));
            return Mono.just(cached.items());
        }
        return refresh;
    }

    /**
     * 预热实时素材池（插件启动时调用，后台跑，不阻塞任何请求）。
     *
     * <p>为什么值得预热：冷启动后的第一位用户要等一次全量抓取（上百个源），
     * 而小程序的请求超时只有 30 秒——预热把这段等待挪到没人等的时刻。</p>
     */
    public void warmLivePool() {
        refreshLivePool(DEFAULT_LIVE_HOURS)
            .subscribe(items -> log.info("[UniHalo][AiContent] 实时素材池预热完成：{} 条",
                    items.size()),
                ex -> log.warn("[UniHalo][AiContent] 实时素材池预热失败（首次请求会重试）：{}",
                    ex.getMessage()));
    }

    /** 真正去抓一次；失败返回缓存（可能为空），绝不抛错——抓取失败不该让简报打不开 */
    private Mono<List<AiContentItemDTO>> refreshLivePool(int hours) {
        var running = liveInFlight.get();
        if (running != null) {
            return running;
        }
        // 自引用：任务结束时要把自己从 liveInFlight 里摘掉，否则一次失败会长期挡住后续抓取
        var holder = new java.util.concurrent.atomic.AtomicReference<
            Mono<List<AiContentItemDTO>>>();
        var task = digestFeedService.fetchRecent(hours)
            .map(result -> toLiveItems(result.getArticles()))
            .doOnNext(items -> {
                if (!items.isEmpty()) {
                    livePool.set(new LivePool(items, Instant.now()));
                }
            })
            .onErrorResume(ex -> {
                var cachedItems = livePool.get();
                log.warn("[UniHalo][AiContent] 实时素材抓取失败，改用缓存/日报内容：{}",
                    ex.getMessage());
                return Mono.just(cachedItems == null ? List.<AiContentItemDTO>of()
                    : cachedItems.items());
            })
            .doFinally(signal -> {
                var self = holder.get();
                if (self != null) {
                    liveInFlight.compareAndSet(self, null);
                }
            })
            .cache();
        holder.set(task);
        liveInFlight.set(task);
        return task;
    }

    /** 抓取结果 → 简报素材：按发布时间倒序（最新的在前），并做成提示词友好的形状 */
    private static List<AiContentItemDTO> toLiveItems(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        return articles.stream()
            .filter(article -> StringUtils.hasText(article.getTitle()))
            .sorted(java.util.Comparator.comparing(DigestArticleDTO::getPubDate,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .map(AiContentFeedService::toLiveItem)
            .toList();
    }

    private static AiContentItemDTO toLiveItem(DigestArticleDTO article) {
        return AiContentItemDTO.builder()
            .title(article.getTitle().trim())
            // 摘要先用抓取到的原文摘要：AI 挑选时会改写成中文，AI 不可用时直接展示它
            .summary(DigestAiHelper.truncateForPrompt(article.getDescription(), ITEM_SUMMARY_LIMIT))
            .sourceName(article.getSourceName())
            .sourceUrl(article.getSourceUrl())
            .link(article.getLink())
            .publishedAt(formatPubDate(article.getPubDate()))
            .build();
    }

    /** 东八区的 {@code MM-dd HH:mm}；时间缺失时留空（前端不显示这一项） */
    private static String formatPubDate(Instant pubDate) {
        return pubDate == null ? ""
            : PUB_TIME_FMT.format(pubDate.atZone(ZONE));
    }

    /**
     * 实时简报的生成：一次 AI 调用同时完成「挑哪些 + 写中文标题/摘要/理由 + 导语」。
     *
     * <p>成本与旧实现一致（每位用户每天一次），但把「挑选」也交给了 AI——素材是从源里现抓的
     * 上百条，纯字符串匹配挑不出「同一件事的多条报道里哪条最值得看」。</p>
     */
    private Mono<AiContentFeedDTO> generateFromLive(String openid, String date,
        AiContentConfigDTO config, AiContentPreferenceDTO preference,
        List<AiContentItemDTO> pool, boolean searched) {
        var hours = config.getLiveHours() <= 0 ? DEFAULT_LIVE_HOURS : config.getLiveHours();
        var shortlist = pool.stream().limit(LIVE_PROMPT_LIMIT).toList();
        return pickWithAi(shortlist, preference, config, searched)
            .flatMap(enhanced -> {
                report(openid, date, STAGE_SAVING, PROGRESS_SAVING);
                return save(openid, date, enhanced.intro(), enhanced.outro(), enhanced.items(),
                    pool.size(), enhanced.aiGenerated(), hours, searched);
            })
            .map(saved -> toFeed(saved, config, true))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 简报生成失败（改用文本匹配）：openid={} {}",
                    mask(openid), ex.getMessage());
                var fallback = lexicalPick(shortlist, preference, config);
                return save(openid, date, "", "", fallback, pool.size(), false, hours, searched)
                    .map(saved -> toFeed(saved, config, true));
            });
    }

    /**
     * 让 AI 从实时素材里挑这一份；关掉 AI 或调用失败时退化为关键词/主题的字符串匹配。
     */
    private Mono<Enhanced> pickWithAi(List<AiContentItemDTO> candidates,
        AiContentPreferenceDTO preference, AiContentConfigDTO config, boolean searched) {
        if (!config.isAiIntroEnabled()) {
            // 站长关掉了 AI：纯文本匹配 + 最新优先（零 AI 成本）
            return Mono.just(Enhanced.plain(lexicalPick(candidates, preference, config)));
        }
        return loadAiConfig()
            .flatMap(aiConfig -> {
                var catalog = DigestCategoryCatalog.from(aiConfig.getDigestCategories());
                var preferences = AiContentPromptHelper.describe(preference.getCategories(),
                    preference.getKeywords(), preference.getCustomTopics());
                var prompt = AiContentPromptHelper.buildPickPrompt(candidates, preferences,
                    config.getMaxItems(), config.getIntroWords(), catalog, config.getOutroWords(),
                    searched);
                return aiClient.call(aiConfig, prompt, DigestAiTask.CONTENT)
                    .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                    .map(reply -> applyPicked(reply.text(), candidates, catalog, config))
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][AiContent] AI 挑选失败，改用文本匹配：{}",
                            ex.getMessage());
                        return Mono.just(Enhanced.plain(
                            lexicalPick(candidates, preference, config)));
                    });
            })
            .defaultIfEmpty(Enhanced.plain(lexicalPick(candidates, preference, config)));
    }

    /** 把 AI 的挑选结果落成条目：文案取 AI 改写的中文，事实（链接/时间）取原素材 */
    private static Enhanced applyPicked(String aiText, List<AiContentItemDTO> candidates,
        DigestCategoryCatalog catalog, AiContentConfigDTO config) {
        var picked = AiContentPromptHelper.parsePicked(aiText, candidates.size());
        if (picked.selections().isEmpty()) {
            // 没挑到任何一条：保留导语/结尾也没意义（页面只显示条目），交给调用方兜底
            return new Enhanced(List.of(), picked.intro(), picked.outroOrEmpty(), false);
        }
        var items = new ArrayList<AiContentItemDTO>();
        for (var selection : picked.selections()) {
            if (items.size() >= config.getMaxItems()) {
                break;
            }
            var base = candidates.get(selection.index());
            var entry = catalog.find(selection.categoryOrEmpty());
            items.add(AiContentItemDTO.builder()
                .title(firstNonBlank(selection.titleOrEmpty(), base.getTitle()))
                .summary(firstNonBlank(selection.summaryOrEmpty(), base.getSummary()))
                .reason(selection.reasonOrEmpty())
                .link(base.getLink())
                .publishedAt(base.getPublishedAt())
                .category(entry == null ? "" : entry.key())
                .categoryLabel(entry == null ? "" : entry.label())
                .categoryEmoji(entry == null ? "" : entry.emoji())
                .build());
        }
        return new Enhanced(List.copyOf(items), picked.intro(), picked.outroOrEmpty(), true);
    }

    /**
     * 纯文本兜底：定制主题 + 关键词在标题/摘要里的子串命中，未命中就按最新取前 N 条。
     *
     * <p>分类维度在这里无法参与（实时素材还没经过 AI 归类），因此分类由 AI 挑选那一步负责；
     * 这一路径的作用是「AI 不可用也不能给空简报」。</p>
     */
    private static List<AiContentItemDTO> lexicalPick(List<AiContentItemDTO> candidates,
        AiContentPreferenceDTO preference, AiContentConfigDTO config) {
        var terms = new ArrayList<String>();
        addTerms(terms, preference.getCustomTopics());
        addTerms(terms, preference.getKeywords());
        var picked = terms.isEmpty() ? candidates
            : candidates.stream().filter(item -> hitsAny(item, terms)).toList();
        return picked.stream().limit(Math.max(1, config.getMaxItems())).toList();
    }

    private static void addTerms(List<String> terms, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream().filter(StringUtils::hasText).map(String::trim).forEach(terms::add);
    }

    private static boolean hitsAny(AiContentItemDTO item, List<String> terms) {
        var haystack = (nullToEmpty(item.getTitle()) + " " + nullToEmpty(item.getSummary()))
            .toLowerCase();
        for (var term : terms) {
            if (haystack.contains(term.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : nullToEmpty(fallback);
    }

    /** 实时素材池：素材 + 抓取时间（TTL 判定用） */
    private record LivePool(List<AiContentItemDTO> items, Instant fetchedAt) {
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
     *
     * <p><b>来源维度已取消</b>（内容改为从实时抓取的源里挑，来源不再由用户指定）：
     * 这里显式传空，让历史上配过来源的偏好不再把内容筛空。</p>
     */
    public static DigestSubscriptionDTO asSubscription(AiContentPreferenceDTO preference) {
        return DigestSubscriptionDTO.builder()
            .enabled(true)
            .categories(preference.getCategories())
            .sources(List.of())
            .keywords(preference.getKeywords())
            .postCategories(List.of())
            .build();
    }

    /** AI 增强（兜底路径用）：一次调用拿导语 + 顺序 + 每篇推荐理由；任何失败都降级为纯筛选结果 */
    private Mono<Enhanced> enhance(List<AiContentItemDTO> picked, AiContentPreferenceDTO preference,
        AiContentConfigDTO config) {
        var preferences = AiContentPromptHelper.describe(preference.getCategories(),
            preference.getKeywords(), preference.getCustomTopics());
        var prompt = AiContentPromptHelper.buildIntroPrompt(picked, preferences,
            config.getIntroWords());
        return loadAiConfig()
            .flatMap(aiConfig -> aiClient.call(aiConfig, prompt, DigestAiTask.CONTENT)
                .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                .map(reply -> applyVerdict(reply.text(), picked))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][AiContent] 导语生成失败，降级为纯筛选结果：{}", ex.getMessage());
                    return Mono.just(new Enhanced(picked, "", "", false));
                }))
            .defaultIfEmpty(new Enhanced(picked, "", "", false));
    }

    /** 应用模型返回的顺序与推荐理由；任何解析问题都退回原顺序 */
    private static Enhanced applyVerdict(String aiText, List<AiContentItemDTO> picked) {
        AiContentPromptHelper.Verdict verdict;
        try {
            verdict = AiContentPromptHelper.parse(aiText, picked.size());
        } catch (Exception ex) {
            // 非 JSON（模型拒答/输出被截断）：导语没有就没有，列表照给
            return new Enhanced(picked, "", "", false);
        }
        if (verdict.picks().isEmpty()) {
            return new Enhanced(picked, verdict.intro(), "", verdict.hasIntro());
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
        return new Enhanced(List.copyOf(reordered), verdict.intro(), "", verdict.hasIntro());
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

    // ── 生成状态（进度、完成、失败） ──

    /**
     * 把这一天的记录标成「生成中」。
     *
     * <p>记录可能还不存在（首次生成）：此时连同一份空壳一起创建——状态要落库才能被
     * 后来的请求（轮询、再进页面、推送线）读到。</p>
     */
    private Mono<AiContentIssue> markRunning(String openid, String date) {
        return findIssue(openid, date)
            .flatMap(existing -> {
                // 只按「有没有在途任务」判断：有任务时别把状态改回 RUNNING（那次任务收尾时会写 READY，
                // 中途改写只会让「刚跑完又点一次」变成没人收尾的 RUNNING）；
                // <b>不能按「记录里已有内容」判断</b>——那样用户改完方向再点生成会被静默跳过，
                // 前端只会看到「今日简报已是最新」。
                if (isGenerating(openid, date)) {
                    return Mono.just(existing);
                }
                existing.setStatus(STATUS_RUNNING);
                existing.setStage(STAGE_PREPARE);
                existing.setProgress(PROGRESS_START);
                existing.setError("");
                existing.setUpdatedAt(Instant.now());
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var created = runningIssue(openid, date);
                return client.create(created)
                    .onErrorResume(ex -> {
                        log.warn("[UniHalo][AiContent] 创建生成记录失败，改用内存态继续生成：{}",
                            ex.getMessage());
                        return Mono.just(created);
                    });
            }))
            .onErrorResume(ex -> {
                // 状态写不进去也不能让生成无法开始（进度只是观感）
                log.warn("[UniHalo][AiContent] 标记生成中失败（继续生成）：openid={} {}",
                    mask(openid), ex.getMessage());
                return Mono.just(runningIssue(openid, date));
            });
    }

    /** 内存态的「生成中」记录：写库不可用时的兜底，保证生成流程还能继续 */
    private AiContentIssue runningIssue(String openid, String date) {
        var issue = issueOf(openid, date, "", "", List.of(), 0, 0, false, 0, false);
        issue.setStatus(STATUS_RUNNING);
        issue.setStage(STAGE_PREPARE);
        issue.setProgress(PROGRESS_START);
        issue.setUpdatedAt(Instant.now());
        return issue;
    }

    /**
     * 写入生成结果并把状态标成 {@code READY}。
     *
     * <p><b>必须更新那条「生成中」的记录，而不是新建一条</b>：记录在 {@code markRunning} 时
     * 就已经按 openid+日期创建好了（状态要落库才能被轮询读到），此时再 create 会撞名，
     * 回读拿到的还是那条没有内容的 RUNNING 记录——生成结果就白丢了。</p>
     */
    private Mono<AiContentIssue> save(String openid, String date, String intro, String outro,
        List<AiContentItemDTO> items, int candidateCount, boolean aiGenerated, int liveHours,
        boolean searched) {
        var safeItems = items == null ? List.<AiContentItemDTO>of() : items;
        return persist(openid, date, intro, outro, safeItems, candidateCount, aiGenerated,
                liveHours, searched)
            .doOnNext(saved -> {
                log.info("[UniHalo][AiContent] 简报已生成：openid={} date={} 条数={} 导语={} "
                        + "实时素材={}",
                    mask(openid), date, safeItems.size(), StringUtils.hasText(intro) ? "有" : "无",
                    liveHours > 0 ? liveHours + "h" : "否");
                preferenceService.touchIssueDate(openid, date).subscribe();
            });
    }

    private Mono<AiContentIssue> persist(String openid, String date, String intro, String outro,
        List<AiContentItemDTO> items, int candidateCount, boolean aiGenerated, int liveHours,
        boolean searched) {
        var fresh = issueOf(openid, date, intro, outro, items, candidateCount, items.size(),
            aiGenerated, liveHours, searched);
        markReady(fresh);
        return findIssue(openid, date)
            .flatMap(existing -> client.update(applyResult(existing, fresh)))
            .switchIfEmpty(Mono.defer(() -> client.create(fresh)
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][AiContent] 写入简报结果失败，回读现有记录：{}",
                        ex.getMessage());
                    return findIssue(openid, date);
                })));
    }

    /** 把新结果灌进已有记录（保留 metadata 与创建时间，避免 update 撞乐观锁） */
    private static AiContentIssue applyResult(AiContentIssue target, AiContentIssue fresh) {
        target.setIntro(fresh.getIntro());
        target.setOutro(fresh.getOutro());
        target.setSearched(fresh.getSearched());
        target.setItems(fresh.getItems());
        target.setCandidateCount(fresh.getCandidateCount());
        target.setMatchedCount(fresh.getMatchedCount());
        target.setAiGenerated(fresh.getAiGenerated());
        target.setLiveHours(fresh.getLiveHours());
        markReady(target);
        return target;
    }

    private static void markReady(AiContentIssue issue) {
        issue.setStatus(STATUS_READY);
        issue.setStage(STAGE_DONE);
        issue.setProgress(100);
        issue.setError("");
        issue.setUpdatedAt(Instant.now());
    }

    /** 生成失败：把原因写给用户看（下次点生成可以重试） */
    private Mono<AiContentIssue> markFailed(String openid, String date, String reason) {
        return findIssue(openid, date).flatMap(issue -> {
            issue.setStatus(STATUS_FAILED);
            issue.setStage(STAGE_FAILED);
            issue.setProgress(0);
            issue.setError(StringUtils.hasText(reason) ? reason : "生成失败，请稍后重试");
            issue.setUpdatedAt(Instant.now());
            return client.update(issue).onErrorResume(ex -> Mono.just(issue));
        }).onErrorResume(ex -> Mono.empty());
    }

    /**
     * 上报进度（写进这一天的记录，前端轮询就能看到）。
     *
     * <p>刻意做成「尽力而为」：进度只是观感，写失败不该影响生成本身——
     * 所以全程 {@code onErrorResume} 吞掉，且只在阶段/百分比变化时写一次。</p>
     */
    private void report(String openid, String date, String stage, int progress) {
        findIssue(openid, date)
            .filter(issue -> STATUS_RUNNING.equals(issue.getStatus()))
            .filter(issue -> !stage.equals(issue.getStage())
                || issue.getProgress() == null || issue.getProgress() != progress)
            .flatMap(issue -> {
                issue.setStage(stage);
                issue.setProgress(progress);
                issue.setUpdatedAt(Instant.now());
                return client.update(issue);
            })
            .subscribe(saved -> {
            }, ex -> log.debug("[UniHalo][AiContent] 进度写入失败（忽略）：{}", ex.getMessage()));
    }


    /**
     * 读某用户某天的简报记录。
     *
     * <p>按 openid+日期派生的资源名<b>直取</b>（不再全表扫 openid 再筛日期）：资源名是幂等键，
     * 直取既快一半，也避免「列表索引稍有延迟就读不到刚写进去的进度」这种诡异时序。</p>
     */
    private Mono<AiContentIssue> findIssue(String openid, String date) {
        return client.fetch(AiContentIssue.class, resourceName(openid, date))
            .onErrorResume(ex -> Mono.empty());
    }

    private static AiContentIssue issueOf(String openid, String date, String intro, String outro,
        List<AiContentItemDTO> items, int candidateCount, int matchedCount, boolean aiGenerated,
        int liveHours, boolean searched) {
        var issue = new AiContentIssue();
        issue.setWxOpenid(openid);
        issue.setDate(date);
        issue.setIntro(intro == null ? "" : intro);
        issue.setOutro(outro == null ? "" : outro);
        issue.setSearched(searched);
        issue.setItems(serializeItems(items));
        issue.setCandidateCount(candidateCount);
        issue.setMatchedCount(matchedCount);
        issue.setAiGenerated(aiGenerated);
        issue.setLiveHours(liveHours > 0 ? liveHours : null);
        var now = Instant.now();
        issue.setCreatedAt(now);
        var metadata = new Metadata();
        metadata.setName(resourceName(openid, date));
        metadata.setCreationTimestamp(now);
        issue.setMetadata(metadata);
        return issue;
    }

    /**
     * 记录 → 下发给页面的读模型。
     *
     * <p>三种状态决定页面显示什么：生成中给进度（不显示旧的条目——旧的那份已经被作废重建了），
     * 失败给原因与重试入口，完成给内容。**「生成中超过阈值」按失败处理**：
     * 服务重启会让在途任务消失，记录会永远停在 RUNNING，用户就会一直看到进度条不动。</p>
     */
    private static AiContentFeedDTO toFeed(AiContentIssue issue, AiContentConfigDTO config,
        boolean isToday) {
        return toFeed(issue, config, isToday, null);
    }

    /**
     * 同上，附带「还能生成几次」。
     *
     * <p>{@code quota} 为空表示这次不问配额（例如定时线内部读）；前端拿不到次数时
     * 只显示内容、不显示剩余次数，不会显示一个错的数字。</p>
     */
    private static AiContentFeedDTO toFeed(AiContentIssue issue, AiContentConfigDTO config,
        boolean isToday, Quota quota) {
        var status = issue.getStatus() == null ? STATUS_READY : issue.getStatus();
        if (STATUS_RUNNING.equals(status) && isStaleRunning(issue)) {
            // 卡住的 RUNNING（服务重启让在途任务消失）：有内容就照常显示（内容是真的），
            // 没内容才算失败并给出重试入口——否则用户会一直盯着一动不动的进度条
            log.warn("[UniHalo][AiContent] 生成任务已超过 {} 分钟没有进展：openid={} date={} 有内容={}",
                STALE_RUNNING.toMinutes(), mask(issue.getWxOpenid()), issue.getDate(),
                hasResult(issue));
            status = hasResult(issue) ? STATUS_READY : STATUS_FAILED;
        }
        var items = STATUS_READY.equals(status) ? parseItems(issue.getItems()) : List.<AiContentItemDTO>of();
        var matched = issue.getMatchedCount() == null ? items.size() : issue.getMatchedCount();
        var liveHours = issue.getLiveHours() == null ? 0 : issue.getLiveHours();
        var error = STATUS_FAILED.equals(status)
            ? (StringUtils.hasText(issue.getError()) ? issue.getError() : "生成中断了，请重试")
            : "";
        return AiContentFeedDTO.builder()
            .enabled(true)
            .active(true)
            .date(issue.getDate())
            .intro(STATUS_READY.equals(status) ? issue.getIntro() : "")
            .outro(STATUS_READY.equals(status) ? nullToEmpty(issue.getOutro()) : "")
            .searched(Boolean.TRUE.equals(issue.getSearched()))
            .aiGenerated(STATUS_READY.equals(status) && Boolean.TRUE.equals(issue.getAiGenerated()))
            .candidateCount(issue.getCandidateCount() == null ? 0 : issue.getCandidateCount())
            .matchedCount(matched)
            .windowDays(config.getWindowDays())
            .liveHours(liveHours)
            .status(status)
            .stage(STATUS_RUNNING.equals(status)
                ? (StringUtils.hasText(issue.getStage()) ? issue.getStage() : STAGE_PREPARE) : "")
            .progress(STATUS_RUNNING.equals(status)
                ? Math.min(99, Math.max(0, issue.getProgress() == null ? 0 : issue.getProgress())) : 0)
            .error(error)
            .summary(summaryOf(issue, items, config, liveHours, status))
            .remainingGenerations(quota == null ? -1 : quota.remaining())
            .todayGenerations(quota == null ? 0 : quota.todayUsed())
            .dailyGenerationLimit(quota == null ? 0 : quota.dailyLimit())
            .items(items)
            .build();
    }

    /** 这条记录里是否已经有生成结果（导语或条目）：判断「是不是真跑完了」以内容为准 */
    private static boolean hasResult(AiContentIssue issue) {
        if (issue == null) {
            return false;
        }
        return StringUtils.hasText(issue.getIntro()) || !parseItems(issue.getItems()).isEmpty();
    }

    private static boolean isStaleRunning(AiContentIssue issue) {
        var stamp = issue.getUpdatedAt() == null ? issue.getCreatedAt() : issue.getUpdatedAt();
        return stamp == null || stamp.isBefore(Instant.now().minus(STALE_RUNNING));
    }

    /**
     * 一句话说明：解释这一份是怎么来的、为空时为什么为空。
     *
     * <p>用户花钱买的功能最怕「看不懂为什么没有内容」，因此空态要能区分
     * 「还没生成」「正在生成」「生成失败」与「偏好太窄」。</p>
     */
    private static String summaryOf(AiContentIssue issue, List<AiContentItemDTO> items,
        AiContentConfigDTO config, int liveHours, String status) {
        if (STATUS_RUNNING.equals(status)) {
            return "正在按你的方向生成今天的简报，你可以先离开，生成好再回来就能看到";
        }
        if (STATUS_FAILED.equals(status)) {
            return StringUtils.hasText(issue.getError()) ? issue.getError() : "生成中断了，可以重新生成";
        }
        var candidates = issue.getCandidateCount() == null ? 0 : issue.getCandidateCount();
        // 三种来源的说法必须分开：用户要能从说明里看出「这一份的素材是从哪来的」
        var scope = Boolean.TRUE.equals(issue.getSearched())
            ? "联网检索到的 " + candidates + " 条相关报道"
            : liveHours > 0 ? "最近 " + liveHours + " 小时抓到的 " + candidates + " 条"
            : "最近 " + config.getWindowDays() + " 天的 " + candidates + " 篇精选";
        if (candidates == 0) {
            return liveHours > 0
                ? "最近的源里还没有抓到内容，稍后刷新试试"
                : "最近 " + config.getWindowDays() + " 天还没有可筛的精选内容，"
                    + "等站长生成新一期日报后这里就会有内容";
        }
        if (items.isEmpty()) {
            return scope + "里没有命中你关注的方向，可以放宽关注条件试试";
        }
        if (items.stream().anyMatch(item -> Boolean.TRUE.equals(item.getGenerated()))) {
            return "已按你的定制主题生成专属内容"
                + (items.size() > 1 ? "，并附上站内相关的精选文章" : "");
        }
        var noIntro = Boolean.TRUE.equals(issue.getAiGenerated())
            && StringUtils.hasText(issue.getIntro()) ? "" : "（本次未生成导语，以下是按你的方向挑出的结果）";
        return "从" + scope + "里挑了 " + items.size() + " 条" + noIntro;
    }

    /**
     * 空读模型：今天还没生成过（{@code NONE}），或用户没开通。
     *
     * <p>关键点：**不再自动生成**。用户第一次进来看到的是「还没有今天的简报」与一个生成按钮，
     * 而不是一份他从没要过、却已经烧了 AI 额度筛出来的内容。</p>
     */
    private static AiContentFeedDTO emptyFeed(AiContentConfigDTO config, String date,
        boolean active) {
        return emptyFeed(config, date, active, null);
    }

    /** 同上，带剩余次数（没生成过时前端也要显示「本期还可生成 N 次」） */
    private static AiContentFeedDTO emptyFeed(AiContentConfigDTO config, String date,
        boolean active, Quota quota) {
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
            .liveHours(config.getLiveHours())
            .status(STATUS_NONE)
            .stage("")
            .progress(0)
            .error("")
            .summary(active
                ? "还没有生成今天的简报：按你的关注方向抓取最近 " + config.getLiveHours()
                    + " 小时的内容，每次生成消耗 1 次机会"
                : "还没有开通「AI 定制内容」，开通后即可按自己的方向收每日简报")
            .remainingGenerations(quota == null ? -1 : quota.remaining())
            .todayGenerations(quota == null ? 0 : quota.todayUsed())
            .dailyGenerationLimit(quota == null ? 0 : quota.dailyLimit())
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

    /** 失败原因：异常消息为空时退回类名，别把一句空话写进记录给用户看 */
    private static String resolveMessage(Throwable ex) {
        if (ex == null) {
            return "生成失败，请稍后重试";
        }
        var message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }

    /** 增强结果：条目（可能已重排并带上理由）+ 导语 + 导语是否来自 AI */
    private record Enhanced(List<AiContentItemDTO> items, String intro, String outro,
                            boolean aiGenerated) {

        /** 没有 AI 文案（站长关掉 AI 或调用失败）时的形状 */
        static Enhanced plain(List<AiContentItemDTO> items) {
            return new Enhanced(items, "", "", false);
        }
    }
}
