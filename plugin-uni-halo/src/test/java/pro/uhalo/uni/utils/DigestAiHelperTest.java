package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提示词构造与模型响应解析：索引口径、JSON 破损兜底、概览纯文本解析。
 */
class DigestAiHelperTest {

    private static DigestArticleDTO article(String title, String description) {
        var article = new DigestArticleDTO();
        article.setTitle(title);
        article.setLink("https://example.com/" + title);
        article.setDescription(description);
        article.setSourceName("example.com");
        return article;
    }

    @Test
    void scoringPromptShouldUseGlobalIndexAndTruncateDescription() {
        var longDescription = "x".repeat(500);
        var prompt = DigestAiHelper.buildScoringPrompt(
            List.of(article("First", "short"), article("Second", longDescription)), 20);

        assertThat(prompt).contains("Index 20: [example.com] First");
        assertThat(prompt).contains("Index 21: [example.com] Second");
        // 正文截断到 300 字，避免长描述把提示词顶爆
        assertThat(prompt).contains("x".repeat(300));
        assertThat(prompt).doesNotContain("x".repeat(301));
        // JSON 示例里的分类取清单第一项，保证示例永远是合法值
        assertThat(prompt).contains(
            "\"category\": \"" + DigestCategoryCatalog.defaultCatalog().sampleKey() + "\"");
    }

    @Test
    void scoringPromptShouldListCategoriesFromConfiguredCatalog() {
        var catalog = DigestCategoryCatalog.from(List.of(
            new pro.uhalo.uni.dto.DigestCategoryConfigDTO("wechat", "微信生态", "💬",
                "小程序、公众号、微信支付相关", "微信")));

        var prompt = DigestAiHelper.buildScoringPrompt(List.of(article("A", "d")), 0, catalog);

        // 站长配了什么，AI 就只能选什么；清单外的分类不再出现在提示词里
        assertThat(prompt).contains("- wechat: 小程序、公众号、微信支付相关");
        assertThat(prompt).doesNotContain("ai-ml");
        assertThat(prompt).contains("\"category\": \"wechat\"");
    }

    @Test
    void summaryPromptShouldScaleOverviewAndPointQuotaByWords() {
        var prompt = DigestAiHelper.buildSummaryPrompt(List.of(article("A", "d")), 0, "zh", 600);

        // 概述取一半篇幅，每条论点取剩下的一半再除以 3，且不低于 25 字
        assertThat(prompt).contains("300 字左右的概述段落（不少于 255 字）");
        assertThat(prompt).contains("每条约 100 字（不少于 85 字）");
        assertThat(prompt).contains("合计不少于 600 字");
    }

    @Test
    void overviewPromptShouldGiveChineseInstructionByDefault() {
        var prompt = DigestAiHelper.buildOverviewPrompt(List.of(article("A", "d")), "zh", 300);

        assertThat(prompt).contains("用中文回答。");
        assertThat(prompt).contains("【看点】");
        assertThat(prompt).contains("1. [null] A");
    }

    @Test
    void parseJsonShouldStripCodeFenceAndTrailingProse() throws Exception {
        var fenced = DigestAiHelper.parseJson("```json\n{\"results\":[]}\n```");
        assertThat(fenced.path("results").isArray()).isTrue();

        // 模型偶尔在 JSON 前后加说明文字，取最外层大括号再解析
        var wrapped = DigestAiHelper.parseJson("好的，结果如下：{\"results\":[{\"index\":0}]} 以上。");
        assertThat(wrapped.path("results").get(0).path("index").asInt()).isZero();
    }

    @Test
    void parseJsonShouldFailWithReadableMessage() {
        assertThatThrownBy(() -> DigestAiHelper.parseJson("这不是 JSON"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void parseOverviewResponseShouldSplitByBracketMarkers() {
        var overview = DigestAiHelper.parseOverviewResponse("""
            【看点】
            今天是势内容
            【背景】
            背景铺垫内容
            """);

        assertThat(overview.trend()).isEqualTo("今天是势内容");
        assertThat(overview.background()).isEqualTo("背景铺垫内容");
    }

    @Test
    void parseOverviewResponseShouldRecoverBrokenJson() {
        var overview = DigestAiHelper.parseOverviewResponse(
            "{\"trend\":\"趋势\\n换行\",\"background\":\"背景\"}");

        assertThat(overview.trend()).isEqualTo("趋势\n换行");
        assertThat(overview.background()).isEqualTo("背景");
    }

    @Test
    void parseOverviewResponseShouldNeverLeakRawJsonIntoReport() {
        // 结构完全破损时退化为纯文本看点，绝不能把 JSON 外壳写进日报
        var overview = DigestAiHelper.parseOverviewResponse("{\"trend\":\"只有看点\"}");

        assertThat(overview.trend()).isEqualTo("只有看点");
        assertThat(overview.trend()).doesNotContain("{");
        assertThat(overview.background()).isEmpty();
    }

    @Test
    void normalizeCategoryShouldRejectUnknownValues() {
        assertThat(DigestAiHelper.normalizeCategory("ai-ml")).isEqualTo("ai-ml");
        assertThat(DigestAiHelper.normalizeCategory("nonsense")).isEqualTo("other");
        assertThat(DigestAiHelper.normalizeCategory(null)).isEqualTo("other");
    }

    @Test
    void clampScoreShouldKeepScoresWithinOneToTen() {
        assertThat(DigestAiHelper.clampScore(0)).isEqualTo(1);
        assertThat(DigestAiHelper.clampScore(11)).isEqualTo(10);
        assertThat(DigestAiHelper.clampScore(7)).isEqualTo(7);
    }
}
