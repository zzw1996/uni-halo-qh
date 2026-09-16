package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报偏好匹配的规则测试。
 *
 * <p>个性化功能最怕两件事，这里都钉住：</p>
 * <ol>
 *   <li><b>匹配口径含糊</b>——用户不知道某篇为什么出现/为什么不出现。因此每篇命中都必须带
 *       可读的原因，且匹配口径是「或」而不是「与」（日报每天只有几篇，用「与」很容易配到空）；</li>
 *   <li><b>结果顺序随机</b>——同样的偏好每次刷出来顺序都不一样。因此同一份日报内按命中强度
 *       倒序，同强度按标题升序，保证确定性。</li>
 * </ol>
 */
class DigestPreferenceMatcherTest {

    private static DigestPublicArticleDTO article(String title, String category, String label,
        List<String> keywords, String source) {
        return DigestPublicArticleDTO.builder()
            .title(title)
            .category(category)
            .categoryLabel(label)
            .categoryEmoji("🤖")
            .keywords(keywords)
            .sourceName(source)
            .postName("post-" + title)
            .build();
    }

    private static DigestSubscriptionDTO prefs(List<String> categories, List<String> keywords,
        List<String> sources) {
        var dto = DigestSubscriptionDTO.builder()
            .enabled(true)
            .categories(categories == null ? List.of() : categories)
            .keywords(keywords == null ? List.of() : keywords)
            .sources(sources == null ? List.of() : sources)
            .build();
        dto.setFiltered(DigestPreferenceMatcher.hasPreferences(dto));
        return dto;
    }

    /** 没设任何条件时等于「全部」，绝不能返回空——否则用户以为功能坏了。 */
    @Test
    void shouldReturnEverythingWhenNoPreferences() {
        var articles = List.of(
            article("A", "ai-ml", "AI / ML", List.of("llm"), "site-a"),
            article("B", "security", "安全", List.of("sbom"), "site-b"));

        assertThat(DigestPreferenceMatcher.hasPreferences(prefs(List.of(), List.of(), List.of())))
            .isFalse();
        assertThat(DigestPreferenceMatcher.hasPreferences(null)).isFalse();
        assertThat(DigestPreferenceMatcher.apply(articles, prefs(List.of(), null, List.of())))
            .hasSize(2);
    }

    /** 命中任一维度即算命中：口径是「或」，避免条件稍多就什么都不剩。 */
    @Test
    void shouldMatchWhenAnyDimensionMatches() {
        var articles = List.of(
            article("分类命中", "ai-ml", "AI / ML", List.of("llm"), "site-a"),
            article("关键词命中", "security", "安全", List.of("transformer"), "site-b"),
            article("来源命中", "other", "其他", List.of("misc"), "simonwillison.net"),
            article("全不命中", "tools", "工具", List.of("vim"), "site-c"));

        var matched = DigestPreferenceMatcher.apply(articles,
            prefs(List.of("ai-ml"), List.of("Transformer"), List.of("SIMONWILLISON.NET")));

        assertThat(matched).extracting("title")
            .containsExactlyInAnyOrder("分类命中", "关键词命中", "来源命中");
    }

    /** 每篇命中都要给出可读原因：用户据此判断该收紧还是放宽。 */
    @Test
    void shouldExplainWhyEachArticleMatched() {
        var articles = List.of(
            article("多维度命中", "ai-ml", "AI / ML", List.of("llm", "transformer", "agent"),
                "simonwillison.net"));

        var matched = DigestPreferenceMatcher.apply(articles,
            prefs(List.of("ai-ml"), List.of("llm", "transformer"), List.of("simonwillison.net")));

        assertThat(matched).hasSize(1);
        var reason = matched.get(0).getMatchedReason();
        assertThat(reason).contains("分类匹配 · AI / ML");
        assertThat(reason).contains("关键词 · llm、transformer");
        assertThat(reason).contains("关注来源 · simonwillison.net");
    }

    /** 分类权重高于关键词与来源，命中分类的排前面。 */
    @Test
    void shouldRankCategoryMatchesAboveKeywordOnlyMatches() {
        var articles = List.of(
            article("只有关键词", "other", "其他", List.of("llm"), "site-a"),
            article("命中分类", "ai-ml", "AI / ML", List.of("misc"), "site-b"));

        var matched = DigestPreferenceMatcher.apply(articles,
            prefs(List.of("ai-ml"), List.of("llm"), List.of()));

        assertThat(matched).extracting("title").containsExactly("命中分类", "只有关键词");
    }

    /** 同强度的顺序必须确定（按标题升序），否则同样条件每次刷出来的顺序都会变。 */
    @Test
    void shouldProduceDeterministicOrderForEqualScores() {
        var articles = List.of(
            article("C 篇", "ai-ml", "AI / ML", List.of(), "s"),
            article("A 篇", "ai-ml", "AI / ML", List.of(), "s"),
            article("B 篇", "ai-ml", "AI / ML", List.of(), "s"));

        var first = DigestPreferenceMatcher.apply(articles, prefs(List.of("ai-ml"), null, null));
        var second = DigestPreferenceMatcher.apply(articles, prefs(List.of("ai-ml"), null, null));

        assertThat(first).extracting("title").containsExactly("A 篇", "B 篇", "C 篇");
        assertThat(second).extracting("title").containsExactly("A 篇", "B 篇", "C 篇");
    }

    /** 关键词是宽泛词时不能靠它把一篇堆到最前：命中数量有上限。 */
    @Test
    void shouldCapKeywordContribution() {
        var manyKeywords = article("关键词超多", "other", "其他",
            List.of("a", "b", "c", "d", "e", "f"), "s");
        var categoryMatch = article("命中分类", "ai-ml", "AI / ML", List.of(), "s");

        var matched = DigestPreferenceMatcher.apply(List.of(manyKeywords, categoryMatch),
            prefs(List.of("ai-ml"), List.of("a", "b", "c", "d", "e", "f"), null));

        // 分类权重 3，关键词上限 3：两者同分（3 vs 3）时按标题定序
        assertThat(matched).hasSize(2);
    }

    /** 大小写与首尾空格不应影响匹配：用户手填的来源域名大小写往往不统一。 */
    @Test
    void shouldMatchIgnoringCaseAndSpaces() {
        var articles = List.of(
            article("空间站", "AI-ML", "AI / ML", List.of("  LLM  "), " Site-A "));

        var matched = DigestPreferenceMatcher.apply(articles,
            prefs(List.of(" ai-ml "), List.of("llm"), List.of("site-a")));

        assertThat(matched).hasSize(1);
    }

    /** 空文章列表与空偏好都不应抛异常。 */
    @Test
    void shouldHandleEmptyInputs() {
        assertThat(DigestPreferenceMatcher.apply(List.of(), prefs(List.of("ai-ml"), null, null)))
            .isEmpty();
        assertThat(DigestPreferenceMatcher.apply(null, prefs(List.of("ai-ml"), null, null)))
            .isEmpty();
    }

    /** 文章缺分类/关键词/来源时不应误命中，也不应抛 NPE。 */
    @Test
    void shouldNotMatchArticlesWithMissingFields() {
        var broken = DigestPublicArticleDTO.builder().title("字段缺失").build();

        assertThat(DigestPreferenceMatcher.apply(List.of(broken),
            prefs(List.of("ai-ml"), List.of("llm"), List.of("site-a"))))
            .isEmpty();
    }
}
