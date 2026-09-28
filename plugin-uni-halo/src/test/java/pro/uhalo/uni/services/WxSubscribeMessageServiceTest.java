package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.PushConfigDTO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅消息下发的两个硬约束：字段截断与错误码分类。
 *
 * <p>它们一个决定「消息能不能发出去」（{@code thing} 超 20 字 → 47003），
 * 一个决定「发不出去之后怎么办」（用户没额度要清额度静默处理，
 * 配置问题要让站长知道）。这两件事都不该靠线上试错来发现。</p>
 */
class WxSubscribeMessageServiceTest {

    @Test
    void clipShouldTruncateThingFieldsToTwentyCharacters() {
        assertThat(WxSubscribeMessageService.clip("短标题")).isEqualTo("短标题");
        assertThat(WxSubscribeMessageService.clip("这是一条恰好二十个字的文章标题用于测试截断逻辑"))
            .hasSize(20);
        assertThat(WxSubscribeMessageService.clip("a".repeat(30))).hasSize(20);
        assertThat(WxSubscribeMessageService.clip("  多余   空白\n换行  "))
            .isEqualTo("多余 空白 换行");
        assertThat(WxSubscribeMessageService.clip(null)).isEmpty();
        assertThat(WxSubscribeMessageService.clip("   ")).isEmpty();
    }

    @Test
    void clipShouldCountEmojiAsOneCharacter() {
        // emoji 在 Java 里占两个 char：按 code point 计数才不会把它们截成乱码
        var title = "🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀额外内容";

        var clipped = WxSubscribeMessageService.clip(title);

        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(20);
        assertThat(clipped).doesNotContain("额外");
    }

    @Test
    void clipShouldKeepShortTitlesIntact() {
        assertThat(WxSubscribeMessageService.clip("Rust 1.90 发布")).isEqualTo("Rust 1.90 发布");
    }

    @Test
    void classifyShouldMarkQuotaExhaustedFor43101() {
        var result = WxSubscribeMessageService.classify(43101, "user refuse to accept the msg");

        assertThat(result.ok()).isFalse();
        assertThat(result.quotaExhausted()).isTrue();
        // 用户拒收不是配置问题：不该让站长去改模板
        assertThat(result.configProblem()).isFalse();
    }

    @Test
    void classifyShouldMarkConfigProblems() {
        // 类目不符 / 模板无效 / page 不存在 / 参数不符，都需要站长处理
        assertThat(WxSubscribeMessageService.classify(43102, "template category not match").configProblem())
            .isTrue();
        assertThat(WxSubscribeMessageService.classify(40037, "invalid template_id").configProblem())
            .isTrue();
        assertThat(WxSubscribeMessageService.classify(41030, "invalid page").configProblem())
            .isTrue();
        assertThat(WxSubscribeMessageService.classify(47003, "argument invalid").configProblem())
            .isTrue();
    }

    @Test
    void classifyShouldTreatOtherErrorsAsPlainFailures() {
        var result = WxSubscribeMessageService.classify(45009, "api freq out of limit");

        assertThat(result.ok()).isFalse();
        assertThat(result.quotaExhausted()).isFalse();
        assertThat(result.configProblem()).isFalse();
        assertThat(result.message()).isEqualTo("api freq out of limit");
    }

    @Test
    void templateOfShouldFallBackForAiDigest() {
        var config = PushConfigDTO.builder()
            .enabled(true)
            .templateId("tmpl-new-post")
            .aiDigestTemplateId("")
            .newPostEnabled(true)
            .aiDigestEnabled(true)
            .build();

        assertThat(pro.uhalo.uni.utils.PushConfigHelper.templateOf(config, "newPost"))
            .isEqualTo("tmpl-new-post");
        // 简报模板留空时复用新文章模板：同一个「内容更新通知」模板，只是文案不同
        assertThat(pro.uhalo.uni.utils.PushConfigHelper.templateOf(config, "aiDigest"))
            .isEqualTo("tmpl-new-post");

        var dedicated = PushConfigDTO.builder()
            .enabled(true)
            .templateId("tmpl-new-post")
            .aiDigestTemplateId("tmpl-digest")
            .newPostEnabled(true)
            .aiDigestEnabled(true)
            .build();
        assertThat(pro.uhalo.uni.utils.PushConfigHelper.templateOf(dedicated, "aiDigest"))
            .isEqualTo("tmpl-digest");
    }
}
