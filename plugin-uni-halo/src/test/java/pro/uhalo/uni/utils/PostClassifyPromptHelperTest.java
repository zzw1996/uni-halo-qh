package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.PostClassifyCandidateDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旧文章归类提示词的组装与解析。
 *
 * <p>这里守的是「一条坏数据不拖垮整批」：模型偶尔会漏一个 index、写个不认识的主题、
 * 或者把关键词数组写成字符串。整批因此报废是站长最不能接受的结果——
 * 一次预览要花一次 AI 调用，白跑一次就得再等一轮。</p>
 */
class PostClassifyPromptHelperTest {

    private static PostClassifyCandidateDTO candidate(String title, String excerpt,
        List<String> tags) {
        return PostClassifyCandidateDTO.builder()
            .name("post-" + title.hashCode())
            .title(title)
            .excerpt(excerpt)
            .categories(List.of())
            .tags(tags)
            .build();
    }

    @Test
    void promptShouldCarryTitleExcerptExistingTagsAndCategoryKeys() {
        var prompt = PostClassifyPromptHelper.buildPrompt(List.of(
            candidate("深入理解 Reactor", "背压是怎么工作的", List.of("Java", "响应式"))));

        assertThat(prompt).contains("Index 0: 深入理解 Reactor");
        assertThat(prompt).contains("背压是怎么工作的");
        // 已有标签是重要的判定线索（站长手工整理过的部分），必须带上
        assertThat(prompt).contains("已有标签: Java, 响应式");
        assertThat(prompt).contains("ai-ml").contains("security").contains("engineering")
            .contains("tools").contains("opinion").contains("other");
        assertThat(prompt).contains("\"index\": 0");
    }

    @Test
    void promptShouldNotCarryEmptyExistingLines() {
        var prompt = PostClassifyPromptHelper.buildPrompt(List.of(candidate("标题", "", null)));

        assertThat(prompt).doesNotContain("已有标签");
        assertThat(prompt).doesNotContain("已有分类");
    }

    @Test
    void parseShouldMapEveryIndexInOrder() {
        var verdicts = PostClassifyPromptHelper.parse("""
            {"results":[
              {"index":0,"category":"ai-ml","keywords":["LLM","transformer"]},
              {"index":1,"category":"security","keywords":["CVE"]}
            ]}""", 2);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).category()).isEqualTo("ai-ml");
        assertThat(verdicts.get(0).keywords()).containsExactly("LLM", "transformer");
        assertThat(verdicts.get(1).category()).isEqualTo("security");
    }

    @Test
    void parseShouldTolerateMarkdownFence() {
        var verdicts = PostClassifyPromptHelper.parse("""
            ```json
            {"results":[{"index":0,"category":"tools","keywords":["CLI"]}]}
            ```""", 1);

        assertThat(verdicts.get(0).category()).isEqualTo("tools");
    }

    @Test
    void parseShouldFillMissingIndexWithUnjudgedVerdict() {
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":0,\"category\":\"tools\",\"keywords\":[\"CLI\"]}]}", 3);

        assertThat(verdicts).hasSize(3);
        assertThat(verdicts.get(1).hasCategory()).isFalse();
        assertThat(verdicts.get(2).keywords()).isEmpty();
    }

    @Test
    void parseShouldIgnoreOutOfRangeIndex() {
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":7,\"category\":\"tools\",\"keywords\":[]}]}", 1);

        assertThat(verdicts.get(0).hasCategory()).isFalse();
    }

    @Test
    void parseShouldDropUnknownCategoryInsteadOfFallingBackToOther() {
        // 「AI 说了个不存在的类别」与「AI 判定它属于其它」是两回事：
        // 后者是有效结论，前者要让站长看见并手工处理
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":0,\"category\":\"前端\",\"keywords\":[]}]}", 1);

        assertThat(verdicts.get(0).category()).isEmpty();
        assertThat(verdicts.get(0).hasCategory()).isFalse();
    }

    @Test
    void parseShouldKeepOtherAsARealVerdict() {
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":0,\"category\":\"other\",\"keywords\":[]}]}", 1);

        assertThat(verdicts.get(0).category()).isEqualTo("other");
    }

    @Test
    void parseShouldNormalizeCategoryCase() {
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":0,\"category\":\" AI-ML \",\"keywords\":[]}]}", 1);

        assertThat(verdicts.get(0).category()).isEqualTo("ai-ml");
    }

    @Test
    void parseShouldSplitKeywordsWrittenAsCommaSeparatedString() {
        var verdicts = PostClassifyPromptHelper.parse(
            "{\"results\":[{\"index\":0,\"category\":\"tools\",\"keywords\":\"Rust, CLI、K8s\"}]}", 1);

        assertThat(verdicts.get(0).keywords()).containsExactly("Rust", "CLI", "K8s");
    }

    @Test
    void parseShouldDedupeKeywordsIgnoringCaseAndCapThem() {
        var verdicts = PostClassifyPromptHelper.parse("""
            {"results":[{"index":0,"category":"tools",
              "keywords":["Rust","rust","","CLI","K8s","LLM","extra","another"]}]}""", 1);

        assertThat(verdicts.get(0).keywords()).containsExactly("Rust", "CLI", "K8s", "LLM");
    }

    @Test
    void parseShouldFailWhenResultsArrayMissing() {
        assertThatThrownBy(() -> PostClassifyPromptHelper.parse("{\"data\":[]}", 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("results");
    }

    @Test
    void parseShouldFailOnNonJsonOutput() {
        assertThatThrownBy(() -> PostClassifyPromptHelper.parse("抱歉，我无法完成这个请求", 1))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseOfEmptyBatchShouldReturnEmptyList() {
        assertThat(PostClassifyPromptHelper.parse("{\"results\":[]}", 0)).isEmpty();
    }

    @Test
    void normalizeCategoryShouldRejectBlankAndUnknown() {
        assertThat(PostClassifyPromptHelper.normalizeCategory(null)).isEmpty();
        assertThat(PostClassifyPromptHelper.normalizeCategory("   ")).isEmpty();
        assertThat(PostClassifyPromptHelper.normalizeCategory("nope")).isEmpty();
        assertThat(PostClassifyPromptHelper.normalizeCategory("Engineering")).isEqualTo("engineering");
    }
}
