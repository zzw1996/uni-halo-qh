package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.utils.AiThinkingPolicy;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多通道故障转移与连通性测试。
 *
 * <p>替换掉「发一次请求」这一层：通道顺序、降级记忆、错误结论都能在不起 HTTP 服务的前提下
 * 逐条钉住。</p>
 */
class DigestAiClientImplTest {

    /** 按脚本决定哪条通道失败，并记录每条被调用的次数 */
    private static class StubClient extends DigestAiClientImpl {

        private final List<String> calls = new ArrayList<>();
        private final Map<String, AiHttpAnswer> answers = new LinkedHashMap<>();

        /** 记录答复（默认 200 + 正常 content），按 base 决定回什么 */
        void answer(String apiBase, AiHttpAnswer answer) {
            answers.put(apiBase, answer);
        }

        /** 记「这条通道会抛异常」 */
        void fail(String apiBase) {
            answers.put(apiBase, null);
        }

        @Override
        protected Mono<AiHttpAnswer> post(DigestAiProviderDTO channel, String prompt, int maxTokens,
            double temperature) {
            calls.add(channel.key());
            var answer = answers.get(channel.apiBase());
            if (answer == null) {
                // 与真实 WebClient 的失败信息对齐，才能验证错误翻译走的是哪一条分支
                return Mono.error(new IllegalStateException(
                    "Connection refused: " + channel.apiBase()));
            }
            return Mono.just(answer);
        }
    }

    /** 记录每次请求实际用到的 max_tokens，并按脚本依次回包（用于验证思考档位与自愈重试） */
    private static class RecordingClient extends DigestAiClientImpl {

        private final List<Integer> maxTokens = new ArrayList<>();
        private final Deque<AiHttpAnswer> script = new ArrayDeque<>();

        void script(int status, String body) {
            script.add(new AiHttpAnswer(status, body));
        }

        @Override
        protected Mono<AiHttpAnswer> post(DigestAiProviderDTO channel, String prompt, int tokens,
            double temperature) {
            maxTokens.add(tokens);
            var answer = script.poll();
            return answer == null ? Mono.error(new IllegalStateException("脚本已经用尽"))
                : Mono.just(answer);
        }
    }

    private static DigestAiProviderDTO provider(String key, String base) {
        return new DigestAiProviderDTO(key, key, base, "sk-" + key, "model-" + key, true);
    }

    private static DigestConfigDTO config(DigestAiProviderDTO... providers) {
        return DigestConfigDTO.builder()
            .aiProviders(List.of(providers))
            .aiApiBase("")
            .aiApiKey("")
            .build();
    }

    private static String content(String text) {
        return "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}]}";
    }

    @Test
    void shouldMoveToNextChannelWhenFirstFails() {
        var client = new StubClient();
        client.fail("https://a.example/v1");
        client.answer("https://b.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("OK-B")));

        StepVerifier.create(client.call(config(
                provider("a", "https://a.example/v1"), provider("b", "https://b.example/v1")),
                "hi", DigestAiTask.SCORE))
            .assertNext(reply -> assertThat(reply.text()).isEqualTo("OK-B"))
            .verifyComplete();

        assertThat(client.calls).containsExactly("a", "b");
    }

    @Test
    void downgradeShouldBeRememberedForTheRestOfTheRun() {
        var client = new StubClient();
        client.fail("https://a.example/v1");
        client.answer("https://b.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("OK-B")));
        var config = config(provider("a", "https://a.example/v1"),
            provider("b", "https://b.example/v1"));

        // 一轮里的多次调用：第一条只在第一次被撞，之后直接从第二条开始
        client.call(config, "1", DigestAiTask.SCORE).block();
        client.call(config, "2", DigestAiTask.SCORE).block();
        client.call(config, "3", DigestAiTask.SCORE).block();

        assertThat(client.calls).containsExactly("a", "b", "b", "b");
    }

    @Test
    void resetForNewRunShouldRetryFromTheFirstChannel() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("OK-A")));
        client.answer("https://b.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("OK-B")));
        var config = config(provider("a", "https://a.example/v1"),
            provider("b", "https://b.example/v1"));

        client.call(config, "1", DigestAiTask.SCORE).block();
        client.resetForNewRun();
        client.call(config, "2", DigestAiTask.SCORE).block();

        assertThat(client.calls).containsExactly("a", "a");
    }

    @Test
    void allChannelsFailingShouldReportTheLastError() {
        var client = new StubClient();
        client.fail("https://a.example/v1");
        client.fail("https://b.example/v1");

        StepVerifier.create(client.call(config(
                provider("a", "https://a.example/v1"), provider("b", "https://b.example/v1")),
                "hi", DigestAiTask.SCORE))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("b.example"))
            .verify();
    }

    // ── 按任务绑定（评分用便宜模型、成稿用强模型） ──

    /** 便宜通道只勾「评分」、强通道只勾「成稿」：两个任务必须各走各的 */
    @Test
    void eachTaskShouldUseItsOwnChannel() {
        var client = new StubClient();
        client.answer("https://cheap.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("CHEAP")));
        client.answer("https://strong.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("STRONG")));
        var cheap = new DigestAiProviderDTO("cheap", "便宜", "https://cheap.example/v1", "sk-c",
            "cheap-model", true, List.of("score"));
        var strong = new DigestAiProviderDTO("strong", "强", "https://strong.example/v1", "sk-s",
            "strong-model", true, List.of("write"));
        var config = config(cheap, strong);

        assertThat(client.call(config, "p", DigestAiTask.SCORE).block().text()).isEqualTo("CHEAP");
        assertThat(client.call(config, "p", DigestAiTask.WRITE).block().text()).isEqualTo("STRONG");
        assertThat(client.calls).containsExactly("cheap", "strong");
    }

    /**
     * 一个任务的降级不能污染另一个任务。
     *
     * <p>评分在便宜通道上失败会降级到强通道，但成稿本该从强通道开始——
     * 若两条任务共用一个降级游标，成稿会被「跳过」到后面某条通道上去，
     * 而站长完全看不出这两件事有关联。</p>
     */
    @Test
    void downgradeShouldBeTrackedPerTask() {
        var client = new StubClient();
        client.fail("https://cheap.example/v1");
        client.answer("https://strong.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("STRONG")));
        var shared = new DigestAiProviderDTO("cheap", "便宜", "https://cheap.example/v1", "sk-c",
            "cheap-model", true, List.of("score"));
        // 强通道两条任务都能干：既接成稿，也能顶替评分
        var strong = new DigestAiProviderDTO("strong", "强", "https://strong.example/v1", "sk-s",
            "strong-model", true, List.of("write", "score"));
        var config = config(shared, strong);

        assertThat(client.call(config, "p", DigestAiTask.SCORE).block().text()).isEqualTo("STRONG");
        // 评分已降级到第 2 条，但成稿仍应从它自己的第 1 条（强通道）开始，而不是被带着跳走
        assertThat(client.call(config, "p", DigestAiTask.WRITE).block().text()).isEqualTo("STRONG");
        assertThat(client.calls).containsExactly("cheap", "strong", "strong");
    }

    /** 任务没有专属通道时宽松回退到全部可用通道，而不是直接失败 */
    @Test
    void taskWithoutTaggedChannelShouldFallBackToAllChannels() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("A")));
        var tagged = new DigestAiProviderDTO("a", "只做评分", "https://a.example/v1", "sk-a", "m-a",
            true, List.of("score"));
        var config = config(tagged);

        // WRITE 没有通道勾选 → 回退到全部可用通道（这里只有 a），仍然能成稿
        assertThat(client.call(config, "p", DigestAiTask.WRITE).block().text()).isEqualTo("A");
        assertThat(client.calls).containsExactly("a");
    }

    /** 所有通道都没勾用途 = 与升级前一致：什么任务都能用 */
    @Test
    void untaggedChannelShouldHandleEveryTask() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("A")));
        var config = config(provider("a", "https://a.example/v1"));

        assertThat(client.call(config, "p", DigestAiTask.SCORE).block().text()).isEqualTo("A");
        assertThat(client.call(config, "p", DigestAiTask.WRITE).block().text()).isEqualTo("A");
        assertThat(client.call(config, "p", DigestAiTask.CONTENT).block().text()).isEqualTo("A");
        assertThat(client.calls).containsExactly("a", "a", "a");
    }

    @Test
    void disabledChannelShouldNotBeCalledAtAll() {
        var client = new StubClient();
        client.answer("https://b.example/v1", new DigestAiClientImpl.AiHttpAnswer(200, content("OK-B")));
        var disabled = new DigestAiProviderDTO("a", "a", "https://a.example/v1", "sk-a", "m", false);

        StepVerifier.create(client.call(config(disabled, provider("b", "https://b.example/v1")), "hi",
                DigestAiTask.SCORE))
            .assertNext(reply -> assertThat(reply.text()).isEqualTo("OK-B"))
            .verifyComplete();

        assertThat(client.calls).containsExactly("b");
    }

    @Test
    void noUsableChannelShouldFailBeforeAnyRequest() {
        var client = new StubClient();
        StepVerifier.create(client.call(config(
                new DigestAiProviderDTO("a", "a", "https://a.example/v1", "", "m", true)),
                "hi", DigestAiTask.SCORE))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("缺 API Key"))
            .verify();

        assertThat(client.calls).isEmpty();
    }

    // ── 用量采集（费用统计的输入） ──

    /** 正常响应：正文与 usage 都要带出来，含缓存命中/未命中（DeepSeek 的差价就在这两项上） */
    @Test
    void replyShouldCarryParsedUsage() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            "{\"model\":\"deepseek-flash\",\"usage\":{\"prompt_tokens\":1000,"
                + "\"completion_tokens\":500,\"total_tokens\":1500,"
                + "\"prompt_cache_hit_tokens\":800,\"prompt_cache_miss_tokens\":200},"
                + "\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));

        var reply = client.call(config(provider("a", "https://a.example/v1")), "hi",
            DigestAiTask.SCORE).block();

        assertThat(reply.text()).isEqualTo("OK");
        assertThat(reply.usage().reported()).isTrue();
        assertThat(reply.usage().inputTokens()).isEqualTo(1000);
        assertThat(reply.usage().completionTokens()).isEqualTo(500);
        assertThat(reply.usage().cacheHitTokens()).isEqualTo(800);
        assertThat(reply.usage().cacheMissTokens()).isEqualTo(200);
        assertThat(reply.channelKey()).isEqualTo("a");
        assertThat(reply.model()).isEqualTo("model-a");
    }

    /** 中转站没回 usage：按「未上报」标记，而不是当成 0 token（那会被算成免费） */
    @Test
    void replyShouldMarkUsageUnreportedWhenMissing() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            content("OK")));

        var reply = client.call(config(provider("a", "https://a.example/v1")), "hi",
            DigestAiTask.SCORE).block();

        assertThat(reply.usage().reported()).isFalse();
        assertThat(reply.usage().isEmpty()).isTrue();
    }

    // ── 余额查询 ──

    @Test
    void balanceBaseShouldStripVersionSegment() {
        // 余额端点在根路径上：/v1 那段必须去掉，否则会打到 /v1/user/balance 上 404
        assertThat(DigestAiClientImpl.balanceBase("https://api.deepseek.com/v1"))
            .isEqualTo("https://api.deepseek.com");
        assertThat(DigestAiClientImpl.balanceBase("https://api.deepseek.com/v1/"))
            .isEqualTo("https://api.deepseek.com");
        assertThat(DigestAiClientImpl.balanceBase("https://api.deepseek.com"))
            .isEqualTo("https://api.deepseek.com");
        assertThat(DigestAiClientImpl.balanceBase("")).isEmpty();
    }

    // ── 连通性测试 ──

    @Test
    void probeShouldPassThinkingModelsThatReturnNoVisibleContent() {
        // 思考型模型（GLM-4.5+/o 系列等）在 8 token 的测试限额里可能只思考不输出：
        // 返回是标准的 chat/completions 结构、content 为空——端点本身是好的，必须判连通
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            "{\"model\":\"glm-5.3-flash\",\"usage\":{\"total_tokens\":8},"
                + "\"choices\":[{\"finish_reason\":\"length\",\"index\":0,"
                + "\"message\":{\"content\":\"\"}}]}"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> {
                assertThat(result.isOk()).isTrue();
                assertThat(result.getMessage()).contains("端点连通");
                assertThat(result.getServedModel()).isEqualTo("glm-5.3-flash");
            })
            .verifyComplete();
    }

    @Test
    void probeShouldStillRejectNonChatCompletionsBodies() {
        // 中转站返回自己的 JSON（没有 choices）：不能因为是 200 就放行
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            "{\"hello\":\"world\"}"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> assertThat(result.isOk()).isFalse())
            .verifyComplete();
    }

    @Test
    void probeShouldReportSuccessWithLatencyAndServedModel() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            "{\"model\":\"deepseek-chat\",\"usage\":{\"total_tokens\":12},"
                + "\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> {
                assertThat(result.isOk()).isTrue();
                assertThat(result.getStatus()).isEqualTo(200);
                assertThat(result.getServedModel()).isEqualTo("deepseek-chat");
                assertThat(result.getTotalTokens()).isEqualTo(12);
                assertThat(result.getReply()).isEqualTo("OK");
                assertThat(result.getLatencyMs()).isNotNull();
            })
            .verifyComplete();
    }

    @Test
    void probeShouldExplainInvalidKey() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(401,
            "{\"error\":{\"message\":\"Invalid API key\"}}"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> {
                assertThat(result.isOk()).isFalse();
                assertThat(result.getMessage()).contains("Key 无效");
            })
            .verifyComplete();
    }

    @Test
    void probeShouldExplainMissingV1Path() {
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(404, "not found"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> assertThat(result.getMessage()).contains("/v1"))
            .verifyComplete();
    }

    @Test
    void probeShouldExplainEmptyBodyOn200() {
        // 有些中转用 200 回一个非 chat/completions 的体，站长会以为配置对了
        var client = new StubClient();
        client.answer("https://a.example/v1", new DigestAiClientImpl.AiHttpAnswer(200,
            "{\"code\":0,\"msg\":\"ok\"}"));

        StepVerifier.create(client.probe(provider("a", "https://a.example/v1")))
            .assertNext(result -> {
                assertThat(result.isOk()).isFalse();
                assertThat(result.getMessage()).contains("不是 chat/completions 格式");
            })
            .verifyComplete();
    }

    @Test
    void probeShouldExplainTransportFailure() {
        var client = new StubClient();
        client.fail("https://unreachable.example/v1");

        StepVerifier.create(client.probe(provider("a", "https://unreachable.example/v1")))
            .assertNext(result -> {
                assertThat(result.isOk()).isFalse();
                assertThat(result.getMessage()).contains("unreachable.example");
            })
            .verifyComplete();
    }

    // ── 请求体：思考参数按 GLM 代际分档，思考 token 与正文共享 max_tokens 预算 ──

    private static DigestAiProviderDTO providerWithModel(String model) {
        return new DigestAiProviderDTO("a", "a", "https://a.example/v1", "sk-a", model, true);
    }

    private static Map<String, Object> bodyFor(String model, int maxTokens) {
        return DigestAiClientImpl.buildRequestBody(model, "hi", maxTokens, 0.3,
            AiThinkingPolicy.stance(model));
    }

    @Test
    void requestBodyShouldDisableThinkingWhereTheModelAllowsIt() {
        for (var model : List.of("glm-4.5-flash", "GLM-4.6", "glm-5", "glm-5.2")) {
            var body = bodyFor(model, 8192);
            assertThat(body.get("thinking")).as("model " + model)
                .isEqualTo(Map.of("type", "disabled"));
            assertThat(body).as("model " + model).doesNotContainKey("reasoning_effort");
            assertThat(body.get("model")).as("model " + model).isEqualTo(model);
        }
    }

    @Test
    void requestBodyShouldLowerEffortInsteadOfDisablingForAlwaysThinkingModels() {
        // 对 GLM-5.3 发 disabled 不是被忽略，而是整批请求直接 400（业务码 1210）
        for (var model : List.of("glm-5.3", "glm-5.3-flash", "ZHIPU/GLM-5.3-Flash", "glm-6")) {
            var body = bodyFor(model, 16384);
            assertThat(body.get("thinking")).as("model " + model)
                .isEqualTo(Map.of("type", "enabled"));
            assertThat(body.get("reasoning_effort")).as("model " + model)
                .isEqualTo(AiThinkingPolicy.EFFORT);
        }
    }

    @Test
    void requestBodyShouldNotSendThinkingWhereItDoesNotApply() {
        // 其它供应商对 thinking 的控制方式各不相同；glm-4 那一代没有这个参数
        for (var model : List.of("deepseek-chat", "gpt-4o-mini", "glm-4-flash", "chatglm3-6b")) {
            var body = bodyFor(model, 8192);
            assertThat(body).as("model " + model).doesNotContainKey("thinking");
            assertThat(body).as("model " + model).doesNotContainKey("reasoning_effort");
        }
    }

    @Test
    void requestBodyShouldKeepBaseFieldsForEveryChannel() {
        var body = bodyFor("glm-5.3-flash", 16384);
        assertThat(body)
            .containsEntry("model", "glm-5.3-flash")
            .containsEntry("max_tokens", 16384)
            .containsEntry("stream", false)
            .containsEntry("temperature", 0.3)
            .containsEntry("top_p", 0.8);
        assertThat((List<?>) body.get("messages")).hasSize(1);
    }

    // ── 输出上限：思考关不掉的模型要给推理留位置 ──

    @Test
    void alwaysThinkingModelsShouldGetMoreOutputRoom() {
        var thinking = new RecordingClient();
        thinking.script(200, content("OK"));
        thinking.call(config(providerWithModel("glm-5.3-flash")), "hi", DigestAiTask.WRITE).block();
        assertThat(thinking.maxTokens).containsExactly(16384);

        var plain = new RecordingClient();
        plain.script(200, content("OK"));
        plain.call(config(providerWithModel("glm-4.6")), "hi", DigestAiTask.WRITE).block();
        assertThat(plain.maxTokens).containsExactly(8192);
    }

    @Test
    void probeShouldAskForEnoughTokensToShowRealContent() {
        // 8 token 对强制思考的模型只够想、不够说话：测试按钮会一直停在「没产出正文」
        var thinking = new RecordingClient();
        thinking.script(200, content("OK"));
        thinking.probe(providerWithModel("glm-5.3-flash")).block();
        assertThat(thinking.maxTokens).containsExactly(512);

        var plain = new RecordingClient();
        plain.script(200, content("OK"));
        plain.probe(providerWithModel("glm-4.6")).block();
        assertThat(plain.maxTokens).containsExactly(8);
    }

    @Test
    void thinkingSwitchRejectionShouldRetryOnceAndBeRemembered() {
        // 按名字分档迟早赶不上智谱改默认值：撞一次 1210 就改按 reasoning_effort 走，
        // 之后的调用不再重复撞——否则每一批成稿都要先白失败一次再降级
        var client = new RecordingClient();
        client.script(400, "{\"error\":{\"code\":\"1210\",\"message\":\"该模型始终思考，"
            + "不支持关闭思考；请使用 low、high 或 max。\"}}");
        client.script(200, content("OK"));
        client.script(200, content("OK-2"));

        StepVerifier.create(client.call(config(providerWithModel("glm-5.2-turbo")), "hi",
                DigestAiTask.WRITE))
            .assertNext(reply -> assertThat(reply.text()).isEqualTo("OK"))
            .verifyComplete();
        assertThat(client.maxTokens).containsExactly(8192, 16384);

        StepVerifier.create(client.call(config(providerWithModel("glm-5.2-turbo")), "hi again",
                DigestAiTask.WRITE))
            .assertNext(reply -> assertThat(reply.text()).isEqualTo("OK-2"))
            .verifyComplete();
        assertThat(client.maxTokens).containsExactly(8192, 16384, 16384);
    }

    @Test
    void thinkingSwitchErrorOnAnAlwaysThinkingModelShouldNotBeRetried() {
        // 已经按 enabled + low 发了还被这样回，说明是对端自己的口径：重发一遍只是多一份同样的失败
        var client = new RecordingClient();
        client.script(400, "{\"error\":{\"code\":\"1210\",\"message\":\"该模型始终思考，"
            + "不支持关闭思考；请使用 low、high 或 max。\"}}");

        StepVerifier.create(client.call(config(providerWithModel("glm-5.3-flash")), "hi",
                DigestAiTask.WRITE))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("思考"))
            .verify();
        assertThat(client.maxTokens).containsExactly(16384);
    }

    @Test
    void otherBadRequestShouldNotBeRetriedAsAThinkingProblem() {
        // 「模型不存在」重发一遍还是不存在：只有那句「关不掉思考」才值得纠正重试
        var client = new RecordingClient();
        client.script(400, "{\"error\":{\"code\":\"1211\",\"message\":\"模型不存在，请检查模型代码\"}}");

        StepVerifier.create(client.call(config(providerWithModel("glm-5.2")), "hi",
                DigestAiTask.WRITE))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage())
                .contains("glm-5.2").contains("不存在"))
            .verify();
        assertThat(client.maxTokens).containsExactly(8192);
    }

    // ── 用量里的缓存命中：智谱按 OpenAI 写法只给 prompt_tokens_details.cached_tokens ──

    @Test
    void parseUsageShouldReadOpenAiStyleCachedTokens() {
        // 数值形状取自智谱官方「上下文缓存」文档里的示例响应
        var usage = DigestAiClientImpl.parseUsage("""
            {"choices":[{"message":{"content":"ok"}}],
             "usage":{"prompt_tokens":1075,"completion_tokens":241,"total_tokens":1316,
                      "prompt_tokens_details":{"cached_tokens":1024}}}""");

        // 不认这个字段，1024 个命中 token 会按未命中价算（GLM-5.3-Flash 是 0.23 对 0.8）
        assertThat(usage.cacheHitTokens()).isEqualTo(1024);
        assertThat(usage.hasCacheBreakdown()).isTrue();
        assertThat(usage.inputTokens()).isEqualTo(1075);
        assertThat(usage.completionTokens()).isEqualTo(241);
    }

    @Test
    void parseUsageShouldPreferExplicitCacheHitFields() {
        // DeepSeek 直接给 prompt_cache_hit_tokens：两处同时出现时以显式的那个为准
        var usage = DigestAiClientImpl.parseUsage("""
            {"usage":{"prompt_tokens":1000,"completion_tokens":200,
                     "prompt_cache_hit_tokens":700,"prompt_cache_miss_tokens":300,
                     "prompt_tokens_details":{"cached_tokens":900}}}""");

        assertThat(usage.cacheHitTokens()).isEqualTo(700);
    }

    // ── 余额：智谱没有对 API Key 开放的查询接口 ──

    private static DigestAiProviderDTO zhipuChannel() {
        return new DigestAiProviderDTO("z", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            "sk-z", "glm-5.3-flash", true);
    }

    @Test
    void zhipuChannelShouldAnswerWhereToLookInsteadOfFailing() {
        // 官方文档通篇没有余额端点，费用的问题全部指向控制台页面。
        // 以前会真的发一次 GET 拿 404，回一句像故障的「该端点没有余额接口」
        var hint = DigestAiClientImpl.balanceUnsupportedHint(zhipuChannel().apiBase());

        assertThat(hint).contains("智谱").contains("财务总览")
            .contains("open.bigmodel.cn/finance/overview");

        // 结论来自这条提示而不是发出去的那次请求（StubClient 没给这条通道打桩，真发就走网络）
        StepVerifier.create(new StubClient().queryBalance(zhipuChannel()))
            .assertNext(balance -> {
                assertThat(balance.supported()).isFalse();
                assertThat(balance.ok()).isFalse();
                assertThat(balance.message()).isEqualTo(hint);
            })
            .verifyComplete();
    }

    @Test
    void otherChannelsShouldStillTryTheBalanceEndpoint() {
        // 认不出是智谱的一律照旧去探一次：中转站自己实现了 /user/balance 的情况不能被短路丢掉
        assertThat(DigestAiClientImpl.balanceUnsupportedHint("https://api.deepseek.com/v1"))
            .isNull();
        assertThat(DigestAiClientImpl.balanceUnsupportedHint("https://gw.example.com/v1"))
            .isNull();
        assertThat(DigestAiClientImpl.balanceUnsupportedHint(null)).isNull();
    }
}
