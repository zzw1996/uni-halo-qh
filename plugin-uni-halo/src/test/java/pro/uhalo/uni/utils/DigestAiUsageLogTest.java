package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiCostDTO;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量账本：按任务/通道累加、并发安全、余额差的口径。
 *
 * <p>两条底线：① 并发批次记账不能丢（评分是并发 2 跑的）；
 * ② 「算不出来」与「0 元」在结果里必须能区分（前者是未配置单价/未上报用量，后者才是真的没花钱）。</p>
 */
class DigestAiUsageLogTest {

    private static final DigestConfigDTO CONFIG = DigestConfigDTO.builder()
        .aiApiBase("")
        .aiApiKey("")
        .aiPrices(List.of(DigestAiPriceDTO.of("cheap", "CNY", "0.5", "2", "8"),
            DigestAiPriceDTO.of("strong", "CNY", "1", "4", "16")))
        .build();

    /** 固定高峰时刻（北京时间周一 10:00）：金额断言不能被「跑测试的时间」左右 */
    private static final java.time.Instant PEAK =
        java.time.ZonedDateTime.of(java.time.LocalDateTime.parse("2026-09-28T10:00"),
            DigestAiPeakWindow.ZONE).toInstant();

    private static DigestAiProviderDTO provider(String key, String model) {
        return new DigestAiProviderDTO(key, key, "https://" + key + ".example/v1", "sk-" + key,
            model, true);
    }

    private static DigestAiReplyDTO reply(DigestAiProviderDTO channel, int inputTokens,
        int outputTokens) {
        return DigestAiReplyDTO.of("ok", channel, channel.model(),
            new DigestAiUsageDTO(inputTokens, outputTokens, inputTokens + outputTokens, 0, 0, 0,
                true));
    }

    @Test
    void snapshotShouldSplitByTask() {
        var log = DigestAiUsageLog.forConfig(CONFIG);
        var cheap = provider("cheap", "cheap");
        var strong = provider("strong", "strong");
        // 评分 2 次（各 1000 输入 / 500 输出），成稿 1 次（各 4000 / 2000）
        log.record(DigestAiTask.SCORE, reply(cheap, 1000, 500), PEAK);
        log.record(DigestAiTask.SCORE, reply(cheap, 1000, 500), PEAK);
        log.record(DigestAiTask.WRITE, reply(strong, 4000, 2000), PEAK);

        var cost = log.snapshot();

        assertThat(cost.getCalls()).isEqualTo(3);
        assertThat(cost.getTasks()).hasSize(2);
        var score = cost.getTasks().get(0);
        assertThat(score.getTask()).isEqualTo("score");
        assertThat(score.getCalls()).isEqualTo(2);
        assertThat(score.getInputTokens()).isEqualTo(2000);
        assertThat(score.getOutputTokens()).isEqualTo(1000);
        // 输入合计 2000、输出合计 1000：2*2000/1e6 + 8*1000/1e6 = 0.004 + 0.008
        assertThat(score.getAmount()).isEqualTo("0.012");
        assertThat(score.isPriced()).isTrue();

        var write = cost.getTasks().get(1);
        assertThat(write.getTask()).isEqualTo("write");
        // 4*4000/1e6 + 16*2000/1e6 = 0.016 + 0.032
        assertThat(write.getAmount()).isEqualTo("0.048");

        // 合计与按通道的拆解：同一币种合并
        assertThat(cost.getTotals()).hasSize(1);
        assertThat(cost.getTotals().get(0).getAmount()).isEqualTo("0.06");
        assertThat(cost.getChannels()).hasSize(2);
        assertThat(cost.getChannels().get(1).getLabel()).isEqualTo("strong");
        assertThat(cost.getNote()).contains("估算");
    }

    @Test
    void snapshotShouldMarkUnpricedAndUnknownUsageSeparately() {
        var log = DigestAiUsageLog.forConfig(CONFIG);
        var unknown = provider("other", "some-unknown-model");
        log.record(DigestAiTask.SCORE, reply(unknown, 1000, 500), PEAK);
        log.record(DigestAiTask.SCORE, DigestAiReplyDTO.of("ok", unknown, unknown.model(),
            DigestAiUsageDTO.unreported()), PEAK);

        var cost = log.snapshot();

        assertThat(cost.getCalls()).isEqualTo(2);
        // 一条没匹配到单价、一条对方没上报用量：分开统计，好让站长知道去哪补
        assertThat(cost.getUnpricedCalls()).isEqualTo(1);
        assertThat(cost.getUnknownUsageCalls()).isEqualTo(1);
        // 缺价时要能说出是哪个模型没配价（面板与详情都靠这个字段点名）
        assertThat(cost.getTasks().get(0).getModel()).isEqualTo("some-unknown-model");
        assertThat(cost.getTasks().get(0).isPriced()).isFalse();
        assertThat(cost.getTotals()).isEmpty();
        assertThat(cost.getNote()).contains("模型单价").contains("未上报用量");
    }

    @Test
    void concurrentRecordingShouldNotLoseCalls() throws Exception {
        // 评分批次是并发 2 跑的，而记账发生在回调里：丢数就等于少算钱
        var log = DigestAiUsageLog.forConfig(CONFIG);
        var channel = provider("cheap", "cheap");
        var threads = 8;
        var perThread = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        for (var i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (var j = 0; j < perThread; j++) {
                        log.record(DigestAiTask.SCORE, reply(channel, 100, 50));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        var cost = log.snapshot();
        assertThat(cost.getCalls()).isEqualTo(threads * perThread);
        assertThat(cost.getTasks().get(0).getInputTokens()).isEqualTo(threads * perThread * 100);
    }

    @Test
    void balanceDeltaShouldComeFromBeforeAfterPair() {
        var log = DigestAiUsageLog.forConfig(CONFIG);
        var channel = provider("deepseek", "deepseek-flash");
        log.record(DigestAiTask.SCORE, reply(channel, 1000, 500), PEAK);
        log.rememberBalanceBefore(DigestAiBalanceDTO.balance("deepseek", "deepseek",
            channel.apiBase(), "CNY", "12.34", "2.00", "10.34", ""));
        log.rememberBalanceAfter(DigestAiBalanceDTO.balance("deepseek", "deepseek",
            channel.apiBase(), "CNY", "11.92", "2.00", "9.92", ""));

        var row = log.snapshot().getChannels().get(0);

        assertThat(row.getBalanceBefore()).isEqualTo("12.34");
        assertThat(row.getBalanceAfter()).isEqualTo("11.92");
        assertThat(row.getBalanceDelta()).isEqualTo("0.42");
        assertThat(row.getBalanceCurrency()).isEqualTo("CNY");
        assertThat(row.getBalanceNote()).isNull();
    }

    @Test
    void unsupportedBalanceShouldBeExplainedNotSilent() {
        var log = DigestAiUsageLog.forConfig(CONFIG);
        var channel = provider("openai", "gpt-4o-mini");
        log.record(DigestAiTask.WRITE, reply(channel, 1000, 500), PEAK);
        log.rememberBalanceBefore(DigestAiBalanceDTO.notSupported("openai", "openai",
            channel.apiBase(), "该端点没有余额接口"));

        var row = log.snapshot().getChannels().get(0);

        // 差额算不出来时必须写明原因，否则空着会被当成功能坏了
        assertThat(row.getBalanceDelta()).isNull();
        assertThat(row.getBalanceNote()).contains("没有余额接口");
    }

    @Test
    void emptyLogShouldNotProduceACostBlock() {
        var log = DigestAiUsageLog.forConfig(CONFIG);

        assertThat(log.isEmpty()).isTrue();
        assertThat(log.snapshot().isEmpty()).isTrue();
        assertThat(log.tasksSeen()).isEmpty();
    }

    @Test
    void usedChannelKeysShouldListOnlyChannelsActuallyCalled() {
        var log = DigestAiUsageLog.forConfig(CONFIG);
        log.record(DigestAiTask.SCORE, reply(provider("cheap", "cheap"), 10, 10), PEAK);

        assertThat(log.usedChannelKeys()).containsExactly("cheap");
    }
}
