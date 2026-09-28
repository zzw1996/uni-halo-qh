package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.uhalo.uni.enums.DigestAiTask;

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
    void providerListShouldBeEmptyByDefault() {
        // 清单留空 = 沿用旧的主 / 兜底通道，升级后老站点行为不变
        assertThat(DigestConfigHelper.defaults().getAiProviders()).isEmpty();
        assertThat(DigestConfigHelper.parse(null).getAiProviders()).isEmpty();
    }

    @Test
    void parseProvidersShouldSanitizeRows() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[
              {"key":"ds","label":" DeepSeek 主用 ","apiBase":"https://api.deepseek.com/v1/",
               "apiKey":" sk-1 ","model":"","enabled":true},
              {},
              {"label":"只填了名字的行"},
              {"apiBase":"https://b.example/v1","apiKey":"sk-2","enabled":false},
              {"key":"ds","apiBase":"https://dup.example/v1","apiKey":"sk-3"}
            ]}
            """));

        var providers = config.getAiProviders();
        // 完全空白的一行被丢掉（点了「新增一项」没填），其余逐行保留
        assertThat(providers).hasSize(4);

        var first = providers.get(0);
        assertThat(first.key()).isEqualTo("ds");
        assertThat(first.label()).isEqualTo("DeepSeek 主用");
        // 端点去尾斜杠、Key 去空格
        assertThat(first.apiBase()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(first.apiKey()).isEqualTo("sk-1");
        assertThat(first.enabled()).isTrue();

        // 只填了名字的行保留下来（后台会提示它缺端点），并补一个自动标识
        assertThat(providers.get(1).key()).isNotEmpty();
        assertThat(providers.get(1).label()).isEqualTo("只填了名字的行");

        // 缺开关的按开启处理
        assertThat(providers.get(2).enabled()).isFalse();

        // 标识重复不丢行：换一个标识继续保留（静默丢行等于悄悄少一条备用通道）
        assertThat(providers.get(3).key()).isNotEqualTo("ds");
        assertThat(providers.get(3).apiKey()).isEqualTo("sk-3");
    }

    @Test
    void parseTasksShouldAcceptArrayCommaStringAndCheckboxMap() throws Exception {
        // 三种形态都要认：设置页的控件给什么形状不由我们决定，配置也可能被人工改过
        var array = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[{"apiBase":"https://a.example/v1","apiKey":"sk-a",
                             "tasks":["score","write"]}]}
            """));
        assertThat(array.getAiProviders().get(0).taskList()).containsExactly("score", "write");

        var comma = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[{"apiBase":"https://a.example/v1","apiKey":"sk-a",
                             "tasks":" score ，classify;content "}]}
            """));
        assertThat(comma.getAiProviders().get(0).taskList())
            .containsExactly("score", "classify", "content");

        var map = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[{"apiBase":"https://a.example/v1","apiKey":"sk-a",
                             "tasks":{"score":true,"write":false,"overview":true}}]}
            """));
        assertThat(map.getAiProviders().get(0).taskList()).containsExactly("score", "overview");
    }

    @Test
    void parseTasksShouldDropUnknownKeysAndDuplicates() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[{"apiBase":"https://a.example/v1","apiKey":"sk-a",
                             "tasks":["score","打分","score","WRITE","nonsense"]}]}
            """));
        // 未知标识丢弃（不猜测、不改写），大小写不敏感，去重保序
        assertThat(config.getAiProviders().get(0).taskList()).containsExactly("score", "write");
        assertThat(config.getAiProviders().get(0).handles(DigestAiTask.CONTENT)).isFalse();
    }

    @Test
    void providerWithoutTasksShouldHandleEveryTask() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"aiProviders":[{"apiBase":"https://a.example/v1","apiKey":"sk-a"}]}
            """));
        var provider = config.getAiProviders().get(0);

        assertThat(provider.taskList()).isEmpty();
        for (var task : DigestAiTask.values()) {
            assertThat(provider.handles(task)).isTrue();
        }
    }

    @Test
    void parseProvidersShouldCapAtMaxRows() throws Exception {
        var rows = new StringBuilder();
        for (var i = 0; i < DigestConfigHelper.MAX_AI_PROVIDERS + 5; i++) {
            rows.append(i > 0 ? "," : "")
                .append("{\"apiBase\":\"https://p").append(i).append(".example/v1\",\"apiKey\":\"sk-")
                .append(i).append("\"}");
        }
        var config = DigestConfigHelper.parse(MAPPER.readTree("{\"aiProviders\":[" + rows + "]}"));

        assertThat(config.getAiProviders()).hasSize(DigestConfigHelper.MAX_AI_PROVIDERS);
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

    // ── 分类清单（站长可配） ──

    /**
     * 老站点不配置任何分类相关字段时，必须与升级前完全一致。
     *
     * <p>这是升级安全线：清单留空 = 内置六类，落库方式 = 不存在则自动创建。</p>
     */
    @Test
    void categoryConfigShouldDefaultToLegacyBehaviour() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("{\"enabled\":true}"));

        assertThat(config.getDigestCategories()).isEmpty();
        assertThat(config.getCategoryMode()).isEqualTo(DigestConfigHelper.CATEGORY_MODE_CREATE);
        assertThat(config.getUnknownCategoryPolicy())
            .isEqualTo(DigestConfigHelper.UNKNOWN_CATEGORY_OTHER);
        // 空清单经目录消毒后 == 内置六类
        assertThat(pro.uhalo.uni.utils.DigestCategoryCatalog.from(config.getDigestCategories()).keys())
            .containsExactly("ai-ml", "security", "engineering", "tools", "opinion", "other");
    }

    @Test
    void parseShouldReadCategoryListAndModes() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"enabled":true,"categoryMode":"reuse","unknownCategoryPolicy":"drop",
             "digestCategories":[
               {"key":"wechat","label":"微信生态","emoji":"💬","desc":"微信相关","targetCategory":"技术"},
               {"key":"","label":"空行","emoji":"","desc":"","targetCategory":""},
               {"key":"docs","label":"文档"}
             ]}"""));

        assertThat(config.getCategoryMode()).isEqualTo(DigestConfigHelper.CATEGORY_MODE_REUSE);
        assertThat(config.getUnknownCategoryPolicy()).isEqualTo(DigestConfigHelper.UNKNOWN_CATEGORY_DROP);
        // 空 key 的行被跳过（在目录消毒里处理），其余按填写顺序保留
        assertThat(config.getDigestCategories()).extracting(row -> row.key())
            .containsExactly("wechat", "", "docs");
        assertThat(config.getDigestCategories().get(0).targetCategory()).isEqualTo("技术");
        assertThat(config.getDigestCategories().get(2).label()).isEqualTo("文档");
    }

    @Test
    void parseShouldNormalizeUnknownModeAndPolicyValues() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree(
            "{\"categoryMode\":\"MAPPING\",\"unknownCategoryPolicy\":\"乱填\"}"));

        // 认得的别名归一化，认不出来的一律回到安全默认值
        assertThat(config.getCategoryMode()).isEqualTo(DigestConfigHelper.CATEGORY_MODE_MAP);
        assertThat(config.getUnknownCategoryPolicy())
            .isEqualTo(DigestConfigHelper.UNKNOWN_CATEGORY_OTHER);
    }

    @Test
    void modeLabelsShouldMatchTheSettingOptions() {
        assertThat(DigestConfigHelper.categoryModeLabel("create")).isEqualTo("不存在则自动创建");
        assertThat(DigestConfigHelper.categoryModeLabel("reuse")).isEqualTo("只复用已有分类");
        assertThat(DigestConfigHelper.categoryModeLabel("map")).isEqualTo("按清单映射");
        // 兼容别名与空值都要有结论，后台面板不能显示空白
        assertThat(DigestConfigHelper.categoryModeLabel("existing")).isEqualTo("只复用已有分类");
        assertThat(DigestConfigHelper.categoryModeLabel(null)).isEqualTo("不存在则自动创建");
        assertThat(DigestConfigHelper.unknownCategoryPolicyLabel("drop")).isEqualTo("不打分类");
        assertThat(DigestConfigHelper.unknownCategoryPolicyLabel("other")).isEqualTo("归入兜底分类");
    }

    @Test
    void categoryQuotaDefaultsShouldKeepEveryCategoryCovered() {
        var config = DigestConfigHelper.defaults();

        // 默认「每个分类至少 1 篇」：老站升级后不改配置也能拿到分类保底
        assertThat(config.getMinPerCategory()).isEqualTo(1);
        assertThat(config.getMaxPerCategory()).isZero();
        assertThat(config.isCategoryFallbackEnabled()).isTrue();
        assertThat(config.getCategoryFallbackHours()).isEqualTo(168);
        assertThat(config.getCategoryFallbackScanLimit()).isEqualTo(30);
        assertThat(config.getCoverFallbackMode())
            .isEqualTo(DigestConfigHelper.COVER_FALLBACK_PLACEHOLDER);
    }

    @Test
    void parseShouldClampCategoryQuotaValues() throws Exception {
        var config = DigestConfigHelper.parse(MAPPER.readTree("""
            {"minPerCategory":999,"maxPerCategory":999,"categoryFallbackHours":0,
             "categoryFallbackScanLimit":9999}
            """));

        assertThat(config.getMinPerCategory()).isEqualTo(10);
        assertThat(config.getMaxPerCategory()).isEqualTo(50);
        assertThat(config.getCategoryFallbackHours()).isEqualTo(1);
        assertThat(config.getCategoryFallbackScanLimit()).isEqualTo(100);
    }

    @Test
    void zeroValuesShouldMeanDisabledOrUnlimited() throws Exception {
        // 0 是「关闭配额」与「不限篇数」的合法取值，不能被当成非法值收敛成别的数
        var config = DigestConfigHelper.parse(MAPPER.readTree(
            "{\"minPerCategory\":0,\"maxPerCategory\":0,\"coverFallbackMode\":\"none\"}"));

        assertThat(config.getMinPerCategory()).isZero();
        assertThat(config.getMaxPerCategory()).isZero();
        assertThat(config.getCoverFallbackMode())
            .isEqualTo(DigestConfigHelper.COVER_FALLBACK_NO_COVER);
    }

    @Test
    void coverFallbackLabelsShouldCoverEveryMode() {
        assertThat(DigestConfigHelper.coverFallbackLabel("placeholder")).isEqualTo("生成标题卡片");
        assertThat(DigestConfigHelper.coverFallbackLabel("skip")).isEqualTo("跳过该篇");
        assertThat(DigestConfigHelper.coverFallbackLabel("no-cover")).isEqualTo("允许无封面");
        // 乱填与空值都按默认的「生成标题卡片」处理，界面上不能显示空白
        assertThat(DigestConfigHelper.coverFallbackLabel("乱填")).isEqualTo("生成标题卡片");
        assertThat(DigestConfigHelper.coverFallbackLabel(null)).isEqualTo("生成标题卡片");
    }
}
