package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投稿文章的 slug 生成。
 *
 * <p>为什么必须带 slug：Halo 的建稿接口不带 slug 会被线上以 HTTP 500 拒绝
 * （后台 UI 建稿、AI 日报发布都带 slug；投稿这条路径此前是唯一不设的，
 * 也是唯一失败的）。这里锁定生成规则：URL 安全、长度可控、同名标题不撞车。</p>
 */
class UserPublishArticleSlugTest {

    @Test
    void shouldFallbackToDateSlugForChineseTitle() {
        var today = LocalDate.now();
        var expectedPrefix = String.format("post-%04d%02d%02d-",
            today.getYear(), today.getMonthValue(), today.getDayOfMonth());

        var slug = UserPublishServiceImpl.buildArticleSlug("黄河岸边的盛夏");

        assertThat(slug).startsWith(expectedPrefix);
        assertThat(slug).matches("^[a-z0-9-]+$");
    }

    @Test
    void shouldSlugifyAsciiTitleKeepingOnlyUrlSafeChars() {
        var slug = UserPublishServiceImpl.buildArticleSlug("Hello, Halo 2.20 —— 我的第一篇投稿!");

        assertThat(slug).startsWith("hello-halo-2-20-");
        assertThat(slug).matches("^[a-z0-9-]+$");
        assertThat(slug).doesNotContain("--");
    }

    @Test
    void shouldKeepSlugWithinLengthLimit() {
        var slug = UserPublishServiceImpl.buildArticleSlug("a".repeat(200));

        assertThat(slug.length()).isLessThanOrEqualTo(66);
        assertThat(slug).matches("^[a-z0-9-]+$");
    }

    @Test
    void shouldStayUniqueForSameTitleAndTolerateBlankInput() {
        var first = UserPublishServiceImpl.buildArticleSlug("同名标题");
        var second = UserPublishServiceImpl.buildArticleSlug("同名标题");
        assertThat(first).isNotEqualTo(second);

        assertThat(UserPublishServiceImpl.buildArticleSlug(null)).matches("^post-\\d{8}-[0-9a-f]{5}$");
        assertThat(UserPublishServiceImpl.buildArticleSlug("   ")).matches("^post-\\d{8}-[0-9a-f]{5}$");
        assertThat(UserPublishServiceImpl.buildArticleSlug("!!!")).matches("^post-\\d{8}-[0-9a-f]{5}$");
    }
}
