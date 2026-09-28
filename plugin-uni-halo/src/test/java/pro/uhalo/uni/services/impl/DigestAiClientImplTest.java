package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
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
}
