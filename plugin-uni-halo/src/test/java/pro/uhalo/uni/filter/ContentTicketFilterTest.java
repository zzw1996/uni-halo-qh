package pro.uhalo.uni.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付费内容拦截 Filter 的路径解析测试。
 *
 * <p>路径判定是整个付费墙的安全边界：判宽了会把列表接口也拦截（全站文章打不开），
 * 判窄了则付费文章的正文会原样返回（内容泄露）。因此以下用例覆盖详情、列表、
 * 子资源、URL 编码与异常路径，确保「只拦截文章详情、且能正确取出 metadata.name」。</p>
 *
 * <p>口径前提：小程序所有详情页跳转传的都是 {@code post.metadata.name}
 * （见 article-detail.vue 的 onLoad 与各处列表跳转），Filter 必须用同一口径，
 * 否则会出现「扣了券但拦截判定未解锁」或「拦截不到」。</p>
 */
class ContentTicketFilterTest {

    private static final String PREFIX = "/apis/api.content.halo.run/v1alpha1/posts";

    // ==================== 详情路径识别 ====================

    @Test
    void shouldRecognizePostDetailPath() {
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX + "/post-abc")).isTrue();
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX + "/hello-world")).isTrue();
    }

    /** 列表接口绝不能被拦截，否则全站文章列表都打不开。 */
    @Test
    void shouldNotInterceptListPath() {
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX)).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX + "/")).isFalse();
    }

    /** 子资源路径（含额外 /）不属于详情，不拦截。 */
    @Test
    void shouldNotInterceptSubResourcePath() {
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX + "/post-abc/comments")).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath(PREFIX + "/post-abc/content")).isFalse();
    }

    /** 非文章接口（标签、分类、评论）一律不拦截。 */
    @Test
    void shouldNotInterceptOtherApis() {
        assertThat(ContentTicketFilter.isPostDetailPath(
            "/apis/api.content.halo.run/v1alpha1/tags")).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath(
            "/apis/api.content.halo.run/v1alpha1/categories/cat-1/posts")).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath(
            "/apis/api.halo.run/v1alpha1/comments")).isFalse();
    }

    /** 空值与畸形路径不得抛异常（Filter 在请求热路径上，异常会波及正常阅读）。 */
    @Test
    void shouldHandleNullAndMalformedPathSafely() {
        assertThat(ContentTicketFilter.isPostDetailPath(null)).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath("")).isFalse();
        assertThat(ContentTicketFilter.isPostDetailPath("/random/path")).isFalse();
    }

    // ==================== 文章名提取 ====================

    @Test
    void shouldExtractArticleName() {
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/post-abc"))
            .isEqualTo("post-abc");
    }

    /** 前端对 name 做了 encodeURIComponent，含编码字符时必须解码后再比对。 */
    @Test
    void shouldDecodeUrlEncodedArticleName() {
        // 中文文章名被编码为 %XX 序列
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/%E6%96%87%E7%AB%A0"))
            .isEqualTo("文章");
        // 含空格的 name 编码为 %20
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/post%20name"))
            .isEqualTo("post name");
    }

    /** 非详情路径取不出文章名，应返回 null（让调用方走放行分支）。 */
    @Test
    void shouldReturnNullForNonDetailPath() {
        assertThat(ContentTicketFilter.extractArticleName(PREFIX)).isNull();
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/")).isNull();
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/a/b")).isNull();
    }

    /**
     * 畸形百分号编码不得抛异常。
     *
     * <p>%ZZ 不是合法编码，URLDecoder 会抛 IllegalArgumentException；
     * 若不兜底，攻击者构造畸形 URL 即可让 Filter 抛异常。虽然上层有 onErrorResume 兜底放行，
     * 但提取阶段就应保证返回原值而非崩溃。</p>
     */
    @Test
    void shouldNotThrowOnMalformedEncoding() {
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/post%ZZ")).isNotNull();
        assertThat(ContentTicketFilter.extractArticleName(PREFIX + "/%E6%96")).isNotNull();
    }
}
