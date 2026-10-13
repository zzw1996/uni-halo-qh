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
import pro.uhalo.uni.dto.ToutiaoConfigDTO;
import pro.uhalo.uni.dto.ToutiaoRequest;
import pro.uhalo.uni.infra.ToutiaoArticleStore;
import pro.uhalo.uni.scheme.ToutiaoArticle;
import pro.uhalo.uni.services.ToutiaoService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日头条号后台接口（只挂 console 分组：管理员的写稿台，小程序端不消费）。
 *
 * <p>生成是异步的（一次成稿要跑 30~90 秒，同步等会被网关超时掐断）：
 * 这里只负责触发并立刻返回，页面靠「状态」接口的 running/stage 观察进度，
 * 结果从历史列表里取。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToutiaoController {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToutiaoService service;
    private final ToutiaoArticleStore store;

    /** 状态：开关 + 默认参数 + 生成进度（页面初始化与轮询共用） */
    public Mono<ServerResponse> status(ServerRequest request) {
        return service.loadConfig().flatMap(config -> {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("enabled", config.isEnabled());
            payload.put("defaults", defaultsOf(config));
            payload.put("taskKey", "toutiao");
            var progress = service.progress();
            payload.put("running", progress.running());
            payload.put("stage", progress.stage());
            payload.put("startedAt", progress.startedAt());
            payload.put("finishedAt", progress.finishedAt());
            payload.put("error", progress.error());
            payload.put("articleName", progress.articleName());
            return jsonOk(payload);
        });
    }

    /** 触发一次生成（立刻返回；页面轮询 /toutiao/status 直到 running=false） */
    public Mono<ServerResponse> generate(ServerRequest request) {
        return request.bodyToMono(ToutiaoRequest.class)
            .defaultIfEmpty(new ToutiaoRequest())
            .flatMap(body -> {
                if (service.isRunning()) {
                    return badRequest("已有一次生成任务在执行中，请等它跑完再试");
                }
                return service.loadConfig().flatMap(config -> {
                    // 「先返回、后台跑」的接口必须在触发前校验前置条件：
                    // 否则页面会看到「已开始」却什么都没发生（与公众号同一教训）
                    if (!config.isEnabled()) {
                        return badRequest(
                            "今日头条号功能未开启：请先在插件设置「今日头条号」中打开总开关");
                    }
                    if (!StringUtils.hasText(body.getKeyword())) {
                        return badRequest("请先填写主题关键词");
                    }
                    log.info("[UniHalo][Toutiao] 收到后台生成请求：keyword={}",
                        body.getKeyword().trim());
                    service.generate(body.getKeyword(), body.getStyle(), body.getWords(),
                            body.getTitleCount())
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                            article -> log.info("[UniHalo][Toutiao] 生成结束：name={} state={}",
                                article.getMetadata().getName(), article.getState()),
                            ex -> log.warn("[UniHalo][Toutiao] 后台生成失败：{}", ex.getMessage(), ex));
                    return jsonOk(Map.of(
                        "started", true,
                        "message", "已开始生成（写稿 + 配图，共约 1~3 分钟）；"
                            + "完成后会自动出现在结果区与历史里"));
                });
            })
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 历史列表（摘要，不含正文） */
    public Mono<ServerResponse> listHistory(ServerRequest request) {
        return store.listRecent(limitParam(request))
            .map(articles -> Map.of("items",
                articles.stream().map(ToutiaoController::toSummary).toList()))
            .flatMap(this::jsonOk)
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 一条记录的完整内容（标题候选 / 正文 / 标签 / 花费） */
    public Mono<ServerResponse> getArticle(ServerRequest request) {
        var name = request.pathVariable("name");
        return store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("记录不存在：" + name)))
            .flatMap(article -> jsonOk(toDetail(article)))
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    /** 删除一条记录（还在生成中的拒绝删除；进程重启留下的「已中断」记录可以删） */
    public Mono<ServerResponse> deleteArticle(ServerRequest request) {
        var name = request.pathVariable("name");
        return store.fetch(name)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("记录不存在：" + name)))
            .flatMap(article -> {
                if ("GENERATING".equals(article.getState()) && !isStale(article)) {
                    return Mono.error(new IllegalStateException("这条记录还在生成中，不能删除"));
                }
                return store.delete(article);
            })
            .then(jsonOk(Map.of("deleted", true)))
            .onErrorResume(ex -> badRequest(ex.getMessage()));
    }

    // ── 组装 ──

    private static Map<String, Object> defaultsOf(ToutiaoConfigDTO config) {
        var defaults = new LinkedHashMap<String, Object>();
        defaults.put("words", config.getWords());
        defaults.put("titleCount", config.getTitleCount());
        defaults.put("styles", config.getStyles());
        defaults.put("defaultStyle", config.getDefaultStyle());
        return defaults;
    }

    private static Map<String, Object> toSummary(ToutiaoArticle article) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", article.getMetadata().getName());
        row.put("keyword", article.getKeyword());
        row.put("style", article.getStyle());
        row.put("words", article.getWords());
        row.put("title", firstTitle(article.getTitles()));
        row.put("state", displayState(article));
        row.put("note", article.getNote());
        row.put("error", article.getError());
        row.put("channel", article.getChannel());
        row.put("bodyLength", article.getBody() == null ? 0 : article.getBody().length());
        row.put("imageCount", splitLines(article.getImages()).size());
        row.put("imageNote", article.getImageNote());
        row.put("createdAt", article.getCreatedAt());
        row.put("finishedAt", article.getFinishedAt());
        return row;
    }

    private static Map<String, Object> toDetail(ToutiaoArticle article) {
        var payload = new LinkedHashMap<String, Object>(toSummary(article));
        payload.put("configuredTitleCount", article.getTitleCount());
        payload.put("titleCandidates", splitLines(article.getTitles()));
        payload.put("body", article.getBody());
        payload.put("tags", splitTags(article.getTags()));
        payload.put("images", splitLines(article.getImages()));
        payload.put("aiCost", parseJsonNode(article.getAiCost()));
        return payload;
    }

    /** 展示用状态：进程重启留下的 GENERATING 记录永远不会收尾，按「已中断」显示 */
    private static String displayState(ToutiaoArticle article) {
        if ("GENERATING".equals(article.getState()) && isStale(article)) {
            return "INTERRUPTED";
        }
        return article.getState();
    }

    private static boolean isStale(ToutiaoArticle article) {
        var createdAt = article.getCreatedAt();
        return createdAt != null && createdAt.isBefore(
            Instant.now().minus(Duration.ofMinutes(ToutiaoService.STALE_MINUTES)));
    }

    private static String firstTitle(String titles) {
        var list = splitLines(titles);
        return list.isEmpty() ? "" : list.get(0);
    }

    /** 标题候选：按行拆分（存储用换行分隔，避免 JSON 二次转义） */
    private static List<String> splitLines(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R"))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .toList();
    }

    private static List<String> splitTags(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("[,，]"))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .toList();
    }

    private static JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int limitParam(ServerRequest request) {
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
