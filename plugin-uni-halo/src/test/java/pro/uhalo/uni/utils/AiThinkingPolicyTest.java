package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 思考参数的按模型分档。
 *
 * <p>这一族的默认值每年都在变（GLM-4 没有 → 4.5 起默认开、可关 → 5.3 起不许关），
 * 判错的代价不对称：该关的没关只是多花 token，该发 enabled 的发了 disabled
 * 是整批请求 400、日报直接塌到 RSS 描述。所以逐代钉住。</p>
 */
class AiThinkingPolicyTest {

    @Test
    void glmBeforeFourPointFiveShouldNotSendThinkingAtAll() {
        for (var model : java.util.List.of("glm-4", "glm-4-flash", "glm-4-plus", "chatglm3-6b",
            "cogview-3", "")) {
            assertThat(AiThinkingPolicy.stance(model)).as("model " + model)
                .isEqualTo(AiThinkingPolicy.Stance.NONE);
        }
        assertThat(AiThinkingPolicy.stance(null)).isEqualTo(AiThinkingPolicy.Stance.NONE);
    }

    @Test
    void glmFourPointFiveThroughFivePointTwoShouldAllowDisabling() {
        for (var model : java.util.List.of("glm-4.5", "glm-4.5-air", "glm-4.6", "glm-4.7",
            "glm-5", "glm-5.1", "glm-5.2", "glm-5.2-fast-preview")) {
            assertThat(AiThinkingPolicy.stance(model)).as("model " + model)
                .isEqualTo(AiThinkingPolicy.Stance.DISABLE);
        }
    }

    @Test
    void glmFivePointThreeOnwardShouldForceThinking() {
        // 官方迁移说明：把 disabled 改成 enabled + reasoning_effort，否则请求直接失败
        for (var model : java.util.List.of("glm-5.3", "glm-5.3-flash", "glm-5.4", "glm-6",
            "glm-6.5-air")) {
            assertThat(AiThinkingPolicy.stance(model)).as("model " + model)
                .isEqualTo(AiThinkingPolicy.Stance.ALWAYS_ON);
        }
    }

    @Test
    void otherProvidersShouldStayOutOfTheGlmTable() {
        for (var model : java.util.List.of("deepseek-chat", "deepseek-reasoner", "gpt-5-mini",
            "kimi-k2-0711-preview", "qwen-max", "moonshot-v1-8k")) {
            assertThat(AiThinkingPolicy.stance(model)).as("model " + model)
                .isEqualTo(AiThinkingPolicy.Stance.NONE);
        }
    }

    @Test
    void relayModelNamePrefixesAndSeparatorsShouldStillBeRecognised() {
        // 中转站会把模型写成 ZHIPU/GLM-5.3-Flash、glm_5_3_flash 这类样子：
        // 认不出来就等于「什么都不发」，对端按默认 max 思考，正文照样被截断
        assertThat(AiThinkingPolicy.canonicalName("ZHIPU/GLM-5.3-Flash")).isEqualTo("glm-5.3-flash");
        assertThat(AiThinkingPolicy.stance("ZHIPU/GLM-5.3-Flash"))
            .isEqualTo(AiThinkingPolicy.Stance.ALWAYS_ON);
        assertThat(AiThinkingPolicy.stance("glm_5_3_flash"))
            .isEqualTo(AiThinkingPolicy.Stance.ALWAYS_ON);
        assertThat(AiThinkingPolicy.stance("  GLM-4.6  ")).isEqualTo(AiThinkingPolicy.Stance.DISABLE);
        assertThat(AiThinkingPolicy.stance("THUDM/glm-4-9b-chat")).isEqualTo(AiThinkingPolicy.Stance.NONE);
    }

    @Test
    void alwaysOnShouldSendEnabledWithTheSharedEffortValue() {
        assertThat(AiThinkingPolicy.requestFields(AiThinkingPolicy.Stance.ALWAYS_ON))
            .containsEntry("thinking", Map.of("type", "enabled"))
            .containsEntry("reasoning_effort", "low");
    }

    @Test
    void disableShouldNotSendReasoningEffort() {
        // 4.5~5.2 那一代没有 reasoning_effort，多发一个未知字段是白担风险
        assertThat(AiThinkingPolicy.requestFields(AiThinkingPolicy.Stance.DISABLE))
            .containsExactly(entry("thinking", Map.of("type", "disabled")));
        assertThat(AiThinkingPolicy.requestFields(AiThinkingPolicy.Stance.NONE)).isEmpty();
    }

    @Test
    void onlyTheThinkingSwitchErrorShouldTriggerTheRepair() {
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(400,
            "{\"error\":{\"code\":\"1210\",\"message\":\"该模型始终思考，不支持关闭思考；"
                + "请使用 low、high 或 max。\"}}")).isTrue();
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(400,
            "{\"error\":{\"message\":\"thinking.type disabled is not supported, "
                + "this model always thinks\"}}")).isTrue();
        // 别的 400 重发一遍也不会变好
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(400,
            "{\"error\":{\"code\":\"1211\",\"message\":\"模型不存在，请检查模型代码\"}}")).isFalse();
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(400,
            "{\"error\":{\"message\":\"prompt is too long\"}}")).isFalse();
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(429,
            "{\"error\":{\"message\":\"该模型始终思考\"}}")).isFalse();
        assertThat(AiThinkingPolicy.thinkingSwitchRejected(200, null)).isFalse();
    }
}
