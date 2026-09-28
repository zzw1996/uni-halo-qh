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
import pro.uhalo.uni.dto.DigestAiCostDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiProvidersSaveRequest;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestCategoryViewDTO;
import pro.uhalo.uni.dto.DigestOverviewDTO;
import pro.uhalo.uni.dto.DigestPostResultDTO;
import pro.uhalo.uni.dto.DigestRunDetailDTO;
import pro.uhalo.uni.dto.DigestRunRequest;
import pro.uhalo.uni.dto.DigestRunSummaryDTO;
import pro.uhalo.uni.dto.PostClassifyApplyRequest;
import pro.uhalo.uni.infra.DigestPersistence;
import pro.uhalo.uni.scheme.DigestReport;
import pro.uhalo.uni.services.DigestPostWriter;
import pro.uhalo.uni.services.DigestProgressTracker;
import pro.uhalo.uni.services.DigestScheduler;
import pro.uhalo.uni.services.DigestService;
import pro.uhalo.uni.services.DigestTaxonomyService;
import pro.uhalo.uni.services.PostTaxonomyService;
import pro.uhalo.uni.services.impl.DigestAiModelService;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import run.halo.app.plugin.ReactiveSettingFetcher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 日报后台接口。
 *
 * <p>「立即生成」是异步的：一次生成要跑 1-3 分钟以上，同步等结果必然把浏览器请求拖到网关超时。
 * 这里只负责触发，前台通过概览接口的 running 标记 + 列表刷新观察进度。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestController {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    /** 旧文章归类一次最多分析多少篇（与 PostTaxonomyService 里的上限保持一致） */
    private static final int MAX_CLASSIFY_LIMIT = 30;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DigestService digestService;
    private final DigestScheduler digestScheduler;
    private final DigestProgressTracker progressTracker;
    private final DigestPostWriter postWriter;
    private final DigestTaxonomyService taxonomyService;
    private final PostTaxonomyService postTaxonomyService;
    private final DigestAiModelService aiModelService;
    private final ReactiveSettingFetcher settingFetcher;

    /** 开关状态 + 下次执行时间 + 最近一次运行 */
    public Mono<ServerResponse> overview(ServerRequest request) {
        return loadConfig()
            .flatMap(config -> digestScheduler.nextRunInstant()
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .flatMap(nextRun -> digestService.listReports(DEFAULT_LIMIT)
                    .map(reports -> buildOverview(config, reports, nextRun.orElse(null)))))
            .flatMap(this::jsonOk);
    }

    public Mono<ServerResponse> listReports(ServerRequest request) {
        var limit = limitParam(request);
        return digestService.listReports(limit)
            .map(reports -> digestReportsToResponse(reports))
            .flatMap(this::jsonOk);
    }

    public Mono<ServerResponse> getReport(ServerRequest request) {
        var name = request.pathVariable("name");
        return digestService.getReport(name)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("日报记录不存在：" + name)))
            .map(report -> DigestRunDetailDTO.builder()
                .summary(toSummary(report))
                .markdown(report.getMarkdown())
                .stats(parseStats(report.getStats()))
                .build())
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 残留文章清理：{@code ?dryRun=true} 只预览清单，false 才执行删除。
     *
     * <p>删除前会自动摘掉所有终态器——站点上若有插件（如 AI 助手）挂了终态器且其协调器
     * 一直失败，文章会永久卡在「删除中」，从后台删不掉。</p>
     */
    public Mono<ServerResponse> cleanupResidues(ServerRequest request) {
        var dryRun = request.queryParam("dryRun").map(Boolean::parseBoolean).orElse(true);
        log.info("[UniHalo][Digest] 收到残留清理请求：dryRun={}", dryRun);
        return (dryRun ? postWriter.previewResidues() : postWriter.previewResidues()
            .flatMap(items -> postWriter.purgeResidues()
                .doOnNext(names -> log.warn("[UniHalo][Digest] 已清理残留文章 {} 篇：{}",
                    names.size(), names))
                .thenReturn(items)))
            .map(items -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("dryRun", dryRun);
                payload.put("count", items.size());
                payload.put("items", items);
                payload.put("message", dryRun
                    ? "共找到 " + items.size() + " 篇未发布成功的残留文章，确认后执行清理"
                    : "已清理 " + items.size() + " 篇残留文章（含终态器摘除）");
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 删除一条运行记录。只删记录本身，已发布的文章不受影响。
     */
    public Mono<ServerResponse> deleteReport(ServerRequest request) {
        var name = request.pathVariable("name");
        log.info("[UniHalo][Digest] 收到删除运行记录请求：name={}", name);
        return digestService.deleteReport(name)
            .then(jsonOk(Map.of("deleted", true, "message", "已删除该条运行记录")))
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    // ── 旧文章归类 ──

    /** 站点上还有多少篇文章没进主题体系 */
    public Mono<ServerResponse> classifyStatus(ServerRequest request) {
        return postTaxonomyService.status()
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 分析下一批未归类文章（只给建议，不改动任何文章）。
     *
     * <p>刻意做成「预览」而不是直接归类：判定要花 AI 调用，写回又要改站点数据，
     * 让站长看过建议再点头，比一键批量改掉几十篇文章可逆得多。</p>
     */
    public Mono<ServerResponse> classifyPreview(ServerRequest request) {
        var limit = classifyLimitParam(request);
        log.info("[UniHalo][Digest] 收到旧文章归类预览请求：limit={}", limit);
        return postTaxonomyService.preview(limit)
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 应用站长勾选采纳的建议：只追加分类与标签，逐条回报结果 */
    public Mono<ServerResponse> classifyApply(ServerRequest request) {
        return request.bodyToMono(PostClassifyApplyRequest.class)
            .flatMap(body -> postTaxonomyService.apply(body))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 发布环境自检：令牌、作者、分类标签逐项返回结论与处置建议 */
    public Mono<ServerResponse> preflight(ServerRequest request) {
        return digestService.preflight()
            .flatMap(items -> jsonOk(Map.of("items", items)))
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    // ── 分类清单 ──

    /**
     * 当前生效的分类清单，以及每一行「发布时会落到哪里」的结论。
     *
     * <p>配置页只填 key 与名字，真正落库还要看站点的已有分类与落库方式，
     * 所以把结论算出来给后台面板看——尤其是 reuse / map 模式下匹配不上的那几行，
     * 它们会被静默跳过，不提示就等于让站长以为配置生效了。</p>
     */
    public Mono<ServerResponse> digestCategories(ServerRequest request) {
        return loadConfig()
            .flatMap(config -> taxonomyService.categoryMatchNames()
                .map(existing -> DigestCategoryViewDTO.of(
                    DigestCategoryCatalog.from(config.getDigestCategories()),
                    config.getCategoryMode(), config.getUnknownCategoryPolicy(), existing)))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 列出插件自动创建、且已经没有文章引用的站点分类 */
    public Mono<ServerResponse> autoCreatedCategories(ServerRequest request) {
        return taxonomyService.listAutoCreatedCategories()
            .map(items -> {
                var unused = items.stream().filter(item -> item.getPostCount() == 0).count();
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("count", items.size());
                payload.put("unusedCount", unused);
                payload.put("items", items);
                payload.put("message", items.isEmpty()
                    ? "没有找到插件自动创建的分类"
                    : "插件自动创建的分类共 " + items.size() + " 个，其中 " + unused
                        + " 个已无文章引用（可清理）");
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 清理没有文章引用的自动分类。
     *
     * <p>只删带 {@code uni.uhalo.pro/auto-generated} 标签的分类：站长自己建的分类哪怕没文章
     * 也不替他动；有文章引用的一律跳过并说明原因，避免把文章的分类指向空。</p>
     */
    public Mono<ServerResponse> purgeAutoCreatedCategories(ServerRequest request) {
        log.info("[UniHalo][Digest] 收到自动分类清理请求");
        return taxonomyService.purgeUnusedAutoCreatedCategories()
            .map(items -> {
                var deleted = items.stream().filter(item -> item.isDeleted()).count();
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("count", items.size());
                payload.put("deletedCount", deleted);
                payload.put("items", items);
                payload.put("message", deleted == 0
                    ? "没有可清理的分类（都还有文章在用）"
                    : "已清理 " + deleted + " 个没有文章引用的自动分类");
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    // ── 大模型通道 ──

    /** 通道清单 + 当前生效通道 + 旧的主 / 兜底通道现状（Key 一律掩码下发） */
    public Mono<ServerResponse> aiModels(ServerRequest request) {
        return aiModelService.load()
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 保存清单：只改 aiProviders 一项，其余配置字段原样保留 */
    public Mono<ServerResponse> saveAiModels(ServerRequest request) {
        return request.bodyToMono(DigestAiProvidersSaveRequest.class)
            .defaultIfEmpty(new DigestAiProvidersSaveRequest())
            .flatMap(body -> aiModelService.save(body.getProviders()))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 查各通道的账户余额（DeepSeek 官方接口，随时可点，不消耗 token）。
     *
     * <p>只查「可用通道」，最多几条：余额是账号级数据，通道再多也不会有额外信息。</p>
     */
    public Mono<ServerResponse> aiBalances(ServerRequest request) {
        return aiModelService.balances()
            .map(map -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("items", map);
                payload.put("message", map.isEmpty()
                    ? "没有可查询的通道：先添加通道并填写 API Key"
                    : "余额查询是 DeepSeek 官方接口，其它端点会显示「不支持」，属正常现象");
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 从官网读一次模型单价（面板「从官网刷新单价」）。
     *
     * <p>官方不提供价格 API，只能读那份价目表页面；读不到就明确报错并继续用内置表。</p>
     */
    public Mono<ServerResponse> refreshAiPrices(ServerRequest request) {
        return aiModelService.refreshPrices()
            .map(view -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("prices", view.prices());
                payload.put("models", view.providers());
                payload.put("message", "已从官网读取 " + view.prices().items().size()
                    + " 个模型的单价（" + view.prices().source() + "）");
                return payload;
            })
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 测试一条通道的连通性。
     *
     * <p>请求体就是面板上的那一行（含草稿行）：Key 可以是掩码串或空值，
     * 服务端按通道标识回查已保存的那把，页面不必为了测试而回传明文密钥。</p>
     */
    public Mono<ServerResponse> testAiModel(ServerRequest request) {
        return request.bodyToMono(DigestAiProviderDTO.class)
            .flatMap(provider -> aiModelService.test(provider))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /**
     * 立即生成。
     *
     * <p>是否发布可以从查询串给（{@code ?publish=true}，方便 curl 与独立管理页），
     * 也可以从 body 给（{@code {"publish": true}}）；都不给时按插件配置里的「自动发布」决定。</p>
     */
    public Mono<ServerResponse> run(ServerRequest request) {
        return request.bodyToMono(DigestRunRequest.class)
            .defaultIfEmpty(new DigestRunRequest())
            .flatMap(body -> {
                if (digestService.isRunning()) {
                    return badRequest("已有一次日报生成任务在执行中，请等它跑完");
                }
                return loadConfig().flatMap(config -> {
                    var publish = resolvePublishFlag(request, body, config);
                    var slot = DigestPersistence.manualSlot(
                        ZonedDateTime.now(DigestConfigHelper.zoneOf(config.getTimezone())));
                    log.info("[UniHalo][Digest] 收到后台手动触发：slot={} publish={}", slot, publish);
                    // 订阅后立刻返回，真正的流水线在弹性线程池上跑，避免占用事件循环线程
                    digestService.run(slot, publish)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                            report -> log.info("[UniHalo][Digest] 后台手动任务结束 name={} status={}",
                                report.getMetadata().getName(), report.getStatus()),
                            ex -> log.warn("[UniHalo][Digest] 后台手动任务失败：{}", ex.getMessage(), ex));
                    return jsonOk(Map.of(
                        "started", true,
                        "slot", slot,
                        "publish", publish,
                        "message", "已开始生成，预计需要 1-3 分钟，可刷新列表查看进度"));
                });
            })
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    private static boolean resolvePublishFlag(ServerRequest request, DigestRunRequest body,
        DigestConfigDTO config) {
        var fromQuery = request.queryParam("publish")
            .map(value -> Boolean.parseBoolean(value.trim()))
            .orElse(null);
        if (fromQuery != null) {
            return fromQuery;
        }
        return body.getPublish() != null ? body.getPublish() : config.isPublishEnabled();
    }

    /**
     * 把已生成但没发布成功的报告重新发布一次。
     *
     * <p>与「立即生成」同样是异步的：发布要等服务端生成发布快照，可能持续几分钟，
     * 同步等待会把浏览器请求拖到网关超时。</p>
     */
    public Mono<ServerResponse> publishReport(ServerRequest request) {
        var name = request.pathVariable("name");
        if (digestService.isRunning()) {
            return badRequest("已有一次日报任务在执行中，请等它跑完");
        }
        log.info("[UniHalo][Digest] 收到后台重新发布请求：name={}", name);
        digestService.publishReport(name)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                report -> log.info("[UniHalo][Digest] 重新发布结束 name={} state={} 共 {} 篇",
                    name, report.getPublishState(),
                    report.getPostCount() == null ? 0 : report.getPostCount()),
                ex -> log.warn("[UniHalo][Digest] 重新发布失败 name={}：{}", name, ex.getMessage(), ex));
        return jsonOk(Map.of(
            "started", true,
            "message", "已开始发布，正文较长时需要几分钟确认，可刷新列表查看状态"));
    }

    // ── 组装 ──

    private DigestOverviewDTO buildOverview(DigestConfigDTO config, java.util.List<DigestReport> reports,
        java.time.Instant nextRunAt) {
        return DigestOverviewDTO.builder()
            .enabled(config.isEnabled())
            .scheduleEnabled(config.isScheduleEnabled())
            .scheduleTimes(config.getScheduleTimes())
            .timezone(config.getTimezone())
            .nextRunAt(nextRunAt)
            .running(digestService.isRunning())
            .progress(progressTracker.snapshot())
            .totalReports(reports.size())
            .lastReport(reports.isEmpty() ? null : toSummary(reports.get(0)))
            .build();
    }

    private Map<String, Object> digestReportsToResponse(java.util.List<DigestReport> reports) {
        var items = reports.stream().map(this::toSummary).toList();
        return Map.of("items", items, "total", items.size());
    }

    private DigestRunSummaryDTO toSummary(DigestReport report) {
        return DigestRunSummaryDTO.builder()
            .name(report.getMetadata() == null ? null : report.getMetadata().getName())
            .date(report.getDate())
            .slot(report.getSlot())
            .status(report.getStatus())
            .publishState(report.getPublishState())
            .articleCount(report.getArticleCount())
            .postCount(report.getPostCount())
            .posts(parsePosts(report.getPosts()))
            .postName(report.getPostName())
            .postUrl(report.getPostUrl())
            .cover(report.getCover())
            .error(report.getError())
            .startedAt(report.getStartedAt())
            .finishedAt(report.getFinishedAt())
            .durationMs(durationMs(report))
            .aiCosts(aiCostOf(report).map(DigestAiCostDTO::getTotals).orElse(List.of()))
            .aiCostText(aiCostOf(report).map(DigestAiCostDTO::summaryText).orElse(""))
            .build();
    }

    /**
     * 从运行记录的 stats 里取花费块。
     *
     * <p>老报告没有这个字段，或 stats 本身是坏的：返回空，让前端显示「-」——
     * 不能当成 0（0 会被读成「这次没花钱」）。</p>
     */
    private Optional<DigestAiCostDTO> aiCostOf(DigestReport report) {
        var stats = parseStats(report.getStats());
        if (stats == null || !stats.has("aiCost") || stats.path("aiCost").isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.treeToValue(stats.path("aiCost"),
                DigestAiCostDTO.class));
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 运行记录的花费块解析失败，按未采集处理：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 各篇文章的发布结果，反序列化失败时返回空列表并告警（不要静默吞掉） */
    private static List<DigestPostResultDTO> parsePosts(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class,
                    DigestPostResultDTO.class));
        } catch (Exception ex) {
            log.warn("[UniHalo][Digest] 发布结果解析失败（name 列表仍可正常展示）：{}",
                ex.getMessage());
            return List.of();
        }
    }

    private static Long durationMs(DigestReport report) {
        if (report.getStartedAt() == null || report.getFinishedAt() == null) {
            return null;
        }
        return Duration.between(report.getStartedAt(), report.getFinishedAt()).toMillis();
    }

    private static JsonNode parseStats(String stats) {
        if (!StringUtils.hasText(stats)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(stats);
        } catch (Exception ex) {
            return null;
        }
    }

    private Mono<DigestConfigDTO> loadConfig() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }

    private int limitParam(ServerRequest request) {
        var raw = request.queryParam("limit").orElse("");
        try {
            return Math.min(MAX_LIMIT, Math.max(1, Integer.parseInt(raw)));
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    /**
     * 旧文章归类的批次大小。
     *
     * <p>与运行记录列表分开取默认值：那边默认 30 是「列表看几行」，这边是
     * 「一次 AI 调用分析几篇」，默认值和上限都不一样，混用会把一批的篇数悄悄放大。</p>
     */
    private int classifyLimitParam(ServerRequest request) {
        var raw = request.queryParam("limit").orElse("");
        try {
            return Math.min(MAX_CLASSIFY_LIMIT, Math.max(1, Integer.parseInt(raw)));
        } catch (NumberFormatException ex) {
            return PostTaxonomyService.BATCH_SIZE;
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
