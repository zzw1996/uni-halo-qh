package pro.uhalo.uni.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.dto.MpIssueSummaryDTO;
import pro.uhalo.uni.dto.MpRequest;
import pro.uhalo.uni.dto.MpStatusDTO;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.MpIssue;
import pro.uhalo.uni.services.MpBackfillService;
import pro.uhalo.uni.services.MpIssueService;
import pro.uhalo.uni.services.MpPushService;
import pro.uhalo.uni.services.MpScheduler;
import pro.uhalo.uni.services.MpSelfCheckService;
import pro.uhalo.uni.utils.MpConfigHelper;
import pro.uhalo.uni.utils.MpTextUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公众号后台接口。
 *
 * <p>生成与推送都是异步的（生成要跑 RSS + 多轮 AI，推送含图片上传与发布轮询），
 * 这里只负责触发，页面靠「状态」接口的 generating/pushing 标记与列表刷新观察进度。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpController {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final MpIssueStore store;
    private final MpIssueService issueService;
    private final MpPushService pushService;
    private final MpBackfillService backfillService;
    private final MpSelfCheckService selfCheckService;
    private final MpScheduler scheduler;

    /** 状态：配置摘要 + 生成进度 + 稿件统计 */
    public Mono<ServerResponse> status(ServerRequest request) {
        return loadConfig()
            .flatMap(config -> scheduler.nextRunInstant()
                .map(java.util.Optional::ofNullable)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(nextRun -> store.listRecent(MAX_LIMIT)
                    .map(issues -> buildStatus(config, issues, nextRun.orElse(null)))))
            .flatMap(this::jsonOk);
    }

    private Mono<pro.uhalo.uni.dto.MpConfigDTO> loadConfig() {
        return settingFetcher.get(MpConfigHelper.groupName())
            .map(MpConfigHelper::parse)
            .defaultIfEmpty(MpConfigHelper.defaults());
    }

    /** 稿件列表（最近 N 条） */
    public Mono<ServerResponse> listIssues(ServerRequest request) {
        return store.listRecent(limitParam(request))
            .map(issues -> issues.stream().map(MpController::toSummary).toList())
            .flatMap(this::jsonOk);
    }

    /** 稿件详情（含 markdown / 微信版 HTML / 精选条目） */
    public Mono<ServerResponse> getIssue(ServerRequest request) {
        var name = request.pathVariable("name");
        return store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("公众号稿件不存在：" + name)))
            .flatMap(issue -> {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("summary", toSummary(issue));
                payload.put("markdown", issue.getMarkdown());
                payload.put("html", issue.getHtml());
                payload.put("wechatHtml", issue.getWechatHtml());
                payload.put("articles", parseJsonArray(issue.getArticles()));
                payload.put("draftMediaId", issue.getDraftMediaId());
                payload.put("publishId", issue.getPublishId());
                payload.put("publishStatus", issue.getPublishStatus());
                payload.put("errmsg", issue.getErrmsg());
                payload.put("aiCost", parseJsonNode(issue.getAiCost()));
                payload.put("pushLog", issue.getPushLog());
                payload.put("titleLimit", MpTextUtils.MAX_TITLE_CHARS);
                payload.put("digestLimit", MpTextUtils.MAX_DIGEST_CHARS);
                return jsonOk(payload);
            })
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 推送某一天的全部方向（每方向一篇独立草稿）。
     *
     * <p>这是「一次点完三个方向」的入口：逐篇独立推送、逐篇独立结果，
     * 页面靠状态接口的 push* 字段显示进度。</p>
     */
    public Mono<ServerResponse> pushAll(ServerRequest request) {
        var date = request.queryParam("date").orElse("");
        var force = request.queryParam("force").map(Boolean::parseBoolean).orElse(false);
        if (pushService.isPushing()) {
            return badRequest("已有一次推送任务在进行中，请稍后再试");
        }
        return requireEnabled(() -> {
            log.info("[UniHalo][Mp] 收到批量推送请求：date={} force={}", date, force);
            pushService.pushAll(date, force)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    issues -> log.info("[UniHalo][Mp] 批量推送结束：{} 篇", issues.size()),
                    ex -> log.warn("[UniHalo][Mp] 批量推送失败：{}", ex.getMessage(), ex));
            return jsonOk(Map.of("started", true,
                "message", "已开始推送（每方向一篇独立草稿），可在上方看到每篇的进度与结果"));
        }).onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 准备正文（把图片换成微信图床地址）并返回最新详情。
     *
     * <p>「复制正文」按钮先调它：这样粘进公众号编辑器的图片就是微信自己的地址，
     * 不依赖编辑器对外链图片的转存。</p>
     */
    public Mono<ServerResponse> prepareIssue(ServerRequest request) {
        var name = request.pathVariable("name");
        var force = request.queryParam("force").map(Boolean::parseBoolean).orElse(true);
        return pushService.prepareContent(name, force)
            .flatMap(issue -> {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("summary", toSummary(issue));
                payload.put("wechatHtml", issue.getWechatHtml());
                payload.put("imageNote", issue.getImageNote());
                return jsonOk(payload);
            })
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 立即生成（全部方向；body/查询串可指定单个方向） */
    public Mono<ServerResponse> generate(ServerRequest request) {
        return request.bodyToMono(MpRequest.class)
            .defaultIfEmpty(new MpRequest())
            .flatMap(body -> {
                if (issueService.isRunning()) {
                    return badRequest("已有一次公众号文章生成任务在执行中，请等它跑完");
                }
                var direction = StringUtils.hasText(body.getDirection()) ? body.getDirection()
                    : request.queryParam("direction").orElse("");
                return requireEnabled(() -> {
                    log.info("[UniHalo][Mp] 收到后台手动生成请求：direction={}", direction);
                    issueService.generate(direction, true)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                            issues -> log.info("[UniHalo][Mp] 后台手动生成结束：{} 条",
                                issues == null ? 0 : issues.size()),
                            ex -> log.warn("[UniHalo][Mp] 后台手动生成失败：{}", ex.getMessage(), ex));
                    return jsonOk(Map.of(
                        "started", true,
                        "message", "已开始生成（抓取 + AI 评分 + 逐方向成稿），预计 1-3 分钟；"
                            + "可刷新列表查看进度"));
                });
            })
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 推送单篇到草稿箱（按配置可继续发布/群发） */
    public Mono<ServerResponse> pushIssue(ServerRequest request) {
        var name = request.pathVariable("name");
        var force = request.queryParam("force").map(Boolean::parseBoolean).orElse(false);
        if (pushService.isPushing()) {
            return badRequest("已有一次推送任务在进行中，请稍后再试");
        }
        return requireEnabled(() -> {
            log.info("[UniHalo][Mp] 收到推送请求：name={} force={}", name, force);
            pushService.pushIssue(name, force)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    issue -> log.info("[UniHalo][Mp] 推送结束 name={} state={}", name,
                        issue.getState()),
                    ex -> log.warn("[UniHalo][Mp] 推送失败 name={}：{}", name, ex.getMessage(), ex));
            return jsonOk(Map.of("started", true, "message", "已开始推送，可刷新列表查看状态"));
        }).onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 合并推送某一天的全部稿件（一个多图文草稿） */
    public Mono<ServerResponse> pushMerged(ServerRequest request) {
        var date = request.queryParam("date").orElse("");
        var force = request.queryParam("force").map(Boolean::parseBoolean).orElse(false);
        if (pushService.isPushing()) {
            return badRequest("已有一次推送任务在进行中，请稍后再试");
        }
        return requireEnabled(() -> {
            log.info("[UniHalo][Mp] 收到合并推送请求：date={} force={}", date, force);
            pushService.pushMerged(date, force)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    issues -> log.info("[UniHalo][Mp] 合并推送结束：{} 篇", issues.size()),
                    ex -> log.warn("[UniHalo][Mp] 合并推送失败：{}", ex.getMessage(), ex));
            return jsonOk(Map.of("started", true, "message", "已开始合并推送（一个多图文草稿）"));
        }).onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 总开关的前置校验。
     *
     * <p>生成与推送都是「先返回、后台跑」，若把「未开启」留给后台任务，
     * 页面会看到「已开始」却什么都没发生——所以在触发前就拦住它。</p>
     */
    private Mono<ServerResponse> requireEnabled(java.util.function.Supplier<Mono<ServerResponse>> action) {
        return loadConfig().flatMap(config -> config.isEnabled() ? action.get()
            : badRequest("微信公众号功能未开启：请先在插件设置「微信公众号」中打开总开关"));
    }

    /** 历史日报列表 + 已生成的补推稿 */
    public Mono<ServerResponse> listBackfill(ServerRequest request) {
        var limit = limitParam(request);
        return backfillService.listReports(limit)
            .flatMap(reports -> store.listByKind(MpIssueStore.KIND_BACKFILL, MAX_LIMIT)
                .map(backfills -> {
                    var byReport = new LinkedHashMap<String, List<MpIssueSummaryDTO>>();
                    for (var issue : backfills) {
                        var reportName = issue.getSourceKey() == null ? ""
                            : issue.getSourceKey().split("\\|")[0];
                        byReport.computeIfAbsent(reportName, key -> new java.util.ArrayList<>())
                            .add(toSummary(issue));
                    }
                    var rows = reports.stream().map(report -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("name", report.getMetadata().getName());
                        row.put("date", report.getDate());
                        row.put("slot", report.getSlot());
                        row.put("status", report.getStatus());
                        row.put("articleCount", report.getArticleCount());
                        row.put("postUrl", report.getPostUrl());
                        row.put("cover", report.getCover());
                        row.put("error", report.getError());
                        row.put("startedAt", report.getStartedAt());
                        row.put("mpIssues", byReport.getOrDefault(report.getMetadata().getName(),
                            List.of()));
                        return row;
                    }).toList();
                    return Map.of("reports", rows, "total", rows.size());
                }))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 从一期历史日报生成公众号稿（merged = 整期一篇，per-article = 逐篇） */
    public Mono<ServerResponse> backfill(ServerRequest request) {
        return request.bodyToMono(MpRequest.class)
            .defaultIfEmpty(new MpRequest())
            .flatMap(body -> backfillService.buildFromReport(body.getReportName(), body.getMode())
                .map(issues -> {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("count", issues.size());
                    payload.put("issues", issues.stream().map(MpController::toSummary).toList());
                    payload.put("message", "已生成 " + issues.size()
                        + " 篇公众号稿：可在列表中预览、复制正文，或直接推送草稿箱");
                    return payload;
                }))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 接入自检（?deep=true 会建一条测试草稿并自动删除） */
    public Mono<ServerResponse> selfCheck(ServerRequest request) {
        var deep = request.queryParam("deep").map(Boolean::parseBoolean).orElse(false);
        log.info("[UniHalo][Mp] 收到自检请求：deep={}", deep);
        return selfCheckService.diagnose(deep)
            .map(items -> Map.of("items", items, "deep", deep))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 删除一条公众号稿件记录（公众号那边的草稿不受影响） */
    public Mono<ServerResponse> deleteIssue(ServerRequest request) {
        var name = request.pathVariable("name");
        return store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("公众号稿件不存在：" + name)))
            .flatMap(issue -> {
                if ("GENERATING".equals(issue.getState())) {
                    return Mono.error(new IllegalStateException("该稿件还在生成中，不能删除"));
                }
                return store.delete(issue);
            })
            .then(jsonOk(Map.of("deleted", true)))
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    // ── 组装 ──

    private MpStatusDTO buildStatus(pro.uhalo.uni.dto.MpConfigDTO config, List<MpIssue> issues,
        java.time.Instant nextRunAt) {
        var status = new MpStatusDTO();
        status.setEnabled(config.isEnabled());
        status.setConfigured(StringUtils.hasText(config.getAppId())
            && StringUtils.hasText(config.getAppSecret()));
        status.setAppIdMasked(maskAppId(config.getAppId()));
        status.setAuthor(config.getAuthor());
        status.setDeliveryMode(config.getDeliveryMode());
        status.setDeliveryModeLabel(MpConfigHelper.deliveryModeLabel(config.getDeliveryMode()));
        status.setContentSourceModeLabel(MpConfigHelper.sourceModeLabel(
            config.getContentSourceMode()));
        status.setContentGranularityLabel(
            MpConfigHelper.granularityLabel(config.getContentGranularity()));
        status.setNeedOpenComment(config.isNeedOpenComment());
        status.setUploadImages(config.isUploadImages());
        status.setPostToSite(config.isPostToSite());
        status.setScheduleEnabled(config.isScheduleEnabled());
        status.setScheduleTimes(config.getScheduleTimes());
        status.setTimezone(config.getTimezone());
        status.setNextRunAt(nextRunAt);
        var progress = issueService.progress();
        status.setGenerating(progress.running());
        status.setStage(progress.stage());
        status.setGenerateStartedAt(progress.startedAt());
        status.setGenerateError(progress.error());
        status.setPushing(pushService.isPushing());
        var push = pushService.progress();
        status.setPushStage(push.stage());
        status.setPushCurrent(push.current());
        status.setPushTotal(push.total());
        status.setPushError(push.error());
        var verdict = selfCheckService.lastVerdict();
        status.setSelfChecked(verdict.checked());
        status.setPushAvailable(!verdict.checked() || (verdict.coverMaterialOk()
            && verdict.uploadImageOk()));
        status.setSelfCheckSummary(verdict.summary());
        status.setPushResults(push.results().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.name());
            row.put("title", item.title());
            row.put("ok", item.ok());
            row.put("message", item.message());
            return row;
        }).toList());
        status.setDirections(MpConfigHelper.publishingDirections(config).stream()
            .map(item -> {
                java.util.Map<String, String> map = new LinkedHashMap<>();
                map.put("key", item.key());
                map.put("label", item.label());
                map.put("emoji", item.emoji());
                map.put("desc", item.description());
                return map;
            }).toList());
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("total", issues.size());
        for (var issue : issues) {
            counts.merge(issue.getState() == null ? "UNKNOWN" : issue.getState(), 1, Integer::sum);
        }
        status.setCounts(counts);
        status.setLastGeneratedAt(issues.stream().map(MpIssue::getCreatedAt)
            .filter(java.util.Objects::nonNull)
            .max(java.util.Comparator.naturalOrder()).orElse(null));
        status.setTitleLimit(MpTextUtils.MAX_TITLE_CHARS);
        status.setDigestLimit(MpTextUtils.MAX_DIGEST_CHARS);
        return status;
    }

    private static MpIssueSummaryDTO toSummary(MpIssue issue) {
        var summary = new MpIssueSummaryDTO();
        summary.setName(issue.getMetadata().getName());
        summary.setSourceKind(issue.getSourceKind());
        summary.setDate(issue.getDate());
        summary.setDirection(issue.getDirection());
        summary.setDirectionLabel(issue.getDirectionLabel());
        summary.setTrigger(issue.getTrigger());
        summary.setTitle(issue.getTitle());
        summary.setDigest(issue.getDigest());
        summary.setDigestWords(issue.getDigestWords());
        summary.setArticleCount(issue.getArticleCount());
        summary.setState(issue.getState());
        summary.setPushMode(issue.getPushMode());
        summary.setArticleUrl(issue.getArticleUrl());
        summary.setSitePostUrl(issue.getSitePostUrl());
        summary.setCover(issue.getCover());
        summary.setImageNote(issue.getImageNote());
        summary.setContentNote(issue.getContentNote());
        summary.setError(issue.getError());
        summary.setErrcode(issue.getErrcode());
        summary.setCreatedAt(issue.getCreatedAt());
        summary.setUpdatedAt(issue.getUpdatedAt());
        return summary;
    }

    private JsonNode parseJsonArray(String json) {
        if (!StringUtils.hasText(json)) {
            return OBJECT_MAPPER.createArrayNode();
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception ex) {
            return OBJECT_MAPPER.createArrayNode();
        }
    }

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String maskAppId(String appId) {
        if (!StringUtils.hasText(appId) || appId.length() <= 8) {
            return StringUtils.hasText(appId) ? "***" : "";
        }
        return appId.substring(0, 6) + "***" + appId.substring(appId.length() - 2);
    }

    private int limitParam(ServerRequest request) {
        var raw = request.queryParam("limit").orElse("");
        try {
            return Math.min(MAX_LIMIT, Math.max(1, Integer.parseInt(raw)));
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    private Mono<ServerResponse> jsonOk(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", StringUtils.hasText(message) ? message : "请求失败"));
    }
}
