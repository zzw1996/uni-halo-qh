package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 日报配置解析：默认值、越界收敛、时刻与时区容错。
 */
class DigestConfigHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsShouldBeSafeToRun() {
        var config = DigestConfigHelper.defaults();
        // 自动发布文章对站点有实际影响，默认必须关闭
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isScheduleEnabled()).isTrue();
        assertThat(config.getScheduleTimes()).isEqualTo("07:00");
        assertThat(config.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(config.getHours()).isEqualTo(48);
        assertThat(config.getTopN()).isEqualTo(15);
        assertThat(config.getWords()).isEqualTo(600);
        assertThat(config.getLanguage()).isEqualTo("zh");
        assertThat(config.isImageEnabled()).isTrue();
        assertThat(config.isPublishEnabled()).isTrue();
        assertThat(config.getPostOwner()).isEqualTo("admin");
        assertThat(config.getPublishWaitSeconds()).isEqualTo(600);
    }

    @Test
    void parseShouldClampOutOfRangeValues() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"hours":0,"topN":500,"words":99999,"publishWaitSeconds":5,
             "language":"EN","scheduleTimes":"07:30"}
            """));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getHours()).isEqualTo(1);
        assertThat(config.getTopN()).isEqualTo(50);
        assertThat(config.getWords()).isEqualTo(2000);
        assertThat(config.getPublishWaitSeconds()).isEqualTo(60);
        assertThat(config.getLanguage()).isEqualTo("en");
        assertThat(config.getScheduleTimes()).isEqualTo("07:30");
    }

    @Test
    void parseShouldFallBackToDefaultsWhenGroupMissing() {
        assertThat(DigestConfigHelper.parse(null).isEnabled()).isFalse();
        assertThat(DigestConfigHelper.parse(null).getHours()).isEqualTo(48);
    }

    @Test
    void parseShouldKeepBlankTextFieldsAsEmptyInsteadOfDefault() throws Exception {
        // 兜底通道与附件策略留空是「不启用」，不能回填成默认值
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"fallbackApiBase":"","fallbackApiKey":"","attachmentPolicyName":""}
            """));

        assertThat(config.getFallbackApiBase()).isEmpty();
        assertThat(config.getFallbackApiKey()).isEmpty();
        assertThat(config.getAttachmentPolicyName()).isEmpty();
    }

    @Test
    void publishModeShouldDefaultToPerArticle() {
        // 默认逐篇：精选 N 篇就发 N 篇独立文章，而不是合并成一篇
        assertThat(DigestConfigHelper.defaults().getPublishMode())
            .isEqualTo(DigestConfigHelper.PUBLISH_MODE_PER_ARTICLE);
        assertThat(DigestConfigHelper.parse(null).getPublishMode())
            .isEqualTo(DigestConfigHelper.PUBLISH_MODE_PER_ARTICLE);
    }

    @Test
    void publishModeShouldNormalizeKnownAliasesAndFallBackOnGarbage() throws Exception {
        assertThat(parseMode("digest")).isEqualTo(DigestConfigHelper.PUBLISH_MODE_DIGEST);
        assertThat(parseMode("BOTH")).isEqualTo(DigestConfigHelper.PUBLISH_MODE_BOTH);
        assertThat(parseMode("merged")).isEqualTo(DigestConfigHelper.PUBLISH_MODE_DIGEST);
        assertThat(parseMode("per-article")).isEqualTo(DigestConfigHelper.PUBLISH_MODE_PER_ARTICLE);
        assertThat(parseMode("随便填的")).isEqualTo(DigestConfigHelper.PUBLISH_MODE_PER_ARTICLE);
    }

    private static String parseMode(String value) throws Exception {
        return DigestConfigHelper.parse(MAPPER.readTree("{\"publishMode\":\"" + value + "\"}"))
            .getPublishMode();
    }

    @Test
    void splitNamesShouldParseMixedSeparators() {
        assertThat(DigestConfigHelper.splitNames("技术, AI，日报; 精选；"))
            .containsExactly("技术", "AI", "日报", "精选");
        assertThat(DigestConfigHelper.splitNames("")).isEmpty();
        assertThat(DigestConfigHelper.splitNames(null)).isEmpty();
        assertThat(DigestConfigHelper.splitNames("   ")).isEmpty();
    }

    @Test
    void zoneOfShouldFallBackOnInvalidTimezone() {
        assertThat(DigestConfigHelper.zoneOf("Asia/Shanghai")).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(DigestConfigHelper.zoneOf("Not/AZone")).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(DigestConfigHelper.zoneOf("")).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void parseScheduleTimesShouldAcceptCommonFormats() {
        assertThat(DigestConfigHelper.parseScheduleTimes("07:00"))
            .containsExactly(LocalTime.of(7, 0));

        // 多时刻、半角/全角逗号、个位数小时、前后空格都要能解析，并按时间排序去重
        assertThat(DigestConfigHelper.parseScheduleTimes(" 19:00,7:00，07:00 "))
            .containsExactly(LocalTime.of(7, 0), LocalTime.of(19, 0));
    }

    @Test
    void parseScheduleTimesShouldFallBackToSevenWhenNothingValid() {
        assertThat(DigestConfigHelper.parseScheduleTimes(null))
            .containsExactly(LocalTime.of(7, 0));
        assertThat(DigestConfigHelper.parseScheduleTimes("abc"))
            .containsExactly(LocalTime.of(7, 0));
        // 越界时刻（25:00 / 07:70）直接丢弃
        assertThat(DigestConfigHelper.parseScheduleTimes("25:00,07:70"))
            .containsExactly(LocalTime.of(7, 0));
    }
}
