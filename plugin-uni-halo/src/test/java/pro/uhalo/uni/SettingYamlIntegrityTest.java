package pro.uhalo.uni;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code extensions/setting.yaml} 的完整性。
 *
 * <p><b>为什么值得单独测</b>：这份文件写坏（重复 key、缩进错、缺 name）时，
 * 故障发生在「插件安装」这一步——Halo 解析 setting 失败，插件直接装不上，
 * 而编译、单测、打包全都不会报错。2026-09-29 就是这样漏出去一次：
 * 给新建的 repeater 加了一个 {@code help}，而同一个 mapping 后面本来就有一个，
 * 结果线上装不上、报 {@code found duplicate key help}。</p>
 *
 * <p>这里只钉「结构性错误」（YAML 语法 + 关键字段齐备），不校验具体数值——
 * 数值是站长的运营决策，不该被测试锁死。</p>
 */
class SettingYamlIntegrityTest {

    private static Map<String, Object> load() {
        try (InputStream in = SettingYamlIntegrityTest.class.getClassLoader()
            .getResourceAsStream("extensions/setting.yaml")) {
            assertThat(in).as("setting.yaml 必须存在于 classpath").isNotNull();
            // allowDuplicateKeys(false)：重复 key 直接抛 DuplicateKeyException，
            // 而不是像默认行为那样「后者覆盖前者」悄悄吞掉
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            return new Yaml(new SafeConstructor(options)).load(in);
        } catch (Exception ex) {
            throw new AssertionError("setting.yaml 解析失败（插件会装不上）：" + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> forms() {
        var spec = (Map<String, Object>) load().get("spec");
        assertThat(spec).isNotNull();
        return (List<Map<String, Object>>) spec.get("forms");
    }

    @Test
    void shouldParseWithoutDuplicateKeys() {
        // 走到这里就说明 YAML 语法与重复 key 都没问题（load() 解析失败即测试失败）
        var root = load();
        assertThat(root.get("kind")).isEqualTo("Setting");
        assertThat(forms()).isNotEmpty();
    }

    /** 分组名是本插件读取配置的唯一键：被误删/改名会让对应功能的配置项整组消失 */
    @Test
    void shouldKeepEveryGroupThePluginReads() {
        var groups = forms().stream()
            .map(form -> String.valueOf(form.get("group")))
            .toList();
        assertThat(groups).contains(
            // 基础与站点
            "basicConfig", "redisConfig", "appConfig", "imagesConfig", "shareConfig",
            "wxAppConfig", "channelsConfig", "tabbarConfig", "userPublishConfig",
            // 积分经济与商业化
            "pointsConfig", "dailyTaskConfig", "mallConfig", "levelConfig", "inviteConfig",
            "payConfig", "contentConfig", "ticketConfig", "subscriptionConfig",
            "subscriptionWallConfig", "memberConfig", "aiContentConfig",
            // 榜单、推送、AI 日报
            "rankConfig", "pushConfig", "aiDigestConfig");
    }

    /** 每个分组都要能被后台渲染：group/label/formSchema 三者缺一不可 */
    @SuppressWarnings("unchecked")
    @Test
    void everyGroupShouldBeRenderable() {
        for (var form : forms()) {
            assertThat(form.get("group")).as("分组缺少 group").isNotNull();
            assertThat(form.get("label")).as("分组 %s 缺少 label", form.get("group")).isNotNull();
            var schema = form.get("formSchema");
            assertThat(schema).as("分组 %s 缺少 formSchema", form.get("group"))
                .isInstanceOf(List.class);
            assertThat((List<Object>) schema).as("分组 %s 的 formSchema 为空", form.get("group"))
                .isNotEmpty();
        }
    }

    /**
     * 每个表单项必须有 {@code $formkit}（或有 {@code $el}）。
     *
     * <p>少了它 Halo 无法渲染该字段，站长看到的是一个空白区块——而且在安装阶段不会报错，
     * 只有打开设置页才会发现。</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void everyFormFieldShouldDeclareItsType() {
        var offenders = new java.util.ArrayList<String>();
        for (var form : forms()) {
            var group = String.valueOf(form.get("group"));
            for (var field : (List<Object>) form.get("formSchema")) {
                if (!(field instanceof Map)) {
                    offenders.add(group + " 里有一个非对象的表单项");
                    continue;
                }
                var map = (Map<String, Object>) field;
                if (!map.containsKey("$formkit") && !map.containsKey("$el")) {
                    offenders.add(group + " 的字段「" + map.getOrDefault("name", "(无 name)")
                        + "」既没有 $formkit 也没有 $el");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
