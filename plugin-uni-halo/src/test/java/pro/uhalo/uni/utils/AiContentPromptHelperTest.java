package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.AiContentItemDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「我的 AI 简报」的提示词与解析。
 *
 * <p>两道线：导语与推荐理由是<b>可选增强</b>（解析失败必须退化为「没有」，不能让简报打不开），
 * 但模型也绝不能借机往列表里塞候选之外的文章——所以越界 index 一律忽略。</p>
 */
class AiContentPromptHelperTest {

    private static AiContentItemDTO item(String title, String source, String category,
        List<String> keywords, String summary) {
        return AiContentItemDTO.builder()
            .title(title)
            .sourceName(source)
            .categoryLabel(category)
            .keywords(keywords)
            .summary(summary)
            .build();
    }

    @Test
    void promptShouldContainPreferencesCandidatesAndWordLimit() {
        var prompt = AiContentPromptHelper.buildIntroPrompt(List.of(
            item("Rust 1.90 发布", "blog.rust-lang.org", "工程", List.of("Rust", "compiler"),
                "编译器改进若干")), "关注主题：工程；关注关键词：rust", 180);

        assertThat(prompt).contains("## 这位读者关注的方向");
        assertThat(prompt).contains("关注主题：工程；关注关键词：rust");
        // 拼接处的换行转义不能把「不超过 180 字」这一句断开
        assertThat(prompt).contains("- 导语不超过 180字，直接讲");
        assertThat(prompt).contains("Index 0: Rust 1.90 发布（来源：blog.rust-lang.org）〔工程〕");
        assertThat(prompt).contains("关键词：Rust, compiler");
        assertThat(prompt).contains("编译器改进若干");
        assertThat(prompt).contains("\"picks\"");
    }

    @Test
    void promptWithoutPreferencesShouldSaySo() {
        var prompt = AiContentPromptHelper.buildIntroPrompt(
            List.of(item("标题", "", "", null, "")), "", 200);

        assertThat(prompt).contains("（未设置具体方向，给他当期全部精选）");
    }

    @Test
    void promptShouldClampTooSmallWordLimit() {
        var prompt = AiContentPromptHelper.buildIntroPrompt(
            List.of(item("标题", "", "", null, "")), "关注主题：工程", 5);

        // 导语下限 30 字：让模型写 5 个字等于没写
        assertThat(prompt).contains("- 导语不超过 30字");
    }

    @Test
    void parseShouldKeepIntroAndPicks() {
        var verdict = AiContentPromptHelper.parse("""
            {"intro":"今天有三篇值得看","picks":[
              {"index":2,"reason":"先看这篇"},
              {"index":0,"reason":"其次是它"}]}""", 3);

        assertThat(verdict.hasIntro()).isTrue();
        assertThat(verdict.intro()).isEqualTo("今天有三篇值得看");
        assertThat(verdict.picks()).extracting(AiContentPromptHelper.Pick::index)
            .containsExactly(2, 0);
        assertThat(verdict.picks().get(0).reasonOrEmpty()).isEqualTo("先看这篇");
    }

    @Test
    void parseShouldDropOutOfRangeIndexes() {
        var verdict = AiContentPromptHelper.parse("""
            {"intro":"导语","picks":[
              {"index":9,"reason":"不存在的文章"},
              {"index":-1,"reason":"负数"},
              {"index":1,"reason":"合法"}]}""", 3);

        // 候选只有 3 条：越界条目不能进入列表，否则简报里会出现打不开的空卡
        assertThat(verdict.picks()).hasSize(1);
        assertThat(verdict.picks().get(0).index()).isEqualTo(1);
    }

    @Test
    void parseShouldAcceptLegacyResultsField() {
        var verdict = AiContentPromptHelper.parse(
            "{\"intro\":\"导语\",\"results\":[{\"index\":0,\"reason\":\"理由\"}]}", 1);

        assertThat(verdict.picks()).hasSize(1);
    }

    @Test
    void parseShouldTolerateMissingFields() {
        var verdict = AiContentPromptHelper.parse("{\"picks\":[{\"index\":0}]}", 1);

        // 没有导语只是「这次没写」，不是错误；推荐理由缺失按空串处理
        assertThat(verdict.hasIntro()).isFalse();
        assertThat(verdict.picks().get(0).reasonOrEmpty()).isEmpty();
    }

    @Test
    void parseShouldFailOnNonJsonSoCallerCanFallBack() {
        assertThatThrownBy(() -> AiContentPromptHelper.parse("抱歉，我无法完成", 1))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void describeShouldJoinConfiguredDimensions() {
        assertThat(AiContentPromptHelper.describe(List.of("engineering"), List.of("rust-lang.org"),
                List.of("rust")))
            .isEqualTo("关注主题：engineering；关注来源：rust-lang.org；关注关键词：rust");
        assertThat(AiContentPromptHelper.describe(List.of(), List.of(), List.of())).isEmpty();
        assertThat(AiContentPromptHelper.describe(null, null, null)).isEmpty();
    }
}
