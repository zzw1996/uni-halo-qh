package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestAiProbeResultDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.services.DigestAiClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 「大模型通道」读写与测试的核心保证。
 *
 * <p>锁住三件事：</p>
 * <ol>
 *   <li><b>保存不丢其它配置</b>：只替换 {@code aiProviders}，同组里的时间窗、精选篇数、
 *       分类清单一律原样保留；</li>
 *   <li><b>不丢 Key</b>：页面回传掩码或空值时沿用已保存的那把——站长不该为了改个模型名
 *       而重新粘贴一遍密钥，更不该因为回传掩码把 Key 存成「••••」；</li>
 *   <li><b>测试按钮按标识回查已存 Key</b>：因此页面无需回传明文。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DigestAiModelServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_MAP_NAME = "plugin-uni-halo-configMap";
    private static final String GROUP = "aiDigestConfig";

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private DigestAiClient aiClient;

    @InjectMocks
    private DigestAiModelService service;

    /** 保存后的配置存储：断言「写进去的到底是什么」 */
    private final Map<String, String> stored = new LinkedHashMap<>();

    /** save 之后 ConfigMap 指向的是新的一份 data，因此要读它当下的内容 */
    private ConfigMap configMap;

    @BeforeEach
    void setUp() {
        var plugin = new Plugin();
        var spec = new Plugin.PluginSpec();
        spec.setConfigMapName(CONFIG_MAP_NAME);
        plugin.setSpec(spec);
        lenient().when(client.fetch(eq(Plugin.class), eq("plugin-uni-halo")))
            .thenReturn(Mono.just(plugin));
        lenient().when(client.update(any(ConfigMap.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private void stubConfigMap() {
        configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(stored);
        lenient().when(client.fetch(eq(ConfigMap.class), eq(CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap));
    }

    private void stubSetting(String groupJson) {
        try {
            lenient().when(settingFetcher.get(GROUP))
                .thenReturn(Mono.just(MAPPER.readTree(groupJson)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static DigestAiProviderDTO row(String key, String label, String base, String apiKey,
        String model, boolean enabled) {
        return new DigestAiProviderDTO(key, label, base, apiKey, model, enabled);
    }

    private static DigestAiProviderDTO tagged(String key, String label, String base, String apiKey,
        String model, List<String> tasks) {
        return new DigestAiProviderDTO(key, label, base, apiKey, model, true, tasks);
    }

    // ── 读取 ──

    @Test
    void loadShouldMaskKeysAndPointAtActiveChannel() {
        stubSetting("""
            {"aiProviders":[
              {"key":"ds","label":"DeepSeek 主用","apiBase":"https://api.deepseek.com/v1",
               "apiKey":"sk-1234567890abcdef","model":"deepseek-chat","enabled":true},
              {"key":"qwen","label":"通义千问 备用","apiBase":"https://dashscope.aliyuncs.com/compatible-mode/v1",
               "apiKey":"sk-qwen-secret","model":"qwen-plus","enabled":false}]}
            """);

        StepVerifier.create(service.load())
            .assertNext(view -> {
                assertThat(view.fromList()).isTrue();
                assertThat(view.providers()).hasSize(2);

                var first = view.providers().get(0);
                // 明文 Key 绝不出现在响应里
                assertThat(first.apiKeyMasked()).doesNotContain("1234567890abcdef");
                assertThat(first.apiKeyMasked()).isEqualTo("sk-" + DigestAiProviderDTO.MASK + "cdef");
                assertThat(first.usable()).isTrue();
                assertThat(first.message()).contains("可用");

                // 关掉的通道会说明它不参与调用
                assertThat(view.providers().get(1).usable()).isFalse();
                assertThat(view.providers().get(1).message()).contains("已关闭");

                assertThat(view.active()).isEqualTo("ds");
                assertThat(view.summary()).contains("DeepSeek 主用");
                assertThat(view.hints()).anySatisfy(hint -> assertThat(hint).contains("被关闭"));
            })
            .verifyComplete();
    }

    @Test
    void loadShouldExplainEmptyListIsStillUsingLegacyChannels() {
        stubSetting("""
            {"aiApiBase":"https://api.deepseek.com/v1","aiApiKey":"sk-legacy-key",
             "aiModel":"deepseek-chat","aiProviders":[]}
            """);

        StepVerifier.create(service.load())
            .assertNext(view -> {
                assertThat(view.fromList()).isFalse();
                assertThat(view.providers()).isEmpty();
                // 清单为空不等于「没在用模型」：实际生效的是兼容通道，面板要能看出来
                assertThat(view.channels()).hasSize(1);
                assertThat(view.channels().get(0).source()).isEqualTo("legacy");
                assertThat(view.active()).isEqualTo("legacy-primary");
                assertThat(view.legacy().configured()).isTrue();
                assertThat(view.legacy().apiKeyMasked()).doesNotContain("legacy-key");
            })
            .verifyComplete();
    }

    // ── 保存 ──

    @Test
    void saveShouldKeepOtherFieldsAndPreserveMaskedKeys() {
        stored.put(GROUP, """
            {"topN":9,"scheduleTimes":"07:00,19:00","aiApiBase":"https://api.deepseek.com/v1",
             "aiApiKey":"sk-old-primary",
             "aiProviders":[{"key":"ds","label":"DeepSeek","apiBase":"https://api.deepseek.com/v1",
                             "apiKey":"sk-original-secret","model":"deepseek-chat","enabled":true}]}
            """);
        stubConfigMap();

        StepVerifier.create(service.save(List.of(
                // 原样回传掩码（站长只改了模型名）+ 新增一条草稿行（填了端点、Key 与用途）
                tagged("ds", "DeepSeek", "https://api.deepseek.com/v1",
                    "sk-" + DigestAiProviderDTO.MASK + "cret", "deepseek-reasoner",
                    List.of("score", "overview")),
                tagged("", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "sk-qwen", "qwen-plus", List.of("write")))))
            .assertNext(view -> {
                assertThat(view.providers()).hasSize(2);
                assertThat(view.summary()).contains("DeepSeek");
            })
            .verifyComplete();

        var group = readGroup();
        // 页面没接管的字段原样保留
        assertThat(group.path("topN").asInt()).isEqualTo(9);
        assertThat(group.path("scheduleTimes").asText()).isEqualTo("07:00,19:00");
        assertThat(group.path("aiApiKey").asText()).isEqualTo("sk-old-primary");

        var providers = group.path("aiProviders");
        assertThat(providers).hasSize(2);
        // 掩码回传 = 保留原 Key（不能被存成掩码串）
        assertThat(providers.get(0).path("apiKey").asText()).isEqualTo("sk-original-secret");
        assertThat(providers.get(0).path("model").asText()).isEqualTo("deepseek-reasoner");
        // 用途是可编辑字段：必须原样写回（漏了就是「保存一下勾选全没了」）
        assertThat(providers.get(0).path("tasks")).hasSize(2);
        assertThat(providers.get(0).path("tasks").get(0).asText()).isEqualTo("score");
        // 新行按提交的 Key 落库，并补一个自动标识
        assertThat(providers.get(1).path("apiKey").asText()).isEqualTo("sk-qwen");
        assertThat(providers.get(1).path("key").asText()).isNotEmpty();
        assertThat(providers.get(1).path("tasks").get(0).asText()).isEqualTo("write");
    }

    @Test
    void saveShouldDropBlankRowsAndKeepDisabledFlag() {
        stored.put(GROUP, "{\"aiProviders\":[]}");
        stubConfigMap();

        StepVerifier.create(service.save(List.of(
                row("", "", "", "", "", true),
                row("off", "备用", "https://b.example/v1", "sk-b", "m-b", false))))
            .assertNext(view -> assertThat(view.providers()).hasSize(1))
            .verifyComplete();

        var providers = readGroup().path("aiProviders");
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).path("enabled").asBoolean()).isFalse();
    }

    @Test
    void saveShouldPersistClearedAndSanitizedTasks() {
        stored.put(GROUP, """
            {"aiProviders":[{"key":"ds","label":"DeepSeek","apiBase":"https://api.deepseek.com/v1",
                             "apiKey":"sk-ds","model":"deepseek-chat","enabled":true,
                             "tasks":["score"]}]}
            """);
        stubConfigMap();

        StepVerifier.create(service.save(List.of(
                // 把勾去掉（提交空数组）= 恢复「什么活都接」；第二条混进一个非法标识
                tagged("ds", "DeepSeek", "https://api.deepseek.com/v1", "", "deepseek-chat",
                    List.of()),
                tagged("", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-qwen",
                    "qwen-plus", List.of("write", "乱填的")))))
            .assertNext(view -> assertThat(view.providers()).hasSize(2))
            .verifyComplete();

        var providers = readGroup().path("aiProviders");
        assertThat(providers.get(0).path("tasks")).isEmpty();
        assertThat(providers.get(1).path("tasks")).hasSize(1);
        assertThat(providers.get(1).path("tasks").get(0).asText()).isEqualTo("write");
    }

    @Test
    void loadShouldExposeWhichChannelServesEachTask() {
        stubSetting("""
            {"aiProviders":[
              {"key":"cheap","label":"便宜","apiBase":"https://cheap.example/v1",
               "apiKey":"sk-cheap","model":"cheap-model","enabled":true,"tasks":["score"]},
              {"key":"strong","label":"强","apiBase":"https://strong.example/v1",
               "apiKey":"sk-strong","model":"strong-model","enabled":true,"tasks":["write"]}]}
            """);

        StepVerifier.create(service.load())
            .assertNext(view -> {
                var score = view.tasks().stream()
                    .filter(line -> "score".equals(line.key())).findFirst().orElseThrow();
                var write = view.tasks().stream()
                    .filter(line -> "write".equals(line.key())).findFirst().orElseThrow();
                assertThat(score.model()).isEqualTo("cheap-model");
                assertThat(score.fallback()).isFalse();
                assertThat(write.model()).isEqualTo("strong-model");

                // 归类没通道勾选 → 回退到全部可用通道，并且要在下发数据里说出来
                var classify = view.tasks().stream()
                    .filter(line -> "classify".equals(line.key())).findFirst().orElseThrow();
                assertThat(classify.fallback()).isTrue();
                assertThat(classify.usable()).isTrue();
                assertThat(classify.note()).contains("回退");
                assertThat(view.providers().get(0).tasks()).containsExactly("score");
            })
            .verifyComplete();
    }

    @Test
    void saveShouldRefuseTooManyRows() {
        var rows = new ArrayList<DigestAiProviderDTO>();
        for (var i = 0; i < 11; i++) {
            rows.add(row("", "通道" + i, "https://p" + i + ".example/v1", "sk-" + i, "m", true));
        }

        // 静默截断等于悄悄丢掉站长刚填的通道，宁可报错
        StepVerifier.create(service.save(rows))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("最多保存 10 条"))
            .verify();
    }

    // ── 测试连通性 ──

    @Test
    void testShouldResolveStoredKeyByRowKey() {
        stubSetting("""
            {"aiProviders":[{"key":"ds","label":"DeepSeek","apiBase":"https://api.deepseek.com/v1",
                             "apiKey":"sk-stored-secret","model":"deepseek-chat","enabled":true}]}
            """);
        when(aiClient.probe(any(DigestAiProviderDTO.class))).thenReturn(Mono.just(
            DigestAiProbeResultDTO.builder().ok(true).message("连通正常").build()));

        // 页面只回传掩码：服务端按 key 找回明文那把
        StepVerifier.create(service.test(row("ds", "DeepSeek", "https://api.deepseek.com/v1",
                "sk-" + DigestAiProviderDTO.MASK + "cret", "deepseek-chat", true)))
            .assertNext(result -> assertThat(result.isOk()).isTrue())
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(DigestAiProviderDTO.class);
        org.mockito.Mockito.verify(aiClient).probe(captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-stored-secret");
        assertThat(captor.getValue().model()).isEqualTo("deepseek-chat");
    }

    @Test
    void testShouldRefuseIncompleteDraftRowWithoutCallingAi() {
        stubSetting("{\"aiProviders\":[]}");

        StepVerifier.create(service.test(row("", "草稿", "https://a.example/v1", "", "m", true)))
            .assertNext(result -> {
                assertThat(result.isOk()).isFalse();
                assertThat(result.getMessage()).contains("缺 API Key");
            })
            .verifyComplete();

        org.mockito.Mockito.verifyNoInteractions(aiClient);
    }

    @Test
    void testShouldSupportSavingDraftRowWithoutModelName() {
        stubSetting("{\"aiProviders\":[]}");
        when(aiClient.probe(any(DigestAiProviderDTO.class))).thenReturn(Mono.just(
            DigestAiProbeResultDTO.builder().ok(true).message("连通正常").build()));

        StepVerifier.create(service.test(row("", "草稿", "https://api.deepseek.com/v1", "sk-draft",
                "", true)))
            .assertNext(result -> assertThat(result.isOk()).isTrue())
            .verifyComplete();

        // 模型名留空时按端点推断，不能把空模型名发出去
        var captor = ArgumentCaptor.forClass(DigestAiProviderDTO.class);
        org.mockito.Mockito.verify(aiClient).probe(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("deepseek-chat");
    }

    private com.fasterxml.jackson.databind.JsonNode readGroup() {
        try {
            return MAPPER.readTree(configMap.getData().get(GROUP));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
