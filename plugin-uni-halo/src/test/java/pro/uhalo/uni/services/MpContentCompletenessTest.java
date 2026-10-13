package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.infra.DigestSeenStore;
import pro.uhalo.uni.infra.MpIssueStore;
import pro.uhalo.uni.utils.DigestAiUsageLog;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公众号成稿的完整性护栏。
 *
 * <p>线上问题：同一天里「AI 发展」方向的文章内容完整，其它方向只有一半、甚至只剩标题——
 * 起因是模型只回了一半（概述有、小编看法空）或整批没回内容，而<b>原来的代码只校验概述</b>：
 * 缺的分点/小编看法既不会补请求、也不会报错，半篇文章就这样一路写进公众号草稿。</p>
 *
 * <p>这里钉住四件事：完整性判定口径、漏字段不抹已有内容、缺内容会补请求、补不回来要留说明。</p>
 */
class MpContentCompletenessTest {

    // ── 完整性口径 ──

    @Test
    void coreContentNeedsSummaryAndAiTake() {
        var article = article("标题");
        assertThat(MpIssueService.hasCoreContent(article)).isFalse();

        article.setSummary("概述");
        assertThat(MpIssueService.hasCoreContent(article))
            .as("只有概述 = 半篇，不能算完整")
            .isFalse();

        article.setAiTake("小编看法");
        assertThat(MpIssueService.hasCoreContent(article)).isTrue();
    }

    @Test
    void missingPartsShouldListEveryGap() {
        var article = article("标题");
        assertThat(MpIssueService.missingParts(article))
            .isEqualTo("概述、小编看法、核心信息、推荐理由");

        article.setSummary("概述");
        article.setAiTake("小编看法");
        assertThat(MpIssueService.missingParts(article)).isEqualTo("核心信息、推荐理由");

        article.setPoints(List.of("要点一"));
        article.setReason("值得读");
        assertThat(MpIssueService.missingParts(article)).isEmpty();
        assertThat(MpIssueService.needsRepair(article))
            .as("五件套齐了就不用再补请求")
            .isFalse();
    }

    @Test
    void needsRepairShouldCoverHalfWritten() {
        var article = article("标题");
        article.setSummary("概述");
        assertThat(MpIssueService.needsRepair(article)).isTrue();

        article.setAiTake("小编看法");
        article.setPoints(List.of("要点"));
        assertThat(MpIssueService.needsRepair(article)).isFalse();
    }

    @Test
    void contentNoteShouldExplainWhatIsMissing() {
        var complete = article("完整的文章");
        complete.setSummary("概述");
        complete.setAiTake("小编看法");
        assertThat(MpIssueService.contentNoteOf(List.of(complete))).isEmpty();

        var half = article("半截的文章标题很长很长很长很长");
        half.setSummary("概述");
        assertThat(MpIssueService.contentNoteOf(List.of(complete, half)))
            .contains("有 1 篇没拿到完整的 AI 成稿")
            .contains("缺小编看法、核心信息、推荐理由")
            .contains("已用原文摘要顶替");
    }

    @Test
    void overLimitHtmlShouldBeReportedBeforePushing() {
        var article = article("标题");
        article.setSummary("概述");
        article.setAiTake("小编看法");
        var html = "<p>" + "内容".repeat(11000) + "</p>";
        assertThat(pro.uhalo.uni.utils.MpTextUtils.exceedsContentLimit(html)).isTrue();
        assertThat(MpIssueService.issueNote(MpIssueService.contentNoteOf(List.of(article)), html))
            .contains("超过微信 2 万字符上限");
        assertThat(MpIssueService.issueNote("", "<p>短正文</p>")).isEmpty();
    }

    // ── 写回 AI 结果时不能把已有内容抹掉 ──

    @Test
    void partialResultShouldNotBlankExistingFields() {
        var service = serviceWithClient(fakeClient(prompt -> Mono.error(new IllegalStateException("不该被调用"))));
        var article = article("标题");
        article.setSummary("上一次拿到的概述");
        article.setAiTake("上一次拿到的小编看法");
        article.setPoints(List.of("上一次的要点"));
        var batch = new MpIssueService.Batch(0, List.of(article));

        // 模型这次只回了中文标题与推荐理由（漏了概述/小编看法/分点）
        var applied = service.applySummaries(batch, """
            {"results":[{"index":0,"titleZh":"中文标题","reason":"值得一读"}]}
            """);

        assertThat(applied).isEqualTo(1);
        assertThat(article.getTitleZh()).isEqualTo("中文标题");
        assertThat(article.getReason()).isEqualTo("值得一读");
        // 关键：漏回的字段保持原值，不能变成空串（否则补请求会越修越少）
        assertThat(article.getSummary()).isEqualTo("上一次拿到的概述");
        assertThat(article.getAiTake()).isEqualTo("上一次拿到的小编看法");
        assertThat(article.getPoints()).containsExactly("上一次的要点");
    }

    @Test
    void emptyResultShouldBeTreatedAsNothingApplied() {
        var service = serviceWithClient(fakeClient(prompt -> Mono.error(new IllegalStateException("不该被调用"))));
        var article = article("标题");
        var batch = new MpIssueService.Batch(0, List.of(article));

        var applied = service.applySummaries(batch, """
            {"results":[{"index":0,"titleZh":"","summary":"","points":[],"aiTake":"","reason":""}]}
            """);

        assertThat(applied).as("整条都没内容 = 0，触发拆批重试").isZero();
    }

    // ── 缺内容的篇目会补请求 ──

    @Test
    void incompleteArticlesShouldBeRepairedByFollowUpCalls() {
        var calls = new AtomicInteger();
        // 第 1 次（整批）：只回概述；第 2 次（补请求）：把小编看法与分点补齐
        var service = serviceWithClient(fakeClient(prompt -> {
            var round = calls.incrementAndGet();
            return Mono.just(reply(round == 1
                ? """
                  {"results":[{"index":0,"summary":"整批拿到的概述","points":[],"aiTake":""}]}
                  """
                : """
                  {"results":[{"index":0,"summary":"","points":["要点一","要点二"],"aiTake":"补上的小编看法"}]}
                  """));
        }));
        var article = article("标题");
        var config = DigestConfigDTO.builder().words(500).build();

        StepVerifier.create(service.summarizeBatchwise(List.of(article), config, 500,
                DigestAiUsageLog.forConfig(config)))
            .assertNext(list -> {
                assertThat(list).hasSize(1);
                assertThat(MpIssueService.hasCoreContent(article)).isTrue();
                assertThat(article.getAiTake()).isEqualTo("补上的小编看法");
                assertThat(article.getSummary()).as("补请求不该抹掉整批已有的概述")
                    .isEqualTo("整批拿到的概述");
                assertThat(article.getPoints()).containsExactly("要点一", "要点二");
            })
            .verifyComplete();
        assertThat(calls.get()).as("整批 1 次 + 补请求 1 次").isEqualTo(2);
    }

    @Test
    void repairShouldGiveUpAfterLimitedAttemptsAndKeepWhatItHas() {
        var calls = new AtomicInteger();
        var service = serviceWithClient(fakeClient(prompt -> {
            calls.incrementAndGet();
            // 每次只回概述：补请求拿不到小编看法，几次之后必须放弃（不能无限烧额度）
            return Mono.just(reply("""
                {"results":[{"index":0,"summary":"只有概述","points":[],"aiTake":""}]}
                """));
        }));
        var article = article("标题");
        article.setDescription("原文摘要兜底内容");
        var config = DigestConfigDTO.builder().words(500).build();

        StepVerifier.create(service.summarizeBatchwise(List.of(article), config, 500,
                DigestAiUsageLog.forConfig(config)))
            .assertNext(list -> {
                assertThat(article.getSummary()).isEqualTo("只有概述");
                assertThat(MpIssueService.contentNoteOf(list)).contains("缺小编看法");
            })
            .verifyComplete();
        assertThat(calls.get()).as("整批 1 次 + 补请求 2 次（到点放弃）").isEqualTo(3);
    }

    @Test
    void failingChannelShouldNotBreakGeneration() {
        var calls = new AtomicInteger();
        var service = serviceWithClient(fakeClient(prompt -> {
            if (calls.incrementAndGet() == 1) {
                return Mono.error(new IllegalStateException("通道限流"));
            }
            return Mono.just(reply("""
                {"results":[{"index":0,"summary":"概述","points":["要点"],"aiTake":"看法"}]}
                """));
        }));
        var article = article("标题");
        var config = DigestConfigDTO.builder().words(500).build();

        StepVerifier.create(service.summarizeBatchwise(List.of(article), config, 500,
                DigestAiUsageLog.forConfig(config)))
            .assertNext(list -> {
                assertThat(MpIssueService.hasCoreContent(article)).isTrue();
                assertThat(MpIssueService.contentNoteOf(list)).isEmpty();
            })
            .verifyComplete();
    }

    // ── 测试脚手架 ──

    /** 假客户端：只实现 call（成稿链路唯一的 AI 入口），其余方法不该被用到 */
    private static DigestAiClient fakeClient(java.util.function.Function<String, Mono<DigestAiReplyDTO>> responder) {
        return new DigestAiClient() {
            @Override
            public Mono<DigestAiReplyDTO> call(DigestConfigDTO config, String prompt,
                DigestAiTask task) {
                return responder.apply(prompt);
            }

            @Override
            public Mono<pro.uhalo.uni.dto.DigestAiBalanceDTO> queryBalance(DigestAiProviderDTO channel) {
                throw new UnsupportedOperationException("成稿链路不查余额");
            }

            @Override
            public Mono<pro.uhalo.uni.dto.DigestAiProbeResultDTO> probe(DigestAiProviderDTO channel) {
                throw new UnsupportedOperationException("成稿链路不做连通性测试");
            }

            @Override
            public void resetForNewRun() {
                // 测试里无需重置降级状态
            }
        };
    }

    /** 只装配成稿链路需要的依赖：其余协作者用 Mockito 空实现占位 */
    private static MpIssueService serviceWithClient(DigestAiClient client) {
        var settingFetcher = Mockito.mock(ReactiveSettingFetcher.class);
        var feedService = Mockito.mock(DigestFeedService.class);
        var seenStore = Mockito.mock(DigestSeenStore.class);
        var assetUploader = Mockito.mock(DigestAssetUploader.class);
        var imageService = Mockito.mock(MpImageService.class);
        var store = Mockito.mock(MpIssueStore.class);
        var htmlRenderer = new MpHtmlRenderer();
        var issueRenderer = new MpIssueRenderer();
        return new MpIssueService(settingFetcher, feedService, client, seenStore, assetUploader,
            imageService, htmlRenderer, issueRenderer, store);
    }

    private static DigestAiReplyDTO reply(String text) {
        var channel = new DigestAiProviderDTO("test", "测试通道", "https://api.example.com/v1", "k",
            "test-model", true);
        return DigestAiReplyDTO.of(text, channel, channel.model(), null);
    }

    private static DigestArticleDTO article(String title) {
        var dto = new DigestArticleDTO();
        dto.setTitle(title);
        dto.setLink("https://example.com/" + title);
        dto.setSourceName("example.com");
        dto.setPubDate(Instant.now());
        dto.setKeywords(new ArrayList<>());
        return dto;
    }
}
