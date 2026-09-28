package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiCostDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 花费估算：单价匹配、缓存命中/未命中分开计价、「算不出来」与「0 元」必须区分。
 *
 * <p>这一层的数字会被站长用来判断成本，算错的后果不是报错而是错误决策，
 * 所以每条规则都单独钉住。</p>
 */
class DigestAiPricingTest {

    private static DigestConfigDTO.DigestConfigDTOBuilder configBuilder() {
        return DigestConfigDTO.builder().aiApiBase("").aiApiKey("");
    }

    /**
     * 固定的高峰时刻（北京时间周一 10:00）。
     *
     * <p>必须显示传入时刻：estimate 的默认重载用「现在」，那样测试会在
     * 9:00 或 18:00 边界上随机失败——价差一倍，断言数字全变。</p>
     */
    private static final java.time.Instant PEAK =
        java.time.ZonedDateTime.of(java.time.LocalDateTime.parse("2026-09-28T10:00"),
            DigestAiPeakWindow.ZONE).toInstant();

    private static DigestAiUsageDTO usage(int prompt, int completion, int hit, int miss,
        boolean reported) {
        return new DigestAiUsageDTO(prompt, completion, prompt + completion, hit, miss, 0,
            reported);
    }

    @Test
    void pricingShouldSplitCacheHitAndMiss() {
        // 命中价 0.003、未命中价 0.15（差 50 倍）：把两者合起来算会差一个数量级
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("test-model", "USD", "0.003", "0.15", "0.6"))).build();
        // 100000 命中 + 100000 未命中输入，100000 输出
        var cost = DigestAiPricing.estimate(config, "test-model",
            usage(200000, 100000, 100000, 100000, true), PEAK).orElseThrow();

        // 0.003*1e5/1e6 + 0.15*1e5/1e6 + 0.6*1e5/1e6 = 0.0003 + 0.015 + 0.06
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0753"));
    }

    @Test
    void pricingShouldTreatAllInputAsMissWhenCacheBreakdownMissing() {
        // 对方没给缓存明细时按未命中价算（偏保守）：宁可高估，也不要因为假设命中而低估
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("m", "CNY", "0.5", "2", "8"))).build();
        var cost = DigestAiPricing.estimate(config, "m", usage(1000, 500, 0, 0, true), PEAK)
            .orElseThrow();

        // 1000/1e6*2 + 500/1e6*8 = 0.002 + 0.004
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.006"));
    }

    @Test
    void longestMatchShouldWin() {
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("deepseek", "USD", "0.01", "0.1", "0.2"),
            DigestAiPriceDTO.of("deepseek-v4-pro", "USD", "0.022", "0.66", "1.98"))).build();

        assertThat(DigestAiPricing.match(config, "deepseek-v4-pro").orElseThrow().cacheMiss())
            .isEqualByComparingTo(new BigDecimal("0.66"));
        // 同族的未知型号落到短匹配上：模型改名不用改价格表
        assertThat(DigestAiPricing.match(config, "deepseek-future-9000").orElseThrow().model())
            .isEqualTo("deepseek");
    }

    @Test
    void exactMatchShouldBeatSubstring() {
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("gpt-4o-mini", "USD", "0.075", "0.15", "0.6"),
            DigestAiPriceDTO.of("gpt-4o", "USD", "1.25", "2.5", "10"))).build();

        // gpt-4o-mini 同时命中两条（都包含 gpt-4o），必须拿到精确那条
        var price = DigestAiPricing.match(config, "gpt-4o-mini").orElseThrow();
        assertThat(price.model()).isEqualTo("gpt-4o-mini");
        assertThat(price.output()).isEqualByComparingTo(new BigDecimal("0.6"));
    }

    @Test
    void unknownModelShouldNotBePricedAsZero() {
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("deepseek", "USD", "0.003", "0.15", "0.6"))).build();

        // 算不出来 = 空，而不是 0——0 会被站长读成「免费」
        assertThat(DigestAiPricing.estimate(config, "some-other-model",
            usage(100, 50, 0, 0, true), PEAK)).isEmpty();
    }

    @Test
    void unreportedUsageShouldNotBePriced() {
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("deepseek", "USD", "0.003", "0.15", "0.6"))).build();

        assertThat(DigestAiPricing.estimate(config, "deepseek",
            DigestAiUsageDTO.unreported())).isEmpty();
    }

    @Test
    void emptyPriceTableShouldFallBackToBuiltInDefaults() {
        // 升级上来的站点没填价格表，也应该能算出 DeepSeek 的费用
        var config = configBuilder().build();

        assertThat(DigestAiPricing.priceTable(config))
            .isEqualTo(DigestAiPricing.DEFAULT_PRICES);
        assertThat(DigestAiPricing.estimate(config, "deepseek-flash", usage(1000, 1000, 0, 0, true)))
            .isPresent();
    }

    @Test
    void brokenPriceValueShouldBeIgnoredInsteadOfThrowing() {
        // 单价填成中文/空值时按「没填」处理，不能让它把整轮运行带崩
        var config = configBuilder().aiPrices(List.of(
            DigestAiPriceDTO.of("m", "CNY", "便宜", "", ""))).build();

        assertThat(DigestAiPricing.match(config, "m")).isEmpty();
        assertThat(DigestAiPricing.estimate(config, "m", usage(10, 10, 0, 0, true), PEAK))
            .isEmpty();
    }

    @Test
    void amountFormatShouldKeepTinyValuesVisible() {
        assertThat(DigestAiCostDTO.formatAmount(new BigDecimal("0.4213"))).isEqualTo("0.4213");
        // 一次评分调用只有几厘钱：不能四舍五入成 0，那会被读成「免费」
        assertThat(DigestAiCostDTO.formatAmount(new BigDecimal("0.00001"))).isEqualTo("<0.0001");
        assertThat(DigestAiCostDTO.formatAmount(new BigDecimal("0"))).isEqualTo("0");
    }

    @Test
    void summaryTextShouldDistinguishUnpricedFromNotCollected() {
        // 只记到次数、算不出钱（模型不在单价表里）：列表要写「未配置单价」，
        // 不能显示成空白——空白会被读成「这个功能没生效」
        var unpriced = DigestAiCostDTO.builder()
            .calls(3)
            .tasks(List.of())
            .totals(List.of())
            .build();
        assertThat(DigestAiCostDTO.summaryText(unpriced))
            .isEqualTo(DigestAiCostDTO.UNPRICED_TEXT);

        // 压根没采集（1.0.165 之前的记录）：空串，界面显示「-」
        assertThat(DigestAiCostDTO.summaryText(DigestAiCostDTO.builder().calls(0).build())).isEmpty();
        assertThat(DigestAiCostDTO.summaryText(null)).isEmpty();
    }

    @Test
    void summaryTextShouldGroupByCurrency() {
        var cost = DigestAiCostDTO.builder()
            .totals(List.of(
                DigestAiCostDTO.Money.builder().currency("CNY").amount("0.4213").build(),
                DigestAiCostDTO.Money.builder().currency("USD").amount("0.05").build()))
            .build();

        assertThat(DigestAiCostDTO.summaryText(cost)).isEqualTo("≈¥0.4213 + $0.05");
        assertThat(DigestAiCostDTO.summaryText(DigestAiCostDTO.builder().build())).isEmpty();
    }
}
