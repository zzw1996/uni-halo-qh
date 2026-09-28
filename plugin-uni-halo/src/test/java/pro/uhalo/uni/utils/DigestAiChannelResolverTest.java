package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通道解析：清单优先、开关与 Key 决定可用性、清单留空时回落旧的主 / 兜底通道。
 *
 * <p>这一层是「到底在用哪个模型」的唯一口径——它算错的后果不是编译失败，
 * 而是面板说在用 A、实际调用走了 B，所以顺序与可用性都要逐个钉住。</p>
 */
class DigestAiChannelResolverTest {

    private static DigestAiProviderDTO provider(String key, String base, String apiKey,
        boolean enabled) {
        return new DigestAiProviderDTO(key, key, base, apiKey, "m-" + key, enabled);
    }

    private static DigestConfigDTO.DigestConfigDTOBuilder withProviders(
        List<DigestAiProviderDTO> providers) {
        return DigestConfigDTO.builder()
            .aiApiBase("")
            .aiApiKey("")
            .fallbackApiBase("")
            .fallbackApiKey("")
            .aiProviders(providers);
    }

    @Test
    void channelsShouldFollowListOrderAndSkipNothingWhenListing() {
        var config = withProviders(List.of(
            provider("a", "https://a.example/v1", "sk-a", true),
            provider("b", "https://b.example/v1", "sk-b", true)))
            .build();

        assertThat(DigestAiChannelResolver.fromList(config)).isTrue();
        assertThat(DigestAiChannelResolver.usableChannels(config))
            .extracting(DigestAiProviderDTO::key).containsExactly("a", "b");
    }

    @Test
    void disabledProviderShouldNeverBeCalled() {
        var config = withProviders(List.of(
            provider("a", "https://a.example/v1", "sk-a", false),
            provider("b", "https://b.example/v1", "sk-b", true)))
            .build();

        // 关掉的那条仍在清单里（页面要显示它），但不参与调用
        assertThat(DigestAiChannelResolver.channels(config))
            .extracting(DigestAiProviderDTO::key).containsExactly("a", "b");
        assertThat(DigestAiChannelResolver.usableChannels(config))
            .extracting(DigestAiProviderDTO::key).containsExactly("b");
    }

    @Test
    void providerWithoutKeyShouldBeListedButNotUsable() {
        var config = withProviders(List.of(provider("a", "https://a.example/v1", "", true))).build();

        assertThat(DigestAiChannelResolver.channels(config)).hasSize(1);
        assertThat(DigestAiChannelResolver.hasUsableChannel(config)).isFalse();
        assertThat(DigestAiChannelResolver.noUsableChannelMessage(config))
            .contains("缺 API Key").contains("a");
    }

    @Test
    void allDisabledMessageShouldSayDisabledNotMissingKey() {
        var config = withProviders(List.of(provider("a", "https://a.example/v1", "sk-a", false)))
            .build();

        // 「全关了」与「缺 Key」的处置动作不同，报错必须区分开
        assertThat(DigestAiChannelResolver.noUsableChannelMessage(config))
            .contains("已关闭").doesNotContain("缺 API Key");
    }

    @Test
    void emptyListShouldFallBackToLegacyChannels() {
        var config = DigestConfigDTO.builder()
            .aiApiBase("https://api.deepseek.com/v1")
            .aiApiKey("sk-primary")
            .aiModel("deepseek-chat")
            .fallbackApiBase("https://api.openai.com/v1")
            .fallbackApiKey("sk-fallback")
            .fallbackModel("gpt-4o-mini")
            .aiProviders(List.of())
            .build();

        assertThat(DigestAiChannelResolver.fromList(config)).isFalse();
        assertThat(DigestAiChannelResolver.usableChannels(config))
            .extracting(DigestAiProviderDTO::key)
            .containsExactly(DigestAiChannelResolver.LEGACY_PRIMARY_KEY,
                DigestAiChannelResolver.LEGACY_FALLBACK_KEY);
        assertThat(DigestAiChannelResolver.activeModel(config)).isEqualTo("deepseek-chat");
        assertThat(DigestAiChannelResolver.describe(config)).contains("清单留空");
    }

    @Test
    void legacyWithOnlyFallbackKeyShouldUseFallback() {
        // 改造前就是「主通道没 Key 就直接走兜底」，这条行为不能变
        var config = DigestConfigDTO.builder()
            .aiApiBase("https://api.deepseek.com/v1")
            .aiApiKey("")
            .fallbackApiKey("sk-fallback")
            .fallbackApiBase("")
            .build();

        var usable = DigestAiChannelResolver.usableChannels(config);
        assertThat(usable).hasSize(1);
        assertThat(usable.get(0).key()).isEqualTo(DigestAiChannelResolver.LEGACY_FALLBACK_KEY);
        // 兜底端点留空时沿用 OpenAI 默认地址与默认模型
        assertThat(usable.get(0).apiBase())
            .isEqualTo(DigestAiChannelResolver.DEFAULT_FALLBACK_BASE);
        assertThat(usable.get(0).model())
            .isEqualTo(DigestAiChannelResolver.DEFAULT_OPENAI_MODEL);
    }

    @Test
    void listShouldTakeOverLegacyFieldsEvenWhenListIsAllDisabled() {
        // 目录清单存在即表示站长接管了配置：旧字段不能再偷偷生效
        var config = withProviders(List.of(provider("a", "https://a.example/v1", "sk-a", false)))
            .aiApiKey("sk-legacy")
            .aiApiBase("https://api.deepseek.com/v1")
            .build();

        assertThat(DigestAiChannelResolver.usableChannels(config)).isEmpty();
    }

    @Test
    void baseShouldDropTrailingSlashesAndModelShouldBeInferred() {
        var config = withProviders(List.of(new DigestAiProviderDTO("a", "", "https://a.example/v1///",
            "sk-a", "", true))).build();

        var channel = DigestAiChannelResolver.channels(config).get(0);
        assertThat(channel.apiBase()).isEqualTo("https://a.example/v1");
        assertThat(channel.model()).isEqualTo(DigestAiChannelResolver.DEFAULT_OPENAI_MODEL);
        // 显示名留空时用主机名兜底，日志里才有东西可看
        assertThat(channel.label()).isEqualTo("a.example");
    }

    @Test
    void deepseekBaseShouldInferDeepseekModel() {
        var config = withProviders(List.of(new DigestAiProviderDTO("a", "x",
            "https://api.deepseek.com/v1", "sk-a", "", true))).build();

        assertThat(DigestAiChannelResolver.channels(config).get(0).model())
            .isEqualTo(DigestAiChannelResolver.DEFAULT_DEEPSEEK_MODEL);
    }

    @Test
    void describeShouldMentionActiveChannelAndCount() {
        var config = withProviders(List.of(
            provider("a", "https://a.example/v1", "sk-a", true),
            provider("b", "https://b.example/v1", "sk-b", true))).build();

        assertThat(DigestAiChannelResolver.describe(config))
            .contains("当前生效").contains("m-a").contains("2 条可用通道");
    }

    // ── 按任务绑定（宽松回退） ──

    private static DigestAiProviderDTO tagged(String key, List<String> tasks) {
        return new DigestAiProviderDTO(key, key, "https://" + key + ".example/v1", "sk-" + key,
            "m-" + key, true, tasks);
    }

    @Test
    void planShouldKeepListOrderWithinTheSameTask() {
        var config = withProviders(List.of(
            tagged("a", List.of("score", "write")),
            tagged("b", List.of("score"))))
            .build();

        assertThat(DigestAiChannelResolver.plan(config, DigestAiTask.SCORE).channels())
            .extracting(DigestAiProviderDTO::key).containsExactly("a", "b");
        assertThat(DigestAiChannelResolver.plan(config, DigestAiTask.WRITE).channels())
            .extracting(DigestAiProviderDTO::key).containsExactly("a");
        assertThat(DigestAiChannelResolver.plan(config, DigestAiTask.SCORE).fallback()).isFalse();
    }

    @Test
    void planShouldSplitCheapScoringFromStrongWriting() {
        // 这正是「评分用便宜模型、成稿用强模型」的配置形态
        var config = withProviders(List.of(
            tagged("cheap", List.of("score", "classify", "content")),
            tagged("strong", List.of("write"))))
            .build();

        assertThat(DigestAiChannelResolver.activeModel(config, DigestAiTask.SCORE))
            .isEqualTo("m-cheap");
        assertThat(DigestAiChannelResolver.activeModel(config, DigestAiTask.WRITE))
            .isEqualTo("m-strong");
        assertThat(DigestAiChannelResolver.describeTasks(config))
            .contains("评分=cheap@m-cheap").contains("成稿=strong@m-strong");
    }

    @Test
    void taskWithoutTaggedChannelShouldFallBackToEveryUsableChannel() {
        var config = withProviders(List.of(tagged("a", List.of("score")))).build();

        var plan = DigestAiChannelResolver.plan(config, DigestAiTask.WRITE);
        assertThat(plan.usable()).isTrue();
        assertThat(plan.fallback()).isTrue();
        assertThat(plan.channels()).extracting(DigestAiProviderDTO::key).containsExactly("a");
        // 回退必须说出来：面板与日志都靠这句话，不能悄悄花掉不该花的钱
        assertThat(plan.note()).contains("回退").contains("成稿");
    }

    @Test
    void untaggedChannelShouldHandleEveryTask() {
        var config = withProviders(List.of(provider("a", "https://a.example/v1", "sk-a", true)))
            .build();

        for (var task : DigestAiTask.values()) {
            var plan = DigestAiChannelResolver.plan(config, task);
            assertThat(plan.usable()).as("任务 %s 应可用", task.getKey()).isTrue();
            assertThat(plan.fallback()).isFalse();
        }
    }

    @Test
    void taskWithoutAnyUsableChannelShouldSayWhichTaskFailed() {
        // 清单非空但全是关掉的：报错要说清是哪个任务没有通道
        var config = withProviders(List.of(
            new DigestAiProviderDTO("a", "a", "https://a.example/v1", "sk-a", "m-a", false,
                List.of("write"))))
            .build();

        var plan = DigestAiChannelResolver.plan(config, DigestAiTask.WRITE);
        assertThat(plan.usable()).isFalse();
        assertThat(plan.note()).contains("成稿").contains("已关闭");
        assertThat(DigestAiChannelResolver.hasUsableChannel(config, DigestAiTask.WRITE)).isFalse();
        assertThat(DigestAiChannelResolver.describeTasks(config)).contains("成稿=无可用通道");
    }

    @Test
    void legacyChannelsShouldServeEveryTask() {
        // 旧字段生成的兼容通道不带用途 = 全任务，否则升级后绑定了用途的站点会突然没通道
        var config = DigestConfigDTO.builder()
            .aiApiBase("https://api.deepseek.com/v1")
            .aiApiKey("sk-legacy")
            .aiModel("deepseek-chat")
            .aiProviders(List.of())
            .build();

        for (var task : DigestAiTask.values()) {
            assertThat(DigestAiChannelResolver.hasUsableChannel(config, task)).isTrue();
            assertThat(DigestAiChannelResolver.plan(config, task).fallback()).isFalse();
        }
    }
}
