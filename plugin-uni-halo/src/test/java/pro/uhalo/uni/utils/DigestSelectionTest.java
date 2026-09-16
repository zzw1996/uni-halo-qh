package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「只发布带封面图的文章」的筛选口径。
 */
class DigestSelectionTest {

    private static DigestArticleDTO article(String title, String imageUrl) {
        var dto = new DigestArticleDTO();
        dto.setTitle(title);
        dto.setImageUrl(imageUrl);
        return dto;
    }

    @Test
    void trimShouldDropArticlesWithoutLeadImageWhenRequired() {
        var articles = List.of(
            article("有图A", "https://cdn.example.com/a.jpg"),
            article("无图B", ""),
            article("有图C", "https://cdn.example.com/c.jpg"),
            article("伪头图D", "https://cdn.example.com/logo.png"));

        var kept = DigestSelection.trim(articles, 10, true);

        assertThat(kept).extracting(DigestArticleDTO::getTitle)
            .containsExactly("有图A", "有图C");
    }

    @Test
    void trimShouldKeepEverythingWhenNotRequired() {
        var articles = List.of(article("有图A", "https://cdn.example.com/a.jpg"),
            article("无图B", ""));

        assertThat(DigestSelection.trim(articles, 10, false))
            .extracting(DigestArticleDTO::getTitle)
            .containsExactly("有图A", "无图B");
    }

    @Test
    void trimShouldRespectLimitAfterFiltering() {
        var articles = List.of(
            article("有图A", "https://cdn.example.com/a.jpg"),
            article("有图B", "https://cdn.example.com/b.jpg"),
            article("有图C", "https://cdn.example.com/c.jpg"));

        assertThat(DigestSelection.trim(articles, 2, true)).hasSize(2);
    }

    @Test
    void keepUploadedShouldOnlyLeaveUploadedOnes() {
        var uploaded = article("已上传", "https://cdn.example.com/origin.jpg");
        uploaded.setUploadedImage("https://blog.example.com/upload/1.jpg");
        var notUploaded = article("仅解析到", "https://cdn.example.com/origin2.jpg");

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
        var withImage = article("有图", "https://cdn.example.com/a.jpg");
        var blankImage = article("空地址", "");
        var noImage = article("没有图", null);

        assertThat(DigestSelection.missingImageCount(List.of(withImage, blankImage, noImage)))
            .isEqualTo(2);
        assertThat(DigestSelection.missingImageCount(List.of(withImage))).isZero();
    }

    @Test
    void emptyOrNullInputShouldNotBlowUp() {
        assertThat(DigestSelection.trim(List.of(), 5, true)).isEmpty();
        assertThat(DigestSelection.trim(null, 5, true)).isEmpty();
        assertThat(DigestSelection.keepUploaded(null)).isEmpty();
        assertThat(DigestSelection.missingImageCount(null)).isZero();
    }
}
