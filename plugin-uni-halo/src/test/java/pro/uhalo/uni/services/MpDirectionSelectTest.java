package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方向选材（公众号「每方向一篇」+「方向保底」的核心规则）。
 *
 * <p>钉住三件事：明确命中优先、备选只用于补足、已被其它方向收走的文章不会再被选
 * （同一篇文章不能出现在两篇公众号文章里）。</p>
 */
class MpDirectionSelectTest {

    @Test
    void primaryMatchesShouldWinOverSecondary() {
        var scored = List.of(
            article("备选低分", "ai-dev-2", 8, "security", "ai-dev"),
            article("明确高分", "ai-dev-1", 20, "ai-dev", ""),
            article("明确低分", "ai-dev-3", 12, "ai-dev", ""));

        var selected = MpIssueService.selectForDirection("ai-dev", scored, 2, true, new HashSet<>());

        assertThat(titles(selected)).containsExactly("明确高分", "明确低分");
    }

    @Test
    void fallbackShouldFillWhenPrimaryIsNotEnough() {
        var scored = List.of(
            article("明确一篇", "ai-dev-1", 20, "ai-dev", ""),
            article("备选之一", "sec-1", 18, "security", "ai-dev"),
            article("备选之二", "sec-2", 15, "security", "ai-dev"));

        var selected = MpIssueService.selectForDirection("ai-dev", scored, 3, true, new HashSet<>());

        assertThat(titles(selected)).containsExactly("明确一篇", "备选之一", "备选之二");
    }

    @Test
    void fallbackDisabledShouldIgnoreSecondary() {
        var scored = List.of(
            article("明确一篇", "ai-dev-1", 20, "ai-dev", ""),
            article("备选之一", "sec-1", 18, "security", "ai-dev"));

        var selected = MpIssueService.selectForDirection("ai-dev", scored, 3, false, new HashSet<>());

        assertThat(titles(selected)).containsExactly("明确一篇");
    }

    @Test
    void usedArticlesShouldBeSkipped() {
        var taken = article("已被选走", "ai-dev-1", 30, "ai-dev", "");
        var scored = List.of(taken, article("剩下的", "ai-dev-2", 10, "ai-dev", ""));
        var used = new HashSet<String>();
        // 选材用的是归一化去重键（与跨天去重同一口径），不是原始链接
        used.add(pro.uhalo.uni.utils.DigestTextUtils.dedupKey(taken.getLink(), taken.getTitle()));

        var selected = MpIssueService.selectForDirection("ai-dev", scored, 3, true, used);

        assertThat(titles(selected)).containsExactly("剩下的");
    }

    @Test
    void emptyResultWhenNothingMatches() {
        var scored = List.of(article("别的方向", "sec-1", 30, "security", "vibe-coding"));

        assertThat(MpIssueService.selectForDirection("ai-dev", scored, 3, true, Set.of())).isEmpty();
    }

    private static List<String> titles(List<DigestArticleDTO> articles) {
        var list = new ArrayList<String>();
        articles.forEach(article -> list.add(article.getTitle()));
        return list;
    }

    private static DigestArticleDTO article(String title, String link, int score,
        String category, String altCategory) {
        var dto = new DigestArticleDTO();
        dto.setTitle(title);
        dto.setLink("https://example.com/" + link);
        // 总分 = 三项之和：按 1:1:1 分摊，便于用分数表达优先级
        dto.setRelevance(Math.min(10, score / 3));
        dto.setQuality(Math.min(10, score / 3));
        dto.setTimeliness(Math.min(10, score - 2 * (score / 3)));
        dto.setCategory(category);
        dto.setAltCategory(altCategory);
        return dto;
    }
}
