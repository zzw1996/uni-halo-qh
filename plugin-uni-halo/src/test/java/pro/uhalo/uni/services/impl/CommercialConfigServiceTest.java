package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.CommercialConfigSaveRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Setting;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 商业化配置读写的核心保证测试。
 *
 * <p>锁住两件事：</p>
 * <ol>
 *   <li><b>字段定义来自 Halo 已解析的 Setting</b>，本插件不另存一份字段清单——
 *       否则 setting.yaml 加了字段而页面没有，站长会以为配置项丢了；</li>
 *   <li><b>保存绝不丢配置</b>：只覆盖提交过的分组与字段，其它分组、以及同组内没提交的字段
 *       原样保留。这条一旦破了，故障表现是「改了个开关，另一处的高级配置被清空」，
 *       而且往往过几天才发现。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CommercialConfigServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SETTING_NAME = "plugin-uni-halo-settings";
    private static final String CONFIG_MAP_NAME = "plugin-uni-halo-configMap";

    @Mock
    private ReactiveExtensionClient client;

    @InjectMocks
    private CommercialConfigService service;

    @BeforeEach
    void setUp() {
        // 插件声明决定设置名与配置存储名，服务端必须按声明去找，不能硬编码
        var plugin = new Plugin();
        var spec = new Plugin.PluginSpec();
        spec.setSettingName(SETTING_NAME);
        spec.setConfigMapName(CONFIG_MAP_NAME);
        plugin.setSpec(spec);
        org.mockito.Mockito.lenient()
            .when(client.fetch(eq(Plugin.class), eq("plugin-uni-halo"))).thenReturn(Mono.just(plugin));
        org.mockito.Mockito.lenient()
            .when(client.listAll(eq(Tag.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(tag("paid")));
    }

    private void stubSetting(String... groupJsonPairs) {
        var setting = new Setting();
        var spec = new Setting.SettingSpec();
        var forms = new ArrayList<Setting.SettingForm>();
        for (var i = 0; i + 1 < groupJsonPairs.length; i += 2) {
            var form = new Setting.SettingForm();
            form.setGroup(groupJsonPairs[i]);
            form.setLabel(groupJsonPairs[i] + " 标签");
            List<Object> schema;
            try {
                schema = MAPPER.convertValue(MAPPER.readTree(groupJsonPairs[i + 1]), List.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            form.setFormSchema(schema);
            forms.add(form);
        }
        spec.setForms(forms);
        setting.setSpec(spec);
        org.mockito.Mockito.lenient()
            .when(client.fetch(eq(Setting.class), eq(SETTING_NAME))).thenReturn(Mono.just(setting));
    }

    private void stubConfigMap(Map<String, String> data) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(new LinkedHashMap<>(data));
        org.mockito.Mockito.lenient()
            .when(client.fetch(eq(ConfigMap.class), eq(CONFIG_MAP_NAME))).thenReturn(Mono.just(configMap));
        org.mockito.Mockito.lenient().when(client.update(any(ConfigMap.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static Tag tag(String slug) {
        var tag = new Tag();
        var spec = new Tag.TagSpec();
        spec.setSlug(slug);
        tag.setSpec(spec);
        return tag;
    }

    private static CommercialConfigSaveRequest request(Map<String, Map<String, Object>> groups) {
        var req = new CommercialConfigSaveRequest();
        req.setGroups(groups);
        return req;
    }

    // ==================== 读取 ====================

    /** 表单结构与当前值都要下发，且只包含本页面接管的分组。 */
    @Test
    void loadShouldReturnManagedGroupsWithSchemaAndValues() {
        stubSetting("payConfig", """
            [{"$formkit":"text","name":"offerId","label":"OfferID","value":""}]
            """,
            "appConfig", """
            [{"$formkit":"text","name":"appName","label":"应用名称","value":""}]
            """);
        stubConfigMap(Map.of("payConfig", "{\"offerId\":\"offer-123\"}", "appConfig", "{}"));

        StepVerifier.create(service.load())
            .assertNext(dto -> {
                assertThat(dto.getSettingName()).isEqualTo(SETTING_NAME);
                assertThat(dto.getConfigMapName()).isEqualTo(CONFIG_MAP_NAME);
                // appConfig 不在接管范围内，不进页面，也就不会被页面保存影响
                assertThat(dto.getGroups()).extracting("group").containsExactly("payConfig");
                var pay = dto.getGroups().get(0);
                assertThat(pay.getLabel()).isEqualTo("payConfig 标签");
                assertThat(pay.getFormSchema()).hasSize(1);
                // values 是 ConfigMap 里该分组 JSON 的解析结果
                assertThat(((JsonNode) pay.getValues()).path("offerId").asText())
                    .isEqualTo("offer-123");
            })
            .verifyComplete();
    }

    /** 缺失的分组按未配置处理，不能因为一个分组没存过就整个页面读不出来。 */
    @Test
    void loadShouldTolerateMissingGroupValues() {
        stubSetting("payConfig", "[{\"$formkit\":\"text\",\"name\":\"offerId\"}]");
        stubConfigMap(Map.of());

        StepVerifier.create(service.load())
            .assertNext(dto -> assertThat(dto.getGroups()).hasSize(1))
            .verifyComplete();
    }

    // ==================== 保存：不丢配置 ====================

    /** 未接管的分组（如 Redis 密码、应用配置）必须原样保留。 */
    @Test
    void saveShouldPreserveGroupsItDoesNotManage() {
        stubSetting(
            "payConfig", "[{\"$formkit\":\"text\",\"name\":\"offerId\"}]",
            "redisConfig", "[{\"$formkit\":\"password\",\"name\":\"password\"}]");
        stubConfigMap(Map.of(
            "payConfig", "{\"offerId\":\"old\"}",
            "redisConfig", "{\"password\":\"secret-value\"}"));

        var groups = new LinkedHashMap<String, Map<String, Object>>();
        groups.put("payConfig", Map.of("offerId", "new"));

        StepVerifier.create(service.save(request(groups)))
            .assertNext(dto -> assertThat(dto.getGroups()).extracting("group").contains("payConfig"))
            .verifyComplete();

        var saved = captureUpdatedConfigMap();
        assertThat(saved.get("redisConfig")).as("未接管的配置分组必须原样保留")
            .isEqualTo("{\"password\":\"secret-value\"}");
        assertThat(saved.get("payConfig")).contains("new");
    }

    /** 同一分组内没提交的字段（历史遗留或页面未渲染）也要保留。 */
    @Test
    void saveShouldPreserveUnsubmittedFieldsWithinGroup() {
        stubSetting("payConfig", "[{\"$formkit\":\"text\",\"name\":\"offerId\"}]");
        stubConfigMap(Map.of("payConfig", "{\"offerId\":\"old\",\"legacyFlag\":true}"));

        var groups = new LinkedHashMap<String, Map<String, Object>>();
        groups.put("payConfig", Map.of("offerId", "new"));

        StepVerifier.create(service.save(request(groups))).expectNextCount(1).verifyComplete();

        var saved = captureUpdatedConfigMap();
        assertThat(saved.get("payConfig")).contains("new");
        assertThat(saved.get("payConfig")).as("组内未提交的字段不能被清掉")
            .contains("legacyFlag");
    }

    /** 页面之外的分组名不得写入：否则会变成「用这个接口改插件任意配置」的隐患。 */
    @Test
    void saveShouldIgnoreGroupsOutsideWhitelist() {
        stubSetting("payConfig", "[{\"$formkit\":\"text\",\"name\":\"offerId\"}]");
        stubConfigMap(Map.of("payConfig", "{}"));

        var groups = new LinkedHashMap<String, Map<String, Object>>();
        groups.put("payConfig", Map.of("offerId", "ok"));
        groups.put("basicConfig", Map.of("personalToken", "hijacked"));

        StepVerifier.create(service.save(request(groups))).expectNextCount(1).verifyComplete();

        var saved = captureUpdatedConfigMap();
        assertThat(saved).doesNotContainKey("basicConfig");
    }

    /** 没有任何可保存的内容时明确报错，而不是「保存成功」但什么都没写。 */
    @Test
    void saveShouldFailWhenNothingApplicable() {
        stubConfigMap(Map.of("payConfig", "{}"));

        var groups = new LinkedHashMap<String, Map<String, Object>>();
        groups.put("basicConfig", Map.of("personalToken", "x"));

        StepVerifier.create(service.save(request(groups)))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("没有需要保存的配置"))
            .verify();
    }

    /** 可重复项这类数组字段要原样写入，不能被序列化成字符串。 */
    @Test
    void saveShouldPersistNestedArraysAsJson() {
        stubSetting("payConfig", "[{\"$formkit\":\"repeater\",\"name\":\"goods\"}]");
        stubConfigMap(Map.of("payConfig", "{}"));

        var groups = new LinkedHashMap<String, Map<String, Object>>();
        groups.put("payConfig", Map.of("goods", List.of(
            Map.of("goodsId", "g1", "price", 600, "value", 5))));

        StepVerifier.create(service.save(request(groups))).expectNextCount(1).verifyComplete();

        var raw = captureUpdatedConfigMap().get("payConfig");
        assertThat(raw).contains("\"goodsId\":\"g1\"");
        assertThat(raw).contains("\"price\":600");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captureUpdatedConfigMap() {
        var captor = org.mockito.ArgumentCaptor.forClass(ConfigMap.class);
        org.mockito.Mockito.verify(client).update(captor.capture());
        var data = captor.getValue().getData();
        return data == null ? Map.of() : (Map<String, String>) (Map<?, ?>) data;
    }
    // ==================== 误报回归：检查必须基于「实际生效的配置」 ====================
    //
    // 这组用例锁住的是一个真实的线上问题：
    // 检查早期读的是 ConfigMap 里的原始存档，而原始存档缺失的字段在运行时会被解析器
    // 补上默认值。于是「没保存过 pointsConfig」被当成「积分规则全是 0」，
    // 每个每日任务都被标成「永远无法完成」——站长怎么配都消不掉这条报错。

    @Test
    void shouldNotReportErrorsOnCompletelyUnconfiguredInstall() {
        stubSetting(
            "pointsConfig", "[{\"$formkit\":\"switch\",\"name\":\"enabled\"}]",
            "dailyTaskConfig", "[{\"$formkit\":\"repeater\",\"name\":\"tasks\"}]");
        stubConfigMap(Map.of());

        StepVerifier.create(service.load())
            .assertNext(dto -> {
                var errors = dto.getChecks().stream()
                    .filter(item -> "error".equals(item.getLevel()))
                    .toList();
                assertThat(errors).as("全新安装不应报任何「需修复」").isEmpty();
            })
            .verifyComplete();
    }

    /**
     * 只保存了积分系统开关、没动过积分规则时，每日任务不能被判成无法完成。
     *
     * <p>这正是站长遇到的场景：运行时会用默认规则（浏览 1 分、评论 5 分…）把任务判为可完成，
     * 而原始存档里读不到 rules。</p>
     */
    @Test
    void shouldNotFlagDailyTasksWhenPointsRulesNotSaved() {
        stubSetting("pointsConfig", "[{\"$formkit\":\"switch\",\"name\":\"enabled\"}]");
        stubConfigMap(Map.of(
            "pointsConfig", "{\"enabled\":true}",
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"browse","target":3,"points":10,"tickets":0,"enabled":true},
                  {"type":"comment","target":1,"points":10,"tickets":0,"enabled":true}]}
                """));

        StepVerifier.create(service.load())
            .assertNext(dto -> assertThat(findCheck(dto, "永远无法完成")).isNull())
            .verifyComplete();
    }

    /** 积分规则确实为 0 时仍要报出来——修误报不能顺手把真问题也修没了。 */
    @Test
    void shouldStillFlagDailyTasksWhenPointsRuleExplicitlyZero() {
        stubSetting("pointsConfig", "[{\"$formkit\":\"switch\",\"name\":\"enabled\"}]");
        stubConfigMap(Map.of(
            "pointsConfig", "{\"enabled\":true,\"rules\":{\"browse\":{\"points\":0}}}",
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"browse","target":3,"points":10,"tickets":0,"enabled":true}]}
                """));

        StepVerifier.create(service.load())
            .assertNext(dto -> {
                var issue = findCheck(dto, "永远无法完成");
                assertThat(issue).isNotNull();
                assertThat(issue.getLevel()).isEqualTo("error");
            })
            .verifyComplete();
    }

    /** 积分系统关闭时给提示（warn）而不是「需修复」：想不用积分时关掉任务即可，不该卡在报错上。 */
    @Test
    void shouldWarnInsteadOfErrorWhenPointsEngineDisabled() {
        stubSetting("pointsConfig", "[{\"$formkit\":\"switch\",\"name\":\"enabled\"}]");
        stubConfigMap(Map.of(
            "pointsConfig", "{\"enabled\":false}",
            "dailyTaskConfig", """
                {"enabled":true,"tasks":[
                  {"type":"browse","target":3,"points":10,"tickets":0,"enabled":true}]}
                """));

        StepVerifier.create(service.load())
            .assertNext(dto -> {
                var issue = findCheck(dto, "积分系统未开启");
                assertThat(issue).isNotNull();
                assertThat(issue.getLevel()).isEqualTo("warn");
            })
            .verifyComplete();
    }

    /** 生效配置要一并下发：表单据此初始化，保证「看到的值 = 系统在用的值」。 */
    @Test
    void loadShouldExposeEffectiveValuesAlongsideStoredOnes() {
        stubSetting("pointsConfig", "[{\"$formkit\":\"group\",\"name\":\"rules\"}]");
        stubConfigMap(Map.of("pointsConfig", "{\"enabled\":true}"));

        StepVerifier.create(service.load())
            .assertNext(dto -> {
                var group = dto.getGroups().get(0);
                // 存档里没有 rules
                assertThat(String.valueOf(group.getValues())).doesNotContain("rules");
                // 生效配置里有——这正是运行时真正在用的值
                assertThat(String.valueOf(group.getEffective())).contains("dailyMaxCount");
            })
            .verifyComplete();
    }

    private static pro.uhalo.uni.dto.ConfigCheckDTO findCheck(
        pro.uhalo.uni.dto.CommercialConfigDTO dto, String titleFragment) {
        return dto.getChecks().stream()
            .filter(item -> item.getTitle() != null && item.getTitle().contains(titleFragment))
            .findFirst()
            .orElse(null);
    }
}
