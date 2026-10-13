package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.MpConfigDTO;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.MpConfigHelper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章粒度：默认「每篇精选各一篇」（站长要的「分开推送」），也可切回「每个方向一篇」。
 *
 * <p>钉住三件事：默认粒度、单篇成稿的形态（不带序号标题/不带导语）、
 * 以及每篇稿件的资源名（名字冲突会让同一天的多篇互相覆盖）。</p>
 */
class MpArticleGranularityTest {

    @Test
    void defaultGranularityShouldBePerArticle() {
        assertThat(MpConfigHelper.defaults().getContentGranularity())
            .isEqualTo(MpConfigHelper.GRANULARITY_ARTICLE);
        assertThat(MpConfigHelper.parse(com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build().createObjectNode()).getContentGranularity())
            .isEqualTo(MpConfigHelper.GRANULARITY_ARTICLE);
    }

    @Test
    void directionGranularityShouldBeRecognizedIncludingAliases() {
        for (var raw : new String[] {"direction", "per-direction", "merged", "DIRECTION"}) {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode();
            node.put("contentGranularity", raw);
            assertThat(MpConfigHelper.parse(node).getContentGranularity())
                .as("别名 %s 应识别为「每个方向一篇」", raw)
                .isEqualTo(MpConfigHelper.GRANULARITY_DIRECTION);
        }
        assertThat(MpConfigHelper.granularityLabel(MpConfigHelper.GRANULARITY_DIRECTION))
            .contains("每个方向一篇");
        assertThat(MpConfigHelper.granularityLabel(MpConfigHelper.GRANULARITY_ARTICLE))
            .contains("每篇精选各一篇");
    }

    @Test
    void singleArticleRenderingShouldBeSelfContained() {
        var renderer = new MpIssueRenderer();
        var article = article();

        var markdown = renderer.renderSingle(article, "2026-10-09",
            DigestCategoryCatalog.defaultCatalog());

        // 单篇形态：不带聚合版的序号标题、不带方向导语，但要有配图/概述/小编看法/版权声明
        assertThat(markdown).doesNotContain("## 1.");
        assertThat(markdown).contains("![中文标题](https://site.example/upload/a.jpg)");
        assertThat(markdown).contains("这是一段概述");
        assertThat(markdown).contains("小编看法");
        assertThat(markdown).contains("原文版权归原作者所有");
        assertThat(markdown).contains("https://example.com/post");
    }

    @Test
    void aggregatedRenderingShouldKeepNumberedSections() {
        var renderer = new MpIssueRenderer();
        var markdown = renderer.render("AI 发展", "今天的导语",
            List.of(article(), article()), "2026-10-09",
            DigestCategoryCatalog.defaultCatalog());

        assertThat(markdown).contains("> 今天的导语");
        assertThat(markdown).contains("## 1.").contains("## 2.");
        assertThat(markdown).contains("---");
    }

    @Test
    void perArticleResourceNamesShouldBeUniqueAndValid() {
        var first = MpIssueStore.articleIssueName("2026-10-09", "vibe-coding", 0);
        var second = MpIssueStore.articleIssueName("2026-10-09", "vibe-coding", 1);
        var direction = MpIssueStore.issueName("2026-10-09", "vibe-coding");

        assertThat(first).isEqualTo("mp-2026-10-09-vibe-coding-01");
        assertThat(second).isEqualTo("mp-2026-10-09-vibe-coding-02");
        assertThat(first).isNotEqualTo(second).isNotEqualTo(direction);
        // 资源名只允许小写字母/数字/短横线
        assertThat(first).matches("[a-z0-9-]+");
    }

    private static DigestArticleDTO article() {
        var dto = new DigestArticleDTO();
        dto.setTitle("Original English Title");
        dto.setTitleZh("中文标题");
        dto.setLink("https://example.com/post");
        dto.setSourceName("Example Blog");
        dto.setPubDate(Instant.now().minusSeconds(3600));
        dto.setCategory("ai-dev");
        dto.setSummary("这是一段概述，用来做摘要兜底。");
        dto.setAiTake("这是小编看法。");
        dto.setReason("值得一读的理由。");
        dto.setPoints(List.of("要点一", "要点二"));
        dto.setKeywords(List.of("LLM", "agent"));
        dto.setUploadedImage("https://site.example/upload/a.jpg");
        return dto;
    }
}
