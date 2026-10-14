package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiProbeResultDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.utils.AiProviderPresets;
import pro.uhalo.uni.utils.AiThinkingPolicy;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiErrorExplainer;
import pro.uhalo.uni.utils.DigestAiPricing;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容 chat/completions 客户端。
 *
 * <p>通道来自 {@link DigestAiChannelResolver}：清单非空时按清单顺序故障转移，
 * 清单留空时退回旧的主 / 兜底两个通道（行为与改造前一致）。同一轮运行内一旦降级就记住位置，
 * 后续批次直接从下一条通道开始，不再每条都先撞一次已经坏掉的通道。</p>
 */
@Slf4j
@Component
public class DigestAiClientImpl implements DigestAiClient {

    /** 长文摘要单次响应可以跑很久，给足 5 分钟 */
    private static final Duration AI_TIMEOUT = Duration.ofMinutes(5);

    /** 连通性测试是站长在页面上等的：不能挂着 5 分钟不给结果 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    /** 默认输出上限：10 篇一批的 JSON 容易被截断，顶到 8K */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * 思考关不掉的模型（GLM-5.3 起）改用这个上限：思考 token 与正文<b>共享</b> max_tokens，
     * 8K 会被推理先吃掉大半，成稿 JSON 拦腰断。这类模型官方标称输出上限 128K，16K 既留足
     * 余量也不会顶到别家的墙上。
     */
    private static final int THINKING_MAX_OUTPUT_TOKENS = 16384;

    /** 测试只验证「能不能通」，多要一个 token 都是浪费 */
    private static final int PROBE_MAX_TOKENS = 8;

    /** 但思考强制开启的模型，8 token 全花在推理上、正文是空的：给一点空间才测得出「能出字」 */
    private static final int THINKING_PROBE_TOKENS = 512;

    private static final double NORMAL_TEMPERATURE = 0.3;

    private static final double PROBE_TEMPERATURE = 0;

    private static final String PROBE_PROMPT = "只回复 OK 两个字母，不要标点或其它内容。";

    /** 余额端点在根路径上，不在 /v1 下 */
    private static final String BALANCE_PATH = "/user/balance";

    /** 余额查询是「顺手看一眼」，不能挂太久拖住整轮运行 */
    private static final Duration BALANCE_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_IN_MEMORY = 16 * 1024 * 1024;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    /**
     * 每个任务各自的降级位置（0 = 该任务通道列表的第一条）：一次失败即推进，不等下一次调用。
     *
     * <p>必须按任务分开记：评分在便宜通道上失败降级，不能把成稿的起点也一起带偏——
     * 否则「评分挂了」的连带后果是「成稿也换了个模型」，而站长完全看不出因果关系。</p>
     */
    private final Map<DigestAiTask, AtomicInteger> activeIndex = new ConcurrentHashMap<>();

    /** 本轮已经提示过「回退到全部通道」的任务：一轮只提醒一次，不刷屏 */
    private final Set<DigestAiTask> fallbackNotified = ConcurrentHashMap.newKeySet();

    /**
     * 实测「关不掉思考」的模型名（归一后）：撞一次 400 就记住，之后直接按强制思考发。
     *
     * <p>刻意<b>不</b>随 {@link #resetForNewRun()} 清空——这是模型的固有属性而不是本轮的降级位置，
     * 每天一轮日报都重新撞一次同样的 400 没有任何意义。</p>
     */
    private final Set<String> alwaysThinkingModels = ConcurrentHashMap.newKeySet();

    @Override
    public void resetForNewRun() {
        activeIndex.clear();
        fallbackNotified.clear();
    }

    @Override
    public Mono<DigestAiReplyDTO> call(DigestConfigDTO config, String prompt, DigestAiTask task) {
        return call(config, prompt, task, NORMAL_TEMPERATURE);
    }

    @Override
    public Mono<DigestAiReplyDTO> call(DigestConfigDTO config, String prompt, DigestAiTask task,
        double temperature) {
        var plan = DigestAiChannelResolver.plan(config, task);
        if (!plan.usable()) {
            return Mono.error(new IllegalStateException(plan.note()));
        }
        if (plan.fallback() && fallbackNotified.add(task)) {
            // 宽松回退是刻意的设计，但绝不能悄悄发生：读到这行日志就知道这一轮贵在哪
            log.warn("[UniHalo][Digest] {}", plan.note());
        }
        var channels = plan.channels();
        var cursor = activeIndex.computeIfAbsent(task, key -> new AtomicInteger(0));
        // 从「本任务本轮已经在用的那条」开始：前面几条已经证明是坏的，不再重试
        var start = Math.min(Math.max(cursor.get(), 0), channels.size() - 1);
        return completeFrom(channels, start, prompt, task, temperature, null);
    }

    @Override
    public Mono<DigestAiProbeResultDTO> probe(DigestAiProviderDTO channel) {
        var startedAt = System.nanoTime();
        return send(channel, PROBE_PROMPT, PROBE_MAX_TOKENS, PROBE_TEMPERATURE)
            .timeout(PROBE_TIMEOUT)
            .map(answer -> toProbeResult(channel, answer, elapsedMs(startedAt)))
            .onErrorResume(ex -> Mono.just(baseResult(channel, elapsedMs(startedAt))
                .status(null)
                .message(DigestAiErrorExplainer.explainThrowable(ex, channel.apiBase()))
                .detail(DigestAiErrorExplainer.snippet(String.valueOf(ex.getMessage())))
                .build()));
    }

    /**
     * 发一次请求；模型「不接受关闭思考」时就地纠正、再发一次。
     *
     * <p>按模型名分档是主路径，但智谱每隔几个月就把「可关」的边界往后推，模型名还可能带中转站
     * 自己加的前缀或后缀。撞一次 400 就把这条模型记成强制思考：比让整批成稿拆半重试、单篇补请求、
     * 最后全塌到 RSS 描述兜底便宜得多——那正是「配了强模型，文章反而更差」的来路。</p>
     *
     * <p>只在原本按 {@code DISABLE} 发过时才重试：按 {@code NONE}/{@code ALWAYS_ON} 发出去
     * 还收到同一句报错，说明是对端自己的口径问题，重发一遍只会多一份同样的失败。</p>
     */
    private Mono<AiHttpAnswer> send(DigestAiProviderDTO channel, String prompt, int maxTokens,
        double temperature) {
        return post(channel, prompt, tokensFor(stanceFor(channel.model()), maxTokens), temperature)
            .flatMap(answer -> {
                if (!AiThinkingPolicy.thinkingSwitchRejected(answer.status(), answer.body())
                    || stanceFor(channel.model()) != AiThinkingPolicy.Stance.DISABLE) {
                    return Mono.just(answer);
                }
                var model = AiThinkingPolicy.canonicalName(channel.model());
                if (alwaysThinkingModels.add(model)) {
                    log.warn("[UniHalo][Digest] 模型「{}」不接受关闭思考（{}），改按 reasoning_effort={} 重发，"
                            + "后续调用直接走这一档",
                        channel.model(), DigestAiErrorExplainer.snippet(answer.body()),
                        AiThinkingPolicy.EFFORT);
                }
                return post(channel, prompt,
                    tokensFor(stanceFor(channel.model()), maxTokens), temperature);
            });
    }

    /**
     * 依次尝试：第 index 条失败就把下标推进到下一条。
     *
     * <p>{@code lastError} 一直传到最后——全部失败时抛出的必须是<b>最后一条通道</b>的错误，
     * 否则站长看到的会是「第一条 Key 无效」，而真正该修的是最后那条。</p>
     */
    private Mono<DigestAiReplyDTO> completeFrom(List<DigestAiProviderDTO> channels, int index,
        String prompt, DigestAiTask task, double temperature, Throwable lastError) {
        if (index >= channels.size()) {
            return Mono.error(lastError == null
                ? new IllegalStateException("AI 通道调用失败：没有可用通道") : lastError);
        }
        var channel = channels.get(index);
        return complete(channel, prompt, MAX_OUTPUT_TOKENS, temperature)
            .onErrorResume(ex -> {
                if (index + 1 < channels.size()) {
                    var cursor = activeIndex.computeIfAbsent(task, key -> new AtomicInteger(0));
                    if (cursor.compareAndSet(index, index + 1)) {
                        log.warn("[UniHalo][Digest] 任务「{}」在通道「{}」上调用失败，切换到「{}」：{}",
                            task.getLabel(), channel.label(), channels.get(index + 1).label(),
                            ex.getMessage());
                    }
                }
                return completeFrom(channels, index + 1, prompt, task, temperature, ex);
            });
    }

    /**
     * 调用一条通道。
     *
     * <p>故意留成 protected：多通道故障转移的顺序是这里最要紧的行为，
     * 单测通过覆盖它来断言「第一条失败后走第二条、且一轮内不再回头」，
     * 不必真的起一个 HTTP 服务。</p>
     */
    protected Mono<DigestAiReplyDTO> complete(DigestAiProviderDTO channel, String prompt,
        int maxTokens, double temperature) {
        return send(channel, prompt, maxTokens, temperature)
            .timeout(AI_TIMEOUT)
            .map(answer -> {
                if (answer.status() < 200 || answer.status() >= 300) {
                    throw new IllegalStateException(DigestAiErrorExplainer.explain(
                        answer.status(), answer.body(), channel.apiBase(), channel.model()));
                }
                // 用量必须在这里就取出来：正文解析完就只剩文字了，费用再也算不出来
                var finishReason = parseFinishReason(answer.body());
                if (isTruncated(finishReason)) {
                    // 「撞到长度上限」是长 JSON 被砍半的第一嫌疑：日志里必须留下这一条，
                    // 否则下游只看到「JSON 解析失败」，无从判断是模型还是通道的问题。
                    // 上限在这里现算而不是用入参：思考关不掉的那一档会被抬到 16K，
                    // 报成 8192 会把站长引到完全错误的方向上。
                    log.warn("[UniHalo][Digest] 通道「{}」的输出达到长度上限被截断"
                        + "（finish_reason=length，max_tokens={}）：下游会拆小批次重试",
                        channel.label(),
                        tokensFor(stanceFor(channel.model()), maxTokens));
                }
                return DigestAiReplyDTO.of(extractContent(answer.body()), channel, channel.model(),
                    parseUsage(answer.body()), finishReason);
            })
            // 连接失败 / 超时这类「没有拿到响应」的错误也翻译成人话：
            // 运行报告里的失败原因与「测试连通性」给的结论必须是同一套说法，
            // 否则站长会在两个地方看到两种解释、不知道该信哪个。
            // 已经解释过的一律是 IllegalStateException（非 2xx 与响应解析），原样透传不重复包装。
            .onErrorResume(ex -> Mono.error(ex instanceof IllegalStateException ? ex
                : new IllegalStateException(
                    DigestAiErrorExplainer.explainThrowable(ex, channel.apiBase()), ex)))
            .doOnError(ex -> log.warn("[UniHalo][Digest] 通道「{}」调用失败（base={} model={}）：{}",
                channel.label(), channel.apiBase(), channel.model(), ex.getMessage()));
    }

    /**
     * 从响应体里取 {@code choices[0].finish_reason}。
     *
     * <p>取值只有诊断意义：{@code length} 说明模型是被输出上限砍断的（长 JSON 成稿最常见的
     * 「只回了一半」原因），日志里能直接看到，不必再从「JSON 解析失败」反推。
     * 有些中转站不回这个字段，取不到就返回空串。</p>
     */
    static String parseFinishReason(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        try {
            return OBJECT_MAPPER.readTree(raw).path("choices").path(0)
                .path("finish_reason").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    /** 是否因长度上限被截断（{@code finish_reason=length}） */
    static boolean isTruncated(String finishReason) {
        return finishReason != null && "length".equalsIgnoreCase(finishReason.trim());
    }

    /**
     * 从响应体里取 token 用量。
     *
     * <p>取不到就返回 {@code reported=false}：中转站不回 usage 是常态，
     * 这种调用只能计次数——按 0 token 算成「免费」比不显示更糟。</p>
     *
     * <p>缓存命中/未命中单独取（DeepSeek 回 {@code prompt_cache_hit_tokens} /
     * {@code prompt_cache_miss_tokens}）：两者价格差几十倍，合并成「输入 token」会把费用算错一个数量级。</p>
     */
    static DigestAiUsageDTO parseUsage(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DigestAiUsageDTO.unreported();
        }
        JsonNode usage;
        try {
            usage = OBJECT_MAPPER.readTree(raw).path("usage");
        } catch (Exception ex) {
            return DigestAiUsageDTO.unreported();
        }
        if (!usage.isObject()) {
            return DigestAiUsageDTO.unreported();
        }
        var prompt = intOf(usage, "prompt_tokens");
        var completion = intOf(usage, "completion_tokens");
        var total = intOf(usage, "total_tokens");
        var hit = intOf(usage, "prompt_cache_hit_tokens");
        var miss = intOf(usage, "prompt_cache_miss_tokens");
        // 智谱按 OpenAI 的写法只在 prompt_tokens_details 里给 cached_tokens：不接这个字段
        // 就把命中的那部分也按未命中价算（GLM-5.3-Flash 是 0.23 对 0.8，等于高估三倍多）
        if (hit <= 0) {
            hit = intOf(usage.path("prompt_tokens_details"), "cached_tokens");
        }
        var reasoning = intOf(usage.path("completion_tokens_details"), "reasoning_tokens");
        if (total <= 0) {
            total = prompt + completion;
        }
        // usage 是对象但一个数都没有（有的服务回 usage:{}）→ 仍然算「未上报」
        if (prompt <= 0 && completion <= 0 && total <= 0) {
            return DigestAiUsageDTO.unreported();
        }
        return new DigestAiUsageDTO(prompt, completion, total, hit, miss, reasoning, true);
    }

    private static int intOf(JsonNode node, String field) {
        var value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? Math.max(0, value.asInt()) : 0;
    }

    /**
     * 查账户余额（DeepSeek 官方接口，不消耗 token）。
     *
     * <p>端点不在 {@code /v1} 下，所以要先把通道地址里的版本段去掉再拼 {@code /user/balance}。
     * 非 DeepSeek 通道会 404 或回别的结构——那属于预期情况，返回「不支持」而不是错误信号，
     * 不能让一次余额查询把整轮日报带崩。</p>
     *
     * <p>已经知道「这家压根没有接口」的（智谱）先短路掉，不发那一次注定 404 的请求，
     * 而是直接把官方查看位置说给站长——见 {@link #balanceUnsupportedHint}。</p>
     */
    @Override
    public Mono<DigestAiBalanceDTO> queryBalance(DigestAiProviderDTO channel) {
        var key = channel.key();
        var label = channel.label();
        var hint = balanceUnsupportedHint(channel.apiBase());
        if (hint != null) {
            return Mono.just(DigestAiBalanceDTO.notSupported(key, label, channel.apiBase(), hint));
        }
        var base = balanceBase(channel.apiBase());
        if (!StringUtils.hasText(base) || !StringUtils.hasText(channel.apiKey())) {
            return Mono.just(DigestAiBalanceDTO.notSupported(key, label, channel.apiBase(),
                "这条通道没配完地址或 Key，查不了余额"));
        }
        return clientFor(base).get()
            .uri(BALANCE_PATH)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey())
            .accept(MediaType.APPLICATION_JSON)
            .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> toBalance(key, label, channel.apiBase(),
                    response.statusCode().value(), body)))
            .timeout(BALANCE_TIMEOUT)
            .onErrorResume(ex -> Mono.just(DigestAiBalanceDTO.failure(key, label,
                channel.apiBase(),
                DigestAiErrorExplainer.explainThrowable(ex, channel.apiBase()))));
    }

    private DigestAiBalanceDTO toBalance(String key, String label, String apiBase, int status,
        String body) {
        if (status == 404 || status == 405) {
            return DigestAiBalanceDTO.notSupported(key, label, apiBase,
                "该端点没有余额接口（余额查询是 DeepSeek 官方接口，多数中转站不支持）");
        }
        if (status == 401 || status == 403) {
            return DigestAiBalanceDTO.failure(key, label, apiBase, "API Key 无效，或没有查询余额的权限");
        }
        if (status < 200 || status >= 300) {
            return DigestAiBalanceDTO.failure(key, label, apiBase,
                "余额查询失败（HTTP " + status + "）：" + DigestAiErrorExplainer.snippet(body));
        }
        try {
            var root = OBJECT_MAPPER.readTree(body);
            var infos = root.path("balance_infos");
            if (!infos.isArray() || infos.isEmpty()) {
                return DigestAiBalanceDTO.notSupported(key, label, apiBase,
                    "返回体不是余额结构，该端点看起来不支持余额查询");
            }
            // 多币种账户取第一条：混合币种极少，显示第一种比什么都不显示有用
            var first = infos.get(0);
            return DigestAiBalanceDTO.balance(key, label, apiBase,
                first.path("currency").asText(""),
                first.path("total_balance").asText(""),
                first.path("granted_balance").asText(""),
                first.path("topped_up_balance").asText(""),
                root.path("is_available").asBoolean(true) ? "" : "账户余额不足，接口已不可用");
        } catch (Exception ex) {
            return DigestAiBalanceDTO.notSupported(key, label, apiBase,
                "余额响应解析失败：" + DigestAiErrorExplainer.snippet(ex.getMessage()));
        }
    }

    /**
     * 这家供应商没有对 API Key 开放的余额接口时，给出「该去哪看」的话；有接口的返回 null。
     *
     * <p>智谱是明确的：官方文档通篇只有 chat/completions 那一套端点，费用的问题全部指向
     * 控制台页面（财务总览 / 费用账单 / 资源包）。以前照样会去 GET 一次 {@code /user/balance}
     * 拿一个 404，站长看到的是一句像故障的「该端点没有余额接口」——把官方入口和扣费顺序
     * 直接说清楚才有用。</p>
     *
     * <p>包级可见留给单测：这条结论不该靠真发一次请求来验证。</p>
     */
    static String balanceUnsupportedHint(String apiBase) {
        var preset = AiProviderPresets.byHost(apiBase);
        if (preset.isEmpty() || !"zhipu".equals(preset.get().key())) {
            return null;
        }
        return "智谱不提供用 API Key 查余额的接口：请到控制台「财务总览」查看 "
            + DigestAiPricing.ZHIPU_FINANCE_URL
            + "。扣费顺序是先扣资源包、再扣现金余额；GLM Coding Plan 套餐不按量扣余额，"
            + "所以这里的报告只能给出估算花费";
    }

    /** 余额端点在根路径上：把 {@code .../v1} 这段版本号去掉 */
    static String balanceBase(String apiBase) {
        var normalized = DigestAiChannelResolver.normalizeBase(apiBase);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceFirst("/v\\d+$", "");
    }

    /**
     * 发一次请求。
     *
     * <p>protected 与包级可见的返回值都是留给单测的接缝：「测试连通性」的结论完全由
     * 「状态码 + 响应体」推导，把它替换成构造好的响应就能逐条断言文案，
     * 不必真的起一个 HTTP 服务。</p>
     *
     * <p>{@code maxTokens} 已由 {@link #send} 按档位定档，这里只负责照发，不再二次换算。</p>
     */
    protected Mono<AiHttpAnswer> post(DigestAiProviderDTO channel, String prompt, int maxTokens,
        double temperature) {
        return clientFor(channel.apiBase()).post()
            .uri(AiProviderPresets.chatCompletionsUrl(channel.apiBase()))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(buildRequestBody(channel.model(), prompt, maxTokens, temperature,
                stanceFor(channel.model())))
            // 用 exchangeToMono 而不是 retrieve()：测试接口要原样回报状态码与响应体，
            // 4xx/5xx 也不能变成异常丢掉正文（正文里才有「模型名不存在」这类关键信息）
            .exchangeToMono(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(text -> new AiHttpAnswer(response.statusCode().value(), text)));
    }

    /**
     * 这条模型名按哪种方式处理思考参数：按名判断的档位，被实测驳回过的一律按「关不掉」处理。
     */
    private AiThinkingPolicy.Stance stanceFor(String model) {
        var stance = AiThinkingPolicy.stance(model);
        return stance == AiThinkingPolicy.Stance.DISABLE
            && alwaysThinkingModels.contains(AiThinkingPolicy.canonicalName(model))
            ? AiThinkingPolicy.Stance.ALWAYS_ON : stance;
    }

    /** 思考关不掉时输出上限必须给推理留位置，否则正文照旧被拦腰截断 */
    static int tokensFor(AiThinkingPolicy.Stance stance, int maxTokens) {
        return stance == AiThinkingPolicy.Stance.ALWAYS_ON ? thinkingTokens(maxTokens) : maxTokens;
    }

    /** 探测请求只要挤出几个字证明能出正文，正式调用要给整批 JSON 留位置 */
    private static int thinkingTokens(int maxTokens) {
        return maxTokens <= PROBE_MAX_TOKENS ? THINKING_PROBE_TOKENS : THINKING_MAX_OUTPUT_TOKENS;
    }

    /**
     * 组装 chat/completions 请求体（包级可见留给单测）。
     *
     * <p>日报的每一类调用都是「按格式回 JSON / 定长段落」的结构化输出，一次还是 10 篇一批的
     * 长 JSON——思考只会挤占正文预算，能关就关；关不掉的（GLM-5.3 起）按
     * {@link AiThinkingPolicy} 降档，发 {@code disabled} 会让整批请求直接 400。
     * 其它供应商不带 thinking 字段：控制方式各家不同（o 系列根本不走这个字段），
     * 多发只添风险。</p>
     */
    static Map<String, Object> buildRequestBody(String model, String prompt, int maxTokens,
        double temperature, AiThinkingPolicy.Stance stance) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", temperature);
        body.put("top_p", 0.8);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        body.putAll(AiThinkingPolicy.requestFields(stance));
        return body;
    }

    /** 一次 HTTP 往返的原始结果：故障转移报错与「测试连通性」需要的是同一份材料 */
    record AiHttpAnswer(int status, String body) {
    }

    private DigestAiProbeResultDTO toProbeResult(DigestAiProviderDTO channel, AiHttpAnswer answer,
        long latencyMs) {
        var builder = baseResult(channel, latencyMs)
            .status(answer.status())
            .detail(DigestAiErrorExplainer.snippet(answer.body()));
        if (answer.status() < 200 || answer.status() >= 300) {
            return builder.ok(false)
                .message(DigestAiErrorExplainer.explain(answer.status(), answer.body(),
                    channel.apiBase(), channel.model()))
                .build();
        }
        try {
            var root = OBJECT_MAPPER.readTree(answer.body());
            builder.servedModel(root.path("model").asText(""));
            var usage = root.path("usage").path("total_tokens");
            if (usage.isNumber()) {
                builder.totalTokens(usage.asInt());
            }
            // 是否是 chat/completions 结构：连通性的判定标准是「像不像这个协议」，
            // 不是「模型有没有挤出正文」——思考型模型（GLM-4.5+/o 系列等）在 8 token 的
            // 测试限额里可能把额度全花在推理上，正文是空的，但端点本身完全可用
            var chatLike = root.path("choices").isArray() && !root.path("choices").isEmpty();
            String reply;
            try {
                reply = extractContent(answer.body());
            } catch (IllegalStateException ex) {
                if (chatLike) {
                    return builder.ok(true).message(noContentButConnected()).build();
                }
                // 200 且连 choices 都没有：按「不是兼容端点」解释，
                // 这条最常见（中转站回自己的 JSON 结构），比丢一句解析失败有用得多
                return builder.ok(false)
                    .message(DigestAiErrorExplainer.explain(200, answer.body(), channel.apiBase(),
                        channel.model()))
                    .build();
            }
            if (!StringUtils.hasText(reply)) {
                if (chatLike) {
                    return builder.ok(true).message(noContentButConnected()).build();
                }
                return builder.ok(false)
                    .message(DigestAiErrorExplainer.explain(200, answer.body(), channel.apiBase(),
                        channel.model()))
                    .build();
            }
            return builder.ok(true)
                .reply(DigestAiErrorExplainer.snippet(reply))
                .message("连通正常")
                .build();
        } catch (Exception ex) {
            return builder.ok(false)
                .message("返回内容解析失败：" + DigestAiErrorExplainer.snippet(ex.getMessage()))
                .build();
        }
    }

    /** 测试专用提示：结构是 chat/completions 但 8 token 里没产出正文（思考型模型的典型表现） */
    private static String noContentButConnected() {
        return "端点连通（返回是 chat/completions 结构）。模型在测试的极小 token 限额内"
            + "没产出可见正文——思考/推理型模型（GLM-4.5+、o 系列等）会先消耗 token 思考，"
            + "这是正常现象，正式调用不受影响";
    }

    private static DigestAiProbeResultDTO.DigestAiProbeResultDTOBuilder baseResult(
        DigestAiProviderDTO channel, long latencyMs) {
        return DigestAiProbeResultDTO.builder()
            .key(channel.key())
            .label(channel.label())
            .apiBase(channel.apiBase())
            .url(AiProviderPresets.chatCompletionsUrl(channel.apiBase()))
            .model(channel.model())
            .latencyMs(latencyMs);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * 从响应里取第一个 choice 的文本内容。
     *
     * <p>部分兼容服务把 content 返回成 [{type:text, text:...}] 数组，需要拼起来。</p>
     */
    private String extractContent(String raw) {
        var content = parseContentNode(raw);
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            var builder = new StringBuilder();
            for (var item : content) {
                if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        if (content.isMissingNode() || content.isNull()) {
            var error = parseError(raw);
            throw new IllegalStateException(StringUtils.hasText(error)
                ? "AI 未返回内容：" + error
                : "AI 未返回内容：" + DigestAiErrorExplainer.snippet(raw));
        }
        return content.asText("");
    }

    private static com.fasterxml.jackson.databind.JsonNode parseContentNode(String raw) {
        try {
            return OBJECT_MAPPER.readTree(raw)
                .path("choices").path(0).path("message").path("content");
        } catch (Exception ex) {
            throw new IllegalStateException(
                "AI 响应解析失败：" + DigestAiErrorExplainer.snippet(raw), ex);
        }
    }

    /** 响应里的 error.message：兼容服务把失败写在 200 里时，这句话就是唯一线索 */
    private static String parseError(String raw) {
        try {
            return OBJECT_MAPPER.readTree(raw).path("error").path("message").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private WebClient clientFor(String base) {
        return clients.computeIfAbsent(base, key -> WebClient.builder()
            .baseUrl(key)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
            .build());
    }
}
