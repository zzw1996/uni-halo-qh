package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.MpIssue;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.MpConfigHelper;
import pro.uhalo.uni.utils.MpErrors;
import pro.uhalo.uni.utils.MpTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公众号推送编排：把已生成的稿件送进草稿箱（可选发布 / 群发）。
 *
 * <p><b>每方向一篇独立草稿</b>是默认形态：{@link #pushAll(String, boolean)} 一次点完，
 * 逐篇独立推送、逐篇独立结果——一篇失败不影响其它篇。</p>
 *
 * <p><b>进度与日志</b>：推送是「下载图片 → 转码 → 依次调用 3~4 个微信接口」的长链路，
 * 全程把阶段写进 {@link PushProgress}（页面轮询显示），并把每一步的结论追加到稿件的
 * {@code pushLog}（落库，事后可回看走到哪一步、卡在哪一环）。</p>
 *
 * <p><b>失败也要看得懂</b>：图片逐张报原因，接口错误一律走 {@code MpErrors} 分类
 * （IP 白名单 / 权限 / 留言 / 素材 / 频率），不再出现「一句笼统提示」。</p>
 *
 * <p>幂等：推送成功后写入 draftMediaId 与状态；再次推送需要显式 force
 * （草稿被发布/群发后 media_id 会失效，重推会新建一条草稿，这是官方语义）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpPushService {

    /** 发布（freepublish）是异步任务：提交后轮询几次确认结果，超时按「发布中」记录 */
    private static final int PUBLISH_POLL_TIMES = 6;
    private static final Duration PUBLISH_POLL_INTERVAL = Duration.ofSeconds(3);

    /** 一个多图文草稿最多 8 篇（官方图文数量上限） */
    private static final int MAX_MERGED_ARTICLES = 8;

    /** 每篇稿件保留的推送日志行数 */
    private static final int MAX_LOG_LINES = 40;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final ReactiveSettingFetcher settingFetcher;
    private final MpIssueStore store;
    private final MpApiClient apiClient;
    private final MpImageService imageService;
    private final HaloPostPublisher postPublisher;

    private final AtomicBoolean pushing = new AtomicBoolean(false);

    /** 本次推送的每步结论（key = 稿件资源名），在 touch() 时落进稿件的 pushLog */
    private final Map<String, List<String>> journal = new ConcurrentHashMap<>();

    private volatile PushProgress progress = PushProgress.idle();

    /** 单篇推送结果（批量推送时逐篇展示） */
    public record ItemResult(String name, String title, boolean ok, String message) {
    }

    /** 推送进度（页面每几秒轮询一次） */
    public record PushProgress(boolean running, String stage, int current, int total,
                               Instant startedAt, Instant finishedAt, String error,
                               List<ItemResult> results) {

        static PushProgress idle() {
            return new PushProgress(false, "", 0, 0, null, null, "", List.of());
        }

        PushProgress stage(String stage) {
            return new PushProgress(running, stage, current, total, startedAt, finishedAt, error,
                results);
        }

        PushProgress item(int current, int total) {
            return new PushProgress(running, stage, current, total, startedAt, finishedAt, error,
                results);
        }

        PushProgress finished(String error, List<ItemResult> results) {
            return new PushProgress(false, stage, current, total, startedAt, Instant.now(),
                error == null ? "" : error, results);
        }
    }

    public PushProgress progress() {
        return progress;
    }

    public boolean isPushing() {
        return pushing.get();
    }

    /**
     * 准备公众号可用的正文（图片换成微信图床地址）。
     *
     * <p>「复制正文」与「推送草稿箱」共用这一步：复制时先换图床，粘进公众号编辑器后
     * 图片就是微信自己的地址，不依赖编辑器转存。</p>
     */
    public Mono<MpIssue> prepareContent(String name, boolean force) {
        return store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalStateException("公众号稿件不存在：" + name)))
            .flatMap(issue -> loadMpConfig().flatMap(config -> {
                if (!config.isEnabled()) {
                    return Mono.error(new IllegalStateException(
                        "微信公众号功能未开启：请先在插件设置「微信公众号」中打开总开关"));
                }
                var html = StringUtils.hasText(issue.getWechatHtml())
                    ? issue.getWechatHtml() : issue.getHtml();
                if (!StringUtils.hasText(html)) {
                    return Mono.error(new IllegalStateException("该稿件没有正文，无法准备内容"));
                }
                if (StringUtils.hasText(issue.getWechatHtml()) && !force) {
                    return Mono.just(issue);
                }
                if (!config.isUploadImages()) {
                    return Mono.just(issue);
                }
                return imageService.rewriteForWechat(html, config)
                    .map(result -> {
                        issue.setWechatHtml(result.html());
                        issue.setImageNote(result.note());
                        note(issue, "正文图片：" + result.converted() + " 张换成微信图床"
                            + (result.skipped() > 0
                                ? "，" + result.skipped() + " 张失败（" + result.note() + "）" : ""));
                        return issue;
                    })
                    .flatMap(this::touch);
            }));
    }

    /** 推送单篇稿件（默认落点：进草稿箱；配置为 publish/mass 时继续发布/群发） */
    public Mono<MpIssue> pushIssue(String name, boolean force) {
        return guarded("推送", 1, () -> store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalStateException("公众号稿件不存在：" + name)))
            .flatMap(issue -> pushOne(issue, force)
                .doOnNext(pushed -> progress = progress.finished(null,
                    appendResult(progress.results(), pushed))))
            // 失败必须落库 + 报给页面：早前失败只写进日志，站长看到的是「点了没反应」
            .onErrorResume(ex -> store.fetch(name)
                .onErrorResume(fetchError -> Mono.empty())
                .flatMap(issue -> markFailedAndSave(issue, ex))
                .switchIfEmpty(Mono.defer(() -> {
                    progress = progress.finished(messageOf(ex), progress.results());
                    return Mono.empty();
                }))
                .then(Mono.empty())))
            .doOnError(ex -> log.warn("[UniHalo][Mp] 推送失败 name={}：{}", name, ex.getMessage()));
    }

    /**
     * 推送某一天的全部方向：<b>每个方向一篇独立草稿</b>。
     *
     * <p>逐篇串行、逐篇独立结果；某一篇失败不影响后面的篇目。
     * 已推送过的默认跳过（点「重新推送」或带 force 才会重推）。</p>
     */
    public Mono<List<MpIssue>> pushAll(String date, boolean force) {
        if (!StringUtils.hasText(date)) {
            return Mono.error(new IllegalStateException("缺少日期参数（yyyy-MM-dd）"));
        }
        return store.listByDate(date)
            .flatMap(issues -> {
                var pushable = issues.stream()
                    .filter(issue -> MpIssueStore.KIND_ISSUE.equals(issue.getSourceKind()))
                    .filter(issue -> StringUtils.hasText(issue.getHtml()))
                    .filter(issue -> force || !StringUtils.hasText(issue.getDraftMediaId()))
                    .toList();
                if (pushable.isEmpty()) {
                    return Mono.error(new IllegalStateException("这一天没有可推送的公众号稿件"
                        + "（可能都已推送过；要重推请点「重新推送」）"));
                }
                return guarded("批量推送", pushable.size(), () -> Flux.fromIterable(pushable)
                    .index()
                    .concatMap(tuple -> {
                        var index = tuple.getT1().intValue();
                        var issue = tuple.getT2();
                        progress = progress.item(index, pushable.size())
                            .stage("正在推送「" + displayName(issue) + "」（" + (index + 1)
                                + "/" + pushable.size() + "）");
                        return pushOne(issue, force)
                            .doOnNext(pushed -> progress = progress.finished(null,
                                appendResult(progress.results(), pushed)))
                            .onErrorResume(ex -> {
                                log.warn("[UniHalo][Mp] 批量推送第 {} 篇失败：{}（{}）", index + 1,
                                    displayName(issue), ex.getMessage());
                                // 失败也要落到记录上：页面要能显示「为什么没推上去」
                                return markFailedAndSave(issue, ex);
                            });
                    })
                    .collectList()
                    .doOnNext(list -> progress = progress.item(list.size(), list.size())));
            });
    }
    /** 合并推送：把同一天的多个方向合成一个多图文草稿（默认不用；每方向独立草稿才是常态） */
    public Mono<List<MpIssue>> pushMerged(String date, boolean force) {
        if (!StringUtils.hasText(date)) {
            return Mono.error(new IllegalStateException("缺少日期参数（yyyy-MM-dd）"));
        }
        return store.listByDate(date)
            .flatMap(issues -> {
                var pushable = issues.stream()
                    .filter(issue -> MpIssueStore.KIND_ISSUE.equals(issue.getSourceKind()))
                    .filter(issue -> StringUtils.hasText(issue.getHtml()))
                    .filter(issue -> force || !StringUtils.hasText(issue.getDraftMediaId()))
                    .limit(MAX_MERGED_ARTICLES)
                    .toList();
                if (pushable.isEmpty()) {
                    return Mono.error(new IllegalStateException("这一天没有可推送的公众号稿件"
                        + "（可能都已推送过；合并推送请先点「重新推送」）"));
                }
                return guarded("合并推送", pushable.size(), () -> {
                    progress = progress.stage("正在合并推送 " + pushable.size()
                        + " 个方向（一个多图文草稿）…");
                    return loadMpConfig().flatMap(config -> {
                        if (!config.isEnabled()) {
                            return Mono.error(new IllegalStateException(
                                "微信公众号功能未开启：请先在插件设置「微信公众号」中打开总开关"));
                        }
                        return Flux.fromIterable(pushable)
                            .concatMap(issue -> publishSiteIfNeeded(issue, config))
                            .concatMap(issue -> prepareContent(issue.getMetadata().getName(), false)
                                .defaultIfEmpty(issue))
                            .collectList()
                            .flatMap(prepared -> buildAndSend(prepared.get(0), config, prepared)
                                .thenReturn(prepared));
                    });
                });
            });
    }

    // ── 单篇推送 ──

    private Mono<MpIssue> pushOne(MpIssue issue, boolean force) {
        if ("GENERATING".equals(issue.getState())) {
            return Mono.error(new IllegalStateException("该稿件还在生成中，请等生成完成后再推送"));
        }
        if (!StringUtils.hasText(issue.getHtml())) {
            return Mono.error(new IllegalStateException("该稿件没有正文"
                + ("EMPTY".equals(issue.getState()) ? "（本期该方向没有候选文章）" : "")
                + "，无法推送"));
        }
        if (!force && StringUtils.hasText(issue.getDraftMediaId())
            && ("DRAFTED".equals(issue.getState()) || "PUBLISHED".equals(issue.getState())
            || "MASSED".equals(issue.getState()))) {
            return Mono.error(new IllegalStateException(
                "该稿件已推送过（草稿 media_id=" + maskId(issue.getDraftMediaId())
                    + "）：要再推一份请用「重新推送」"));
        }
        return loadMpConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return Mono.error(new IllegalStateException(
                    "微信公众号功能未开启：请先在插件设置「微信公众号」中打开总开关"));
            }
            // 每篇推送前把上一次的失败原因清掉：页面上不该还挂着上一轮的红字
            issue.setError(null);
            issue.setErrcode(null);
            issue.setErrmsg(null);
            progress = progress.stage("准备「" + displayName(issue) + "」的正文与封面…");
            return publishSiteIfNeeded(issue, config)
                .flatMap(prepared -> prepareContent(prepared.getMetadata().getName(), true)
                    .defaultIfEmpty(prepared))
                .flatMap(prepared -> buildAndSend(prepared, config, List.of(prepared))
                    .thenReturn(prepared));
        });
    }

    /** 组装并提交一次 draft/add（{@code commentsOff=true} 时强制关闭留言开关） */
    private Mono<MpApiClient.MpCall> sendDraft(List<MpIssue> articles, MpConfigDTO config,
        boolean commentsOff) {
        return Flux.fromIterable(articles)
            .concatMap(issue -> buildArticle(issue, config, commentsOff))
            .collectList()
            .doOnNext(built -> log.info("[UniHalo][Mp] 推送草稿箱：{} 篇（{}）", built.size(),
                built.stream().map(MpApiClient.MpArticle::title).toList()))
            .flatMap(apiClient::addDraft);
    }

    /**
     * 组装图文并发送（单篇或合并成多图文）。
     *
     * @param primary  代表该次推送的稿件（合并推送时用第一篇记录结果）
     * @param articles 要放进同一草稿的稿件
     */
    private Mono<MpIssue> buildAndSend(MpIssue primary, MpConfigDTO config, List<MpIssue> articles) {
        progress = progress.stage("正在创建公众号草稿…");
        return sendDraft(articles, config, false)
            .flatMap(call -> {
                // 88000 = 账号没有留言功能：关掉留言开关重发一次（内容一字不变）
                if (!call.ok() && call.error().errcode() == 88000
                    && (config.isNeedOpenComment() || config.isOnlyFansCanComment())) {
                    log.warn("[UniHalo][Mp] 账号没有留言权限（88000），自动关闭留言后重试一次");
                    note(primary, "留言：账号没有留言权限（88000），已自动关闭留言后重试");
                    return sendDraft(articles, config, true);
                }
                return Mono.just(call);
            })
            .flatMap(call -> handleDraftResult(primary, articles, config, call));
    }

    private Mono<MpIssue> handleDraftResult(MpIssue primary, List<MpIssue> articles,
        MpConfigDTO config, MpApiClient.MpCall call) {
        if (!call.ok()) {
            var error = call.error();
            var message = "推送草稿箱失败：" + error.suggestion()
                + (StringUtils.hasText(error.ip()) ? "（服务器出口 IP：" + error.ip() + "）" : "");
            log.warn("[UniHalo][Mp] 草稿推送失败：{}", MpErrors.describe(error));
            primary.setErrcode(error.errcode());
            primary.setErrmsg(error.errmsg());
            primary.setError(message);
            primary.setAttempts((primary.getAttempts() == null ? 1 : primary.getAttempts()) + 1);
            note(primary, "草稿箱：失败 errcode=" + error.errcode()
                + (StringUtils.hasText(error.errmsg()) ? " errmsg=" + error.errmsg() : ""));
            return touch(primary).then(Mono.error(new IllegalStateException(message)));
        }
        var mediaId = call.text("media_id");
        if (!StringUtils.hasText(mediaId)) {
            var message = "草稿接口成功但未返回 media_id，请稍后重试";
            primary.setError(message);
            note(primary, "草稿箱：返回里没有 media_id");
            return touch(primary).then(Mono.error(new IllegalStateException(message)));
        }
        log.info("[UniHalo][Mp] 草稿箱推送成功：media_id={}（{} 篇）", maskId(mediaId), articles.size());
        var delivery = MpConfigHelper.deliveryModeLabel(config.getDeliveryMode());
        return Flux.fromIterable(articles)
            .concatMap(issue -> {
                issue.setDraftMediaId(mediaId);
                issue.setState("DRAFTED");
                issue.setPushMode(config.getDeliveryMode());
                issue.setErrcode(null);
                issue.setErrmsg(null);
                issue.setError(null);
                issue.setFinishedAt(Instant.now());
                note(issue, "草稿箱：成功 media_id=" + maskId(mediaId)
                    + (articles.size() > 1 ? "（多图文，共 " + articles.size() + " 篇）" : ""));
                return touch(issue);
            })
            .then(Mono.defer(() -> advanceDelivery(articles.get(0), articles, config, mediaId,
                delivery)))
            .thenReturn(articles.get(0));
    }

    /** 草稿之后的动作：只进草稿箱就到此为止；发布 / 群发按配置继续 */
    private Mono<Void> advanceDelivery(MpIssue primary, List<MpIssue> articles, MpConfigDTO config,
        String mediaId, String deliveryLabel) {
        var mode = config.getDeliveryMode();
        if (MpConfigHelper.DELIVERY_PUBLISH.equals(mode)) {
            return submitPublish(primary, articles, mediaId);
        }
        if (MpConfigHelper.DELIVERY_MASS.equals(mode)) {
            return massSend(primary, articles, mediaId);
        }
        progress = progress.stage("已进草稿箱：到公众号后台确认后发布即可");
        log.info("[UniHalo][Mp] 推送完成（{}）：请到公众号后台预览确认后发布", deliveryLabel);
        return Mono.empty();
    }

    private Mono<Void> submitPublish(MpIssue primary, List<MpIssue> articles, String mediaId) {
        progress = progress.stage("已进草稿箱，正在提交发布（freepublish）…");
        return apiClient.submitPublish(mediaId)
            .flatMap(call -> {
                if (!call.ok()) {
                    primary.setErrcode(call.error().errcode());
                    primary.setErrmsg(call.error().errmsg());
                    primary.setError("提交发布失败：" + call.error().suggestion());
                    note(primary, "发布：提交失败 errcode=" + call.error().errcode());
                    return touch(primary).then(Mono.empty());
                }
                var publishId = call.text("publish_id");
                primary.setPublishId(publishId);
                note(primary, "发布：已提交 publish_id=" + maskId(publishId));
                log.info("[UniHalo][Mp] 已提交发布：publish_id={}", publishId);
                return pollPublish(primary, articles, publishId, 0);
            });
    }

    /** 轮询发布结果：0 成功；1 发布中；2/3/4 失败（原创声明失败 / 普通失败 / 审核不通过） */
    private Mono<Void> pollPublish(MpIssue primary, List<MpIssue> articles, String publishId,
        int attempt) {
        if (attempt >= PUBLISH_POLL_TIMES) {
            primary.setError("发布任务已提交但未在预期时间内完成（publish_id=" + publishId
                + "）：可在公众号后台查看发布状态，插件不再轮询");
            note(primary, "发布：等待超时，未确认结果");
            return touch(primary).then(Mono.empty());
        }
        return Mono.delay(PUBLISH_POLL_INTERVAL)
            .flatMap(ignored -> apiClient.queryPublish(publishId))
            .flatMap(call -> {
                if (!call.ok()) {
                    primary.setError("查询发布状态失败：" + call.error().suggestion());
                    note(primary, "发布：查询状态失败 errcode=" + call.error().errcode());
                    return touch(primary).then(Mono.empty());
                }
                var status = call.intValue("publish_status");
                if (status == 1) {
                    progress = progress.stage("发布中（第 " + (attempt + 1) + " 次查询）…");
                    return pollPublish(primary, articles, publishId, attempt + 1);
                }
                primary.setPublishStatus(status);
                if (status == 0) {
                    var url = articleUrlOf(call);
                    primary.setArticleUrl(url);
                    primary.setError(null);
                    note(primary, "发布：成功" + (StringUtils.hasText(url) ? " " + url : ""));
                    log.info("[UniHalo][Mp] 发布成功：{}", url);
                    return Flux.fromIterable(articles)
                        .concatMap(issue -> {
                            issue.setState("PUBLISHED");
                            issue.setPublishId(publishId);
                            issue.setPublishStatus(0);
                            issue.setArticleUrl(url);
                            return touch(issue);
                        })
                        .then();
                }
                var reason = switch (status) {
                    case 2 -> "原创声明失败（账号未获得原创能力，或内容与已有原创重复）";
                    case 4 -> "平台审核不通过";
                    default -> "发布失败";
                };
                primary.setError("发布未成功：" + reason + "（publish_status=" + status + "）");
                note(primary, "发布：失败 publish_status=" + status);
                log.warn("[UniHalo][Mp] {}", primary.getError());
                return touch(primary).then(Mono.empty());
            });
    }

    private Mono<Void> massSend(MpIssue primary, List<MpIssue> articles, String mediaId) {
        progress = progress.stage("已进草稿箱，正在群发…");
        return apiClient.massSendAll(mediaId)
            .flatMap(call -> {
                if (!call.ok()) {
                    primary.setErrcode(call.error().errcode());
                    primary.setErrmsg(call.error().errmsg());
                    primary.setError("群发失败：" + call.error().suggestion());
                    note(primary, "群发：失败 errcode=" + call.error().errcode());
                    return touch(primary).then(Mono.empty());
                }
                note(primary, "群发：已提交 msg_id=" + maskId(call.text("msg_id")));
                log.info("[UniHalo][Mp] 群发已提交：msg_id={}", call.text("msg_id"));
                return Flux.fromIterable(articles)
                    .concatMap(issue -> {
                        issue.setState("MASSED");
                        return touch(issue);
                    })
                    .then();
            });
    }

    // ── 组装图文 ──

    private Mono<MpApiClient.MpArticle> buildArticle(MpIssue issue, MpConfigDTO config,
        boolean commentsOff) {
        return ensureCover(issue, config)
            .map(mediaId -> new MpApiClient.MpArticle(
                MpTextUtils.clipTitle(issue.getTitle(), MpTextUtils.MAX_TITLE_CHARS),
                MpTextUtils.clip(config.getAuthor(), MpTextUtils.MAX_AUTHOR_CHARS),
                MpTextUtils.clip(issue.getDigest(), MpTextUtils.MAX_DIGEST_CHARS),
                contentOf(issue),
                resolveSourceUrl(issue, config),
                mediaId,
                !commentsOff && config.isNeedOpenComment(),
                !commentsOff && config.isOnlyFansCanComment()))
            .flatMap(article -> {
                if (StringUtils.hasText(article.thumbMediaId())) {
                    return Mono.just(article);
                }
                return Mono.error(new IllegalStateException(
                    "封面素材准备失败：公众号草稿必须带封面（thumb_media_id）"));
            });
    }

    private Mono<String> ensureCover(MpIssue issue, MpConfigDTO config) {
        if (StringUtils.hasText(issue.getCoverMediaId())) {
            note(issue, "封面素材：复用已上传的永久素材");
            return Mono.just(issue.getCoverMediaId());
        }
        var baseName = "mp-cover-" + issue.getMetadata().getName();
        progress = progress.stage("上传封面为永久素材（" + displayName(issue) + "）…");
        return imageService.ensureCoverMediaId(issue.getCover(), baseName, config)
            .flatMap(mediaId -> {
                issue.setCoverMediaId(mediaId);
                note(issue, "封面素材：已上传为永久素材 media_id=" + maskId(mediaId));
                return touch(issue).thenReturn(mediaId);
            });
    }

    /** 正文：优先用换过图床的版本（复制/推送都以它为准），并做 2 万字符保护 */
    private static String contentOf(MpIssue issue) {
        var html = StringUtils.hasText(issue.getWechatHtml())
            ? issue.getWechatHtml() : issue.getHtml();
        return MpTextUtils.clipContent(html, MpTextUtils.MAX_CONTENT_CHARS);
    }

    /** 阅读原文：站点文章 / 固定链接 / 不设置 */
    private String resolveSourceUrl(MpIssue issue, MpConfigDTO config) {
        return switch (config.getContentSourceMode() == null ? MpConfigHelper.SOURCE_SITE
            : config.getContentSourceMode()) {
            case MpConfigHelper.SOURCE_FIXED -> MpTextUtils.clipRaw(config.getContentSourceUrl(),
                MpTextUtils.MAX_SOURCE_URL_CHARS);
            case MpConfigHelper.SOURCE_NONE -> "";
            default -> StringUtils.hasText(issue.getSitePostUrl()) ? issue.getSitePostUrl() : "";
        };
    }

    /**
     * 可选：把这一期也发布到站点（「阅读原文」随之指向它）。
     *
     * <p>失败不阻断公众号推送：站点发布失败时只记一条错误，「阅读原文」自动留空。</p>
     */
    private Mono<MpIssue> publishSiteIfNeeded(MpIssue issue, MpConfigDTO config) {
        if (!config.isPostToSite() || !MpIssueStore.KIND_ISSUE.equals(issue.getSourceKind())
            || StringUtils.hasText(issue.getSitePostUrl())) {
            return Mono.just(issue);
        }
        return loadDigestConfig().flatMap(aiConfig -> {
            var post = HaloPostPublishDTO.builder()
                .title(issue.getTitle())
                .slug("mp-" + issue.getMetadata().getName())
                .markdown(issue.getMarkdown())
                .cover(issue.getCover())
                .categoryNames(DigestConfigHelper.splitNames(config.getPostCategories()))
                .aiCategories(List.of())
                .keywords(List.of())
                .catalog(MpConfigHelper.directionCatalog(config))
                .categoryMode(DigestConfigHelper.CATEGORY_MODE_CREATE)
                .owner(config.getPostOwner())
                .build();
            progress = progress.stage("同步发布到站点（" + displayName(issue) + "）…");
            log.info("[UniHalo][Mp] 同步发布到站点：{}", issue.getTitle());
            return postPublisher.publish(post, aiConfig.getPublishWaitSeconds())
                .map(result -> {
                    if ("PUBLISHED".equals(result.getState())) {
                        issue.setSitePostName(result.getPostName());
                        issue.setSitePostUrl(result.getPermalink());
                        note(issue, "站点发布：成功 " + result.getPermalink());
                    } else {
                        note(issue, "站点发布：未完成（state=" + result.getState() + "）");
                        log.warn("[UniHalo][Mp] 站点发布未完成（state={} {}）：「阅读原文」将留空",
                            result.getState(), result.getMessage());
                    }
                    issue.setUpdatedAt(Instant.now());
                    return issue;
                })
                .flatMap(this::touch)
                .onErrorResume(ex -> {
                    note(issue, "站点发布：失败（" + ex.getMessage() + "）");
                    log.warn("[UniHalo][Mp] 站点发布失败（不影响公众号推送）：{}", ex.getMessage());
                    return Mono.just(issue);
                });
        });
    }

    // ── 进度 / 日志 / 工具 ──

    /** 统一的「单飞」包装：置位进度、清空日志、异常收尾 */
    private <T> Mono<T> guarded(String stage, int total, java.util.function.Supplier<Mono<T>> action) {
        if (!pushing.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("已有一次推送任务在进行中，请稍后再试"));
        }
        var startedAt = Instant.now();
        journal.clear();
        progress = new PushProgress(true, stage + "中…", 0, total, startedAt, null, "",
            new ArrayList<>());
        return action.get()
            .doOnError(ex -> progress = progress.finished(
                StringUtils.hasText(ex.getMessage()) ? ex.getMessage()
                    : ex.getClass().getSimpleName(), progress.results()))
            // 成功收尾时把计数归位：否则页面上会一直停着「0/1」，看着像没跑完
            .doOnNext(ignored -> progress = progress.item(total, total)
                .finished(null, progress.results()))
            .doFinally(signal -> {
                pushing.set(false);
                journal.clear();
            });
    }

    private static List<ItemResult> appendResult(List<ItemResult> existing, MpIssue issue) {
        var list = new ArrayList<>(existing);
        list.add(new ItemResult(issue.getMetadata().getName(), issue.getTitle(),
            !"FAILED".equals(issue.getState()) && !StringUtils.hasText(issue.getError()),
            StringUtils.hasText(issue.getError()) ? issue.getError() : "已推送"));
        return List.copyOf(list);
    }

    /**
     * 失败收尾：把原因落库（含推送日志）、记进逐篇结果，并把进度标成结束。
     *
     * <p>这是「点了推草稿箱没反应」的直接修复点：早前失败路径既不更新记录、
     * 也不产出结果项，页面自然什么都不显示。</p>
     */
    private Mono<MpIssue> markFailedAndSave(MpIssue issue, Throwable ex) {
        var message = messageOf(ex);
        issue.setError(message);
        issue.setAttempts((issue.getAttempts() == null ? 1 : issue.getAttempts()) + 1);
        issue.setFinishedAt(Instant.now());
        note(issue, "推送中断：" + message);
        return touch(issue)
            .doOnNext(saved -> progress = progress.finished(null,
                appendResult(progress.results(), saved)))
            .onErrorResume(saveError -> {
                log.warn("[UniHalo][Mp] 失败原因落库失败（不影响页面提示）：{}", saveError.getMessage());
                progress = progress.finished(message, appendResult(progress.results(), issue));
                return Mono.just(issue);
            });
    }

    private static String messageOf(Throwable ex) {
        return ex == null ? "推送失败"
            : (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
    }

    /** 记一步推送日志（在 touch() 时落库） */
    private void note(MpIssue issue, String line) {
        if (issue == null || issue.getMetadata() == null
            || !StringUtils.hasText(issue.getMetadata().getName())) {
            return;
        }
        journal.computeIfAbsent(issue.getMetadata().getName(), key -> new ArrayList<>())
            .add(ZonedDateTime.now(ZONE).format(LOG_TIME) + "  " + line);
    }

    /**
     * 落库，并把本次推送日志追加进去（只保留最后若干行）。
     *
     * <p>带<b>乐观锁重试</b>：一次推送会在同一条记录上写好几步（封面素材 id → 草稿 media_id →
     * 状态 → 日志），而 {@code client.update()} 返回的是新实例；只要某一步用的是旧引用，
     * Halo 就会拒绝：{@code Failed to update versioned entity ...; Was the entity updated or
     * deleted concurrently?}。遇到这种冲突就重读记录、把推送字段搬过去再写一次，
     * 站长不会看到这种内部错误。</p>
     */
    private Mono<MpIssue> touch(MpIssue issue) {
        var name = issue.getMetadata().getName();
        return Mono.defer(() -> {
            mergeJournal(issue);
            issue.setUpdatedAt(Instant.now());
            return store.update(issue)
                .onErrorResume(ex -> {
                    if (!MpIssueStore.isVersionConflict(ex)) {
                        return Mono.error(ex);
                    }
                    log.info("[UniHalo][Mp] 稿件 {} 写入遇到并发更新，重读记录后重试一次", name);
                    return store.fetch(name)
                        .onErrorResume(fetchError -> Mono.empty())
                        .flatMap(fresh -> {
                            copyPushFields(issue, fresh);
                            mergeJournal(fresh);
                            fresh.setUpdatedAt(Instant.now());
                            return store.update(fresh);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[UniHalo][Mp] 稿件 {} 在推送过程中被删除，本次推送结果不再落库",
                                name);
                            return Mono.just(issue);
                        }));
                });
        });
    }

    /**
     * 把推送流程改动的字段从内存实例搬到刚读回来的记录上。
     *
     * <p>两类字段处理不同：<b>推送状态</b>（状态、草稿/发布 id、错误、日志…）无条件覆盖，
     * 因为它们本来就该由本次推送决定（包括「把上一次的错误清掉」）；
     * 而 <b>推送产物</b>（封面素材 id、换过图床的正文、配图说明）只在内存里有值时才写，
     * 否则会把库里的成果抹成 null。内容字段（标题/正文/摘要等）一律以库里为准——
     * 并发期间若重新生成过，不能被推送流程里的旧引用改回去。</p>
     */
    static void copyPushFields(MpIssue from, MpIssue to) {
        to.setTrigger(from.getTrigger());
        to.setPushMode(from.getPushMode());
        to.setState(from.getState());
        to.setDraftMediaId(from.getDraftMediaId());
        to.setPublishId(from.getPublishId());
        to.setPublishStatus(from.getPublishStatus());
        to.setArticleUrl(from.getArticleUrl());
        to.setSitePostName(from.getSitePostName());
        to.setSitePostUrl(from.getSitePostUrl());
        to.setErrcode(from.getErrcode());
        to.setErrmsg(from.getErrmsg());
        to.setError(from.getError());
        to.setAttempts(from.getAttempts());
        to.setFinishedAt(from.getFinishedAt());
        to.setPushLog(from.getPushLog());
        // 推送产物：内存里没有就不动库里的值
        if (StringUtils.hasText(from.getCoverMediaId())) {
            to.setCoverMediaId(from.getCoverMediaId());
        }
        if (StringUtils.hasText(from.getWechatHtml())) {
            to.setWechatHtml(from.getWechatHtml());
        }
        if (StringUtils.hasText(from.getImageNote())) {
            to.setImageNote(from.getImageNote());
        }
    }

    /** 把 journal 里攒的日志行合进记录（每个资源名只合一次） */
    private void mergeJournal(MpIssue issue) {
        var lines = journal.remove(issue.getMetadata().getName());
        if (lines == null || lines.isEmpty()) {
            return;
        }
        var merged = new ArrayList<String>();
        if (StringUtils.hasText(issue.getPushLog())) {
            merged.addAll(List.of(issue.getPushLog().split("\n")));
        }
        merged.addAll(lines);
        if (merged.size() > MAX_LOG_LINES) {
            merged = new ArrayList<>(merged.subList(merged.size() - MAX_LOG_LINES, merged.size()));
        }
        issue.setPushLog(String.join("\n", merged));
    }

    private static String displayName(MpIssue issue) {
        if (StringUtils.hasText(issue.getDirectionLabel())) {
            return issue.getDirectionLabel();
        }
        return StringUtils.hasText(issue.getTitle()) ? issue.getTitle()
            : issue.getMetadata().getName();
    }

    private static String articleUrlOf(MpApiClient.MpCall call) {
        try {
            var items = call.body() == null ? null
                : call.body().path("article_detail").path("item");
            if (items != null && items.isArray() && !items.isEmpty()) {
                return items.get(0).path("article_url").asText("");
            }
        } catch (Exception ignored) {
            // 取不到链接不影响发布结果记录
        }
        return "";
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

    private static String maskId(String id) {
        if (!StringUtils.hasText(id)) {
            return "(空)";
        }
        return id.length() <= 6 ? id + "***" : id.substring(0, 6) + "***";
    }
}
