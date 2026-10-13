package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.scheme.MpIssue;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 乐观锁与推送字段合并的护栏。
 *
 * <p>线上踩过：一次推送要在同一条记录上写好几步，而 {@code client.update()} 返回的是新实例，
 * 某一步用旧引用时 Halo 会拒绝并抛出
 * {@code Failed to update versioned entity ...; Was the entity updated or deleted concurrently?}。
 * 现在遇到这种冲突会重读记录再写；重写时要<b>只搬运推送字段</b>，
 * 不能把正文/标题这些内容字段覆盖掉（否则会把并发期间重新生成的内容改回去）。</p>
 */
class MpIssueVersionGuardTest {

    private static final String REAL_MESSAGE =
        "Failed to update versioned entity with id '/registry/uni.uhalo.pro/mpIssues/"
            + "mp-2026-10-09-vibe-coding' (version '5') in table [extensions]; "
            + "Was the entity updated or deleted concurrently?";

    @Test
    void shouldRecognizeHaloVersionConflict() {
        assertThat(MpIssueStore.isVersionConflict(new IllegalStateException(REAL_MESSAGE))).isTrue();
        assertThat(MpIssueStore.isVersionConflict(
            new RuntimeException("optimistic locking failed"))).isTrue();
    }

    @Test
    void shouldNotTreatOtherErrorsAsVersionConflict() {
        assertThat(MpIssueStore.isVersionConflict(
            new IllegalStateException("公众号稿件不存在：mp-2026-10-09-vibe-coding"))).isFalse();
        assertThat(MpIssueStore.isVersionConflict(new IllegalStateException("网络请求失败"))).isFalse();
        assertThat(MpIssueStore.isVersionConflict(null)).isFalse();
    }

    @Test
    void copyPushFieldsShouldMovePushStateButKeepContent() {
        var memory = new MpIssue();
        memory.setTrigger("manual");
        memory.setState("DRAFTED");
        memory.setPushMode("draft");
        memory.setDraftMediaId("draft-123");
        memory.setCoverMediaId("cover-abc");
        memory.setArticleUrl("https://mp.weixin.qq.com/s/xxx");
        memory.setError(null);
        memory.setPushLog("10-09 12:00  草稿箱：成功");
        memory.setAttempts(3);
        memory.setFinishedAt(Instant.parse("2026-10-09T04:00:00Z"));
        memory.setWechatHtml("<p>已换图床的正文</p>");
        // 内存实例里的内容字段是旧的，不该覆盖库里的
        memory.setTitle("旧标题");
        memory.setMarkdown("旧正文");
        memory.setHtml("<p>旧正文</p>");
        memory.setDigest("旧摘要");

        var stored = new MpIssue();
        stored.setTitle("新标题");
        stored.setMarkdown("新正文");
        stored.setHtml("<p>新正文</p>");
        stored.setDigest("新摘要");
        stored.setDate("2026-10-09");

        MpPushService.copyPushFields(memory, stored);

        assertThat(stored.getState()).isEqualTo("DRAFTED");
        assertThat(stored.getDraftMediaId()).isEqualTo("draft-123");
        assertThat(stored.getCoverMediaId()).isEqualTo("cover-abc");
        assertThat(stored.getPushLog()).contains("草稿箱：成功");
        assertThat(stored.getAttempts()).isEqualTo(3);
        // 内容以库里为准：并发期间重新生成的内容不能被推送流程的旧引用改回去
        assertThat(stored.getTitle()).isEqualTo("新标题");
        assertThat(stored.getMarkdown()).isEqualTo("新正文");
        assertThat(stored.getDigest()).isEqualTo("新摘要");
        // 推送产物（换过图床的正文）跟着走
        assertThat(stored.getWechatHtml()).isEqualTo("<p>已换图床的正文</p>");
        assertThat(stored.getDate()).isEqualTo("2026-10-09");
    }

    @Test
    void copyPushFieldsShouldNotEraseStoredProductsWithNullMemoryValues() {
        var memory = new MpIssue();
        memory.setState("DRAFTED");
        // 内存里没有换图床的正文与封面 id（例如没开图片转换）：不能把库里的抹掉
        memory.setCoverMediaId(null);
        memory.setWechatHtml(null);
        memory.setImageNote(null);

        var stored = new MpIssue();
        stored.setCoverMediaId("cover-abcd");
        stored.setWechatHtml("<p>已换图床</p>");
        stored.setImageNote("2 张配图已用标题卡片顶替");

        MpPushService.copyPushFields(memory, stored);

        assertThat(stored.getState()).isEqualTo("DRAFTED");
        assertThat(stored.getCoverMediaId()).isEqualTo("cover-abcd");
        assertThat(stored.getWechatHtml()).isEqualTo("<p>已换图床</p>");
        assertThat(stored.getImageNote()).contains("标题卡片");
    }
}
