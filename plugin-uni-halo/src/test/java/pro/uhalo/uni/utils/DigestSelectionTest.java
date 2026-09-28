package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「每个分类至少几篇」的配额精选口径。
 */
class DigestSelectionTest {

    private static DigestArticleDTO article(String title, String category, int score,
        String imageUrl) {
        var dto = new DigestArticleDTO();
        dto.setTitle(title);
        dto.setCategory(category);
        dto.setImageUrl(imageUrl);
        // 三个维度平分总分，方便测试里直接按 3 分档写分数
        dto.setRelevance(score / 3);
        dto.setQuality(score / 3);
        dto.setTimeliness(score - 2 * (score / 3));
        return dto;
    }

    private static DigestArticleDTO imaged(String title, String category, int score) {
        return article(title, category, score, "https://cdn.example.com/" + title + ".jpg");
    }

    private static DigestArticleDTO plain(String title, String category, int score) {
        return article(title, category, score, "");
    }

    private static final DigestCategoryCatalog CATALOG = DigestCategoryCatalog.defaultCatalog();

    private static DigestSelection.CategorySelection select(List<DigestArticleDTO> candidates,
        int minPerCategory, int maxPerCategory, int limit, boolean requireImage) {
        return DigestSelection.selectByCategory(candidates, CATALOG, minPerCategory,
            maxPerCategory, limit, requireImage);
    }

    private static List<String> titles(DigestSelection.CategorySelection selection) {
        return selection.articles().stream().map(DigestArticleDTO::getTitle).toList();
    }

    @Test
    void quotaShouldGuaranteeOneArticlePerCategory() {
        // ai-ml 高分扎堆，security 只有一篇低分文章——配额生效后它也必须有一篇
        var candidates = new ArrayList<DigestArticleDTO>();
        for (var i = 0; i < 5; i++) {
            candidates.add(imaged("ai" + i, "ai-ml", 27 - i));
        }
        candidates.add(imaged("sec0", "security", 12));

        var selection = select(candidates, 1, 0, 6, true);

        assertThat(selection.countOf("ai-ml")).isEqualTo(5);
        assertThat(selection.countOf("security")).isEqualTo(1);
        assertThat(titles(selection)).contains("sec0");
    }

    @Test
    void quotaShouldPreferArticlesWithCoverImageInsideCategory() {
        // security 有两篇：没图的高分排在前面，但开了「只发布带封面图」时应优先选带图的那篇
        var candidates = List.of(
            plain("sec-高分无图", "security", 28),
            imaged("sec-低分有图", "security", 19));

        var selection = select(candidates, 1, 0, 1, true);

        assertThat(titles(selection)).containsExactly("sec-低分有图");
    }

    @Test
    void quotaShouldReportMissingCategoriesWhenNoCandidateExists() {
        var candidates = List.of(imaged("ai0", "ai-ml", 20), imaged("tool0", "tools", 18));

        var selection = select(candidates, 1, 0, 10, true);

        assertThat(selection.missingCategories())
            .containsExactlyInAnyOrder("security", "engineering", "opinion");
        assertThat(selection.hasMissingCategories()).isTrue();
    }

    @Test
    void fallbackCategoryShouldNotTakeAPartOfTheQuota() {
        // 只有「其他」有文章：它不参与配额，所以配额能达成的分类就是 0 个
        var candidates = List.of(imaged("misc0", "other", 15), imaged("misc1", "other", 14));

        var selection = select(candidates, 1, 0, 5, true);

        assertThat(selection.countOf("other")).isEqualTo(2);
        assertThat(selection.requiredCategories()).doesNotContain("other");
        assertThat(selection.missingCategories()).hasSize(5);
    }

    @Test
    void quotaShouldWinOverTopN() {
        // 六个分类各一篇，但精选篇数只给 3：保底优先。保底已占满 5 个分类（兜底分类不参与），
        // 名额已超上限，因此不再走填充轮
        var candidates = List.of(
            imaged("ai0", "ai-ml", 30), imaged("sec0", "security", 29),
            imaged("eng0", "engineering", 28), imaged("tool0", "tools", 27),
            imaged("op0", "opinion", 26), imaged("misc0", "other", 25));

        var selection = select(candidates, 1, 0, 3, true);

        assertThat(selection.articles()).hasSize(5);
        assertThat(selection.missingCategories()).isEmpty();
        assertThat(titles(selection))
            .containsExactly("ai0", "sec0", "eng0", "tool0", "op0");
    }

    @Test
    void quotaShouldFillRemainingSlotsByScore() {
        var candidates = new ArrayList<DigestArticleDTO>();
        for (var i = 0; i < 6; i++) {
            candidates.add(imaged("ai" + i, "ai-ml", 30 - i));
        }
        candidates.add(imaged("sec0", "security", 10));

        // limit=3：保底给 security 一篇（10 分），剩下两篇按分数给 ai0、ai1
        var selection = select(candidates, 1, 0, 3, true);

        assertThat(selection.articles()).hasSize(3);
        assertThat(titles(selection)).containsExactly("ai0", "ai1", "sec0");
    }

    @Test
    void maxPerCategoryShouldCapTheFillRound() {
        var candidates = new ArrayList<DigestArticleDTO>();
        for (var i = 0; i < 5; i++) {
            candidates.add(imaged("ai" + i, "ai-ml", 30 - i));
        }
        candidates.add(imaged("sec0", "security", 10));

        // 上限 2：ai-ml 最多 2 篇，security 保底 1 篇，剩下的名额没人可补
        var selection = select(candidates, 1, 2, 5, true);

        assertThat(selection.countOf("ai-ml")).isEqualTo(2);
        assertThat(selection.articles()).hasSize(3);
    }

    @Test
    void quotaDisabledShouldFallBackToGlobalTopN() {
        var candidates = new ArrayList<DigestArticleDTO>();
        for (var i = 0; i < 5; i++) {
            candidates.add(imaged("ai" + i, "ai-ml", 30 - i));
        }
        candidates.add(imaged("sec0", "security", 5));

        var selection = select(candidates, 0, 0, 3, true);

        assertThat(titles(selection)).containsExactly("ai0", "ai1", "ai2");
        assertThat(selection.missingCategories()).isEmpty();
        assertThat(selection.quotaEnabled()).isFalse();
    }

    @Test
    void supplementaryArticlesShouldLoseToFreshOnesWithSameScore() {
        var fresh = imaged("sec-本期", "security", 20);
        var old = imaged("sec-补抓", "security", 20);
        old.setSupplementary(true);

        var selection = select(List.of(old, fresh), 1, 0, 1, true);

        assertThat(titles(selection)).containsExactly("sec-本期");
    }

    @Test
    void withoutRequireImageTheScoreOrderShouldWin() {
        // 没开「只发布带封面图」时不按有没有图重排分类内部的顺序
        var candidates = List.of(
            plain("sec-高分无图", "security", 28),
            imaged("sec-低分有图", "security", 19));

        var selection = select(candidates, 1, 0, 1, false);

        assertThat(titles(selection)).containsExactly("sec-高分无图");
    }

    @Test
    void uncategorizedArticlesShouldNotBreakTheQuota() {
        // unknownCategoryPolicy=drop 时分类是空串：它能被正常选出，但不占配额名额，
        // 也不该出现在「需要保底的分类」里（清单里根本没有这个 key）
        var candidates = List.of(article("无分类", "", 20, ""),
            imaged("sec0", "security", 15));

        var selection = select(candidates, 1, 0, 5, true);

        assertThat(selection.countOf("")).isEqualTo(1);
        assertThat(selection.countOf("security")).isEqualTo(1);
        assertThat(selection.requiredCategories()).doesNotContain("");
        assertThat(selection.articles()).hasSize(2);
    }

    @Test
    void emptyOrNullInputShouldNotBlowUp() {
        assertThat(select(List.of(), 1, 0, 5, true).articles()).isEmpty();
        assertThat(DigestSelection.selectByCategory(null, CATALOG, 1, 0, 5, true)
            .missingCategories()).hasSize(5);
        assertThat(DigestSelection.selectByCategory(null, null, 1, 0, 5, true).articles())
            .isEmpty();
    }

    @Test
    void keepUploadedShouldOnlyLeaveUploadedOnes() {
        var uploaded = article("已上传", "ai-ml", 20, "https://cdn.example.com/origin.jpg");
        uploaded.setUploadedImage("https://blog.example.com/upload/1.jpg");
        var notUploaded = article("仅解析到", "ai-ml", 20, "https://cdn.example.com/origin2.jpg");

        assertThat(DigestSelection.keepUploaded(List.of(uploaded, notUploaded)))
            .extracting(DigestArticleDTO::getTitle)
            .containsExactly("已上传");
        assertThat(DigestSelection.hasUploadedImage(notUploaded)).isFalse();
    }

    /**
     * 缺失封面数要真的数出来。
     *
     * <p>原先这个方法的判空条件写成了 {@code !StringUtils.hasText("")}，恒为 true，
     * 于是无论传什么进来都返回 0——一个永远骗人的统计函数。它没有调用点，
     * 但留着这样一段代码，下次谁想用它做统计就会得到静默错误的结果。</p>
     */
    @Test
    void missingImageCountShouldCountArticlesWithoutLeadImage() {
        var withImage = imaged("有图", "ai-ml", 20);
        var blankImage = plain("空地址", "ai-ml", 20);
        var noImage = article("没有图", "ai-ml", 20, null);

        assertThat(DigestSelection.missingImageCount(List.of(withImage, blankImage, noImage)))
            .isEqualTo(2);
        assertThat(DigestSelection.missingImageCount(List.of(withImage))).isZero();
        assertThat(DigestSelection.keepUploaded(null)).isEmpty();
        assertThat(DigestSelection.missingImageCount(null)).isZero();
    }
}
