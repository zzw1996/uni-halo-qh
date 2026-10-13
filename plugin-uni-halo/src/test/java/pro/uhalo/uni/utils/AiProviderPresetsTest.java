package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大模型供应商预设与地址纠偏：适配各家的第一现场是「API Base 填法」——
 * 站长最常见的两种错（只填域名、把完整接口地址整条粘进来）都要能自动兜住。
 */
class AiProviderPresetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void zhipuPresetShouldExistWithCanonicalBase() {
        var preset = AiProviderPresets.byHost("https://open.bigmodel.cn/api/paas/v4/chat/completions")
            .orElseThrow();

        assertThat(preset.key()).isEqualTo("zhipu");
        assertThat(preset.apiBase()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(preset.models()).contains("glm-4-flash");
    }

    @Test
    void byHostShouldMatchKnownProvidersRegardlessOfPath() {
        assertThat(AiProviderPresets.byHost("https://api.deepseek.com").isPresent()).isTrue();
        assertThat(AiProviderPresets.byHost("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .map(AiProviderPresets.Preset::key)).contains("dashscope");
        // 认不出的域名就是空：通用提示兜底，不张冠李戴
        assertThat(AiProviderPresets.byHost("https://api.example.com/v1")).isEmpty();
        assertThat(AiProviderPresets.byHost("")).isEmpty();
        assertThat(AiProviderPresets.byHost(null)).isEmpty();
    }

    @Test
    void normalizeApiBaseShouldStripSlashAndFullEndpoint() {
        assertThat(AiProviderPresets.normalizeApiBase(" https://api.deepseek.com/v1/ "))
            .isEqualTo("https://api.deepseek.com/v1");
        // 把完整接口地址当 Base 粘进来：自动纠正，否则会拼成 …/chat/completions/chat/completions
        assertThat(AiProviderPresets.normalizeApiBase(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions"))
            .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(AiProviderPresets.normalizeApiBase("")).isEmpty();
    }

    @Test
    void chatUrlShouldJoinBaseWithEndpoint() {
        assertThat(AiProviderPresets.chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"))
            .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(AiProviderPresets.chatCompletionsUrl("https://api.deepseek.com/v1/"))
            .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiProviderPresets.chatCompletionsUrl(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions"))
            .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(AiProviderPresets.chatCompletionsUrl("")).isEqualTo("/chat/completions");
    }

    @Test
    void hintForShouldTellTheRightBaseForKnownHosts() {
        var hint = AiProviderPresets.hintFor("https://open.bigmodel.cn/api/paas/v1");

        assertThat(hint).contains("智谱 GLM");
        assertThat(hint).contains("https://open.bigmodel.cn/api/paas/v4");
        assertThat(hint).contains("你填的是 https://open.bigmodel.cn/api/paas/v1");
        assertThat(hint).contains("glm-4-flash");
    }

    @Test
    void hintForShouldBeEmptyForUnknownHosts() {
        assertThat(AiProviderPresets.hintFor("https://api.example.com/v1")).isEmpty();
        assertThat(AiProviderPresets.hintFor("")).isEmpty();
    }

    @Test
    void presetsShouldBeSerializableForTheConsoleDropdown() throws Exception {
        var root = MAPPER.readTree(MAPPER.writeValueAsString(AiProviderPresets.ALL));

        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isGreaterThanOrEqualTo(10);
        assertThat(root.get(0).path("key").asText()).isEqualTo("zhipu");
        assertThat(root.get(0).path("apiBase").asText()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
    }
}
