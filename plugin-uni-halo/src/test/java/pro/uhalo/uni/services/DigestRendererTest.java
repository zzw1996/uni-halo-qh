package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestStatsDTO;
import pro.uhalo.uni.utils.DigestAiHelper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报成稿：板块完整性、图表开关、配图与署名。
 */
class DigestRendererTest {

    private static final ZonedDateTime GENERATED_AT =
        ZonedDateTime.of(2026, 9, 11, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai"));

    private final DigestRenderer renderer = new DigestRenderer();

    private static DigestArticleDTO article(String titleZh, String category, List<String> keywords) {
        var article = new DigestArticleDTO();
        article.setTitle(titleZh + " (en)");
        article.setTitleZh(titleZh);
        article.setLink("https://example.com/" + titleZh);
        article.setSourceName("example.com");
        article.setPubDate(Instant.now().minusSeconds(3600));
        article.setCategory(category);
        article.setRelevance(9);
        article.setQuality(8);
        article.setTimeliness(7);
        article.setKeywords(keywords);
        article.setSummary("概述内容");
        article.setPoints(List.of("论点一", "论点二"));
        article.setAiTake("这是 AI 自己视角的原创点评，与原文表述无关");
        article.setReason("推荐理由");
        return article;
    }

    private static DigestStatsDTO stats() {
        return DigestStatsDTO.builder()
            .totalFeeds(92)
            .successFeeds(81)
            .totalArticles(2484)
            .filteredArticles(24)
            .selected(4)
            .hours(48)
            .words(600)
            .actualAvgWords(700)
            .imageCount(3)
            .language("zh")
            .durationMs(120000)
            .build();
    }

    private String render(List<DigestArticleDTO> articles, boolean mermaid, String footerNote) {
        return renderer.render(articles, new DigestAiHelper.Overview("今日趋势总结", "背景铺垫内容"),
            stats(), mermaid, footerNote, GENERATED_AT);
    }

    @Test
    void renderShouldIncludeEverySection() {
        var report = render(List.of(
            article("标题一", "ai-ml", List.of("LLM", "Rust")),
            article("标题二", "security", List.of("LLM")),
            article("标题三", "engineering", List.of("database"))
        ), true, "署名行");

        assertThat(report).contains("# 📰 AI 博客每日精选 — 2026-09-11");
        assertThat(report).contains("精选 92 个中英文技术博客（含 Karpathy 推荐源），AI 精选 Top 3，每篇约 700 字");
        assertThat(report).contains("## 📝 今日看点");
        assertThat(report).contains("今日趋势总结");
        assertThat(report).contains("### 🧭 背景铺垫");
        assertThat(report).contains("## 🏆 今日必读");
        assertThat(report).contains("🥇 **标题一**");
        assertThat(report).contains("## 📊 数据概览");
        assertThat(report).contains("| 81/92 | 2484 篇 → 24 篇 | 48h | **3 篇** |");
        assertThat(report).contains("## 🤖 AI / ML");
        assertThat(report).contains("## 🔒 安全");
        // 小编看法：合并模式与逐篇成稿都要带上（防侵权结构里的观点段）
        assertThat(report).contains("✍️ **小编看法**: 这是 AI 自己视角的原创点评，与原文表述无关");
        assertThat(report).contains("*署名行*");
    }

    @Test
    void renderArticleShouldIncludeAiTakeAndDisclaimer() {
        var article = article("标题一", "ai-ml", List.of("Rust"));

        var markdown = renderer.renderArticle(article, stats(), "", GENERATED_AT);

        assertThat(markdown).contains("✍️ **小编看法**: 这是 AI 自己视角的原创点评，与原文表述无关");
        // 版权声明：AI 生成属性 + 版权归属 + 引导读原文，逐篇固定输出
        assertThat(markdown).contains("本文由 AI 辅助生成");
        assertThat(markdown).contains("原文版权归原作者所有");
    }

    @Test
    void renderShouldEmitMermaidChartsOnlyWhenEnabled() {
        var articles = List.of(
            article("标题一", "ai-ml", List.of("LLM", "Rust")),
            article("标题二", "security", List.of("LLM"))
        );

        var withMermaid = render(articles, true, "");
        assertThat(withMermaid).contains("```mermaid\npie showData");
        assertThat(withMermaid).contains("xychart-beta horizontal");

        var withoutMermaid = render(articles, false, "");
        assertThat(withoutMermaid).doesNotContain("```mermaid");
        // 关掉 Mermaid 后纯文本图与标签云仍要保留，终端/不支持图表的环境照样能读
        assertThat(withoutMermaid).contains("<summary>📈 纯文本关键词图（终端友好）</summary>");
        assertThat(withoutMermaid).contains("### 🏷️ 话题标签");
    }

    @Test
    void renderShouldOnlyReferenceUploadedImages() {
        var withImage = article("有图", "ai-ml", List.of("Rust"));
        withImage.setImageUrl("https://origin.example.com/a.jpg");
        withImage.setUploadedImage("https://blog.example.com/upload/a.jpg");

        var withoutImage = article("无图", "ai-ml", List.of("Rust"));
        withoutImage.setImageUrl("https://origin.example.com/b.jpg");

        var report = render(List.of(withImage, withoutImage), true, "");

        // 只引用已上传到附件库的地址，未上传成功的原图不能出现在正文里
        assertThat(report).contains("![有图](https://blog.example.com/upload/a.jpg)");
        assertThat(report).doesNotContain("origin.example.com");
    }

    @Test
    void renderShouldSkipHighlightsWhenOverviewEmpty() {
        var report = renderer.render(List.of(article("标题一", "ai-ml", List.of("Rust"))),
            new DigestAiHelper.Overview("", ""), stats(), true, "", GENERATED_AT);

        assertThat(report).doesNotContain("## 📝 今日看点");
        assertThat(report).contains("## 📊 数据概览");
    }

    @Test
    void renderShouldOmitTopThreeBlockWhenFewerThanThreeArticles() {
        var report = render(List.of(article("唯一一篇", "ai-ml", List.of("Rust"))), true, "");

        assertThat(report).doesNotContain("## 🏆 今日必读");
        assertThat(report).contains("### 1. 唯一一篇");
    }

    @Test
    void renderArticleShouldProduceOneStandalonePost() {
        var article = article("单篇标题", "ai-ml", List.of("LLM", "Rust"));
        article.setUploadedImage("https://blog.example.com/upload/a.jpg");
        article.setReason("推荐理由");

        var post = renderer.renderArticle(article, stats(), "署名行", GENERATED_AT);

        assertThat(post).startsWith("# 单篇标题");
        assertThat(post).contains("](https://example.com/单篇标题)");
        assertThat(post).contains("![单篇标题](https://blog.example.com/upload/a.jpg)");
        assertThat(post).contains("核心信息");
        assertThat(post).contains("- 论点一");
        assertThat(post).contains("💡 **为什么值得读**: 推荐理由");
        assertThat(post).contains("✍️ **小编看法**: 这是 AI 自己视角的原创点评，与原文表述无关");
        assertThat(post).contains("本文由 AI 辅助生成");
        assertThat(post).contains("🏷️ LLM, Rust");
        // 单独成文：不能把日报级别的板块带进来，否则 5 篇就会变成 5 份重复的日报
        assertThat(post).doesNotContain("## 📝 今日看点");
        assertThat(post).doesNotContain("## 📊 数据概览");
        assertThat(post).doesNotContain("今日必读");
        assertThat(post).contains("*署名行*");
    }

    @Test
    void humanizeTimeShouldSwitchUnitsByAge() {
        var now = Instant.now();
        assertThat(DigestRenderer.humanizeTime(now.minusSeconds(600))).isEqualTo("10 分钟前");
        assertThat(DigestRenderer.humanizeTime(now.minusSeconds(7200))).isEqualTo("2 小时前");
        assertThat(DigestRenderer.humanizeTime(now.minusSeconds(2 * 86400))).isEqualTo("2 天前");
        assertThat(DigestRenderer.humanizeTime(null)).isEqualTo("未知时间");
    }

    @Test
    void asciiBarChartShouldAlignLabelsAndScaleBars() {
        var articles = List.of(
            article("一", "ai-ml", List.of("llm", "llm")),
            article("二", "ai-ml", List.of("rust"))
        );

        var chart = renderer.asciiBarChart(articles);

        assertThat(chart).contains("llm");
        assertThat(chart).contains("rust");
        assertThat(chart).contains("█");
        assertThat(chart).contains("░");
        assertThat(chart).contains(" 2");
    }
}
