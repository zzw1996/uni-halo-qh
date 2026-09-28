package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推送配置解析：默认关闭、模板回落、间隔与上限收敛。
 *
 * <p>间隔与上限直接决定「一天最多点几次微信接口」，所以它们必须被夹在安全区间里——
 * 站长把间隔填成 0 或上限填成 100000000，不该变成对微信侧的持续调用。</p>
 */
class PushConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsShouldBeSafeToRun() {
        var config = PushConfigHelper.defaults();

        // 推送会真的给用户发微信消息：没配好之前绝不能因为「升级完就自动开推」打扰用户
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getTemplateId()).isEqualTo(PushConfigHelper.DEFAULT_TEMPLATE_ID);
        assertThat(config.isNewPostEnabled()).isTrue();
        assertThat(config.isAiDigestEnabled()).isTrue();
        assertThat(config.getCheckIntervalMinutes()).isEqualTo(5);
        assertThat(config.getDailySendLimit()).isEqualTo(500);
        assertThat(config.getMiniprogramState()).isEqualTo("formal");
    }

    @Test
    void parseShouldClampUnsafeValues() throws Exception {
        var config = PushConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"checkIntervalMinutes":0,"maxItemsPerMessage":99,
             "dailySendLimit":99999999,"lookbackHours":999,"miniprogramState":"TRIAL"}"""));

        assertThat(config.getCheckIntervalMinutes()).isEqualTo(1);
        assertThat(config.getMaxItemsPerMessage()).isEqualTo(5);
        assertThat(config.getDailySendLimit()).isEqualTo(100000);
        assertThat(config.getLookbackHours()).isEqualTo(168);
        assertThat(config.getMiniprogramState()).isEqualTo("trial");
    }

    @Test
    void parseShouldFallBackOnUnknownState() throws Exception {
        assertThat(PushConfigHelper.parse(MAPPER.readTree("{\"miniprogramState\":\"乱填\"}"))
            .getMiniprogramState()).isEqualTo("formal");
        assertThat(PushConfigHelper.parse(null).isEnabled()).isFalse();
    }

    @Test
    void kindEnabledShouldRequireSwitchAndTemplate() {
        var disabled = PushConfigHelper.defaults();
        assertThat(PushConfigHelper.kindEnabled(disabled, PushConfigHelper.KIND_NEW_POST)).isFalse();

        var enabledNoTemplate = PushConfigHelper.parse(
            MAPPER.createObjectNode().put("enabled", true).put("templateId", ""));
        // 没有模板就别去调微信（必然 40037）
        assertThat(PushConfigHelper.kindEnabled(enabledNoTemplate, PushConfigHelper.KIND_NEW_POST))
            .isFalse();

        var enabled = PushConfigHelper.parse(
            MAPPER.createObjectNode().put("enabled", true).put("templateId", "tmpl"));
        assertThat(PushConfigHelper.kindEnabled(enabled, PushConfigHelper.KIND_NEW_POST)).isTrue();
        assertThat(PushConfigHelper.kindEnabled(enabled, PushConfigHelper.KIND_AI_DIGEST)).isTrue();

        var onlyNewPost = PushConfigHelper.parse(MAPPER.createObjectNode()
            .put("enabled", true).put("templateId", "tmpl").put("aiDigestEnabled", false));
        assertThat(PushConfigHelper.kindEnabled(onlyNewPost, PushConfigHelper.KIND_NEW_POST))
            .isTrue();
        assertThat(PushConfigHelper.kindEnabled(onlyNewPost, PushConfigHelper.KIND_AI_DIGEST))
            .isFalse();
    }

    @Test
    void normalizeKindShouldRejectUnknownValues() {
        assertThat(PushConfigHelper.normalizeKind("newPost")).isEqualTo("newPost");
        assertThat(PushConfigHelper.normalizeKind(" aiDigest ")).isEqualTo("aiDigest");
        // 认不出来一律返回空：不猜、不默认成某一种
        assertThat(PushConfigHelper.normalizeKind("unknown")).isEmpty();
        assertThat(PushConfigHelper.normalizeKind(null)).isEmpty();
    }
}
