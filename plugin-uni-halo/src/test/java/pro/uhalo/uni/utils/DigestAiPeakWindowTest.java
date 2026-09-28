package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高峰/空闲时段判定与「按时段计价」。
 *
 * <p>官方口径：北京时间周一至周五 9:00-12:00、14:00-18:00 为高峰，其余（含周末与节假日）空闲，
 * 空闲价是高峰的一半。日报常见的执行时刻正好跨在两个时段上，不区分时段会差一倍。</p>
 */
class DigestAiPeakWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static Instant at(String text) {
        return ZonedDateTime.of(LocalDateTime.parse(text), ZONE).toInstant();
    }

    @Test
    void weekdayBusinessHoursShouldBePeak() {
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T09:00"))).isTrue();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T11:59"))).isTrue();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T14:00"))).isTrue();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T17:59"))).isTrue();
    }

    @Test
    void noonBreakAndEarlyMorningShouldBeOffPeak() {
        // 12:00 整点已经是休息时间：边界必须左闭右开，否则会多算一个点的高峰价
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T12:00"))).isFalse();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T13:59"))).isFalse();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T18:00"))).isFalse();
        // 日报默认 07:00 跑：属于空闲时段（半价）
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-28T07:00"))).isFalse();
    }

    @Test
    void weekendShouldAlwaysBeOffPeak() {
        // 2026-09-26 是周六、09-27 是周日
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-26T10:00"))).isFalse();
        assertThat(DigestAiPeakWindow.isPeak(at("2026-09-27T15:00"))).isFalse();
    }

    @Test
    void estimationShouldUseThePriceOfTheCallingMoment() {
        var config = DigestConfigDTO.builder().aiApiBase("").aiApiKey("")
            .aiPrices(java.util.List.of(
                DigestAiPriceDTO.of("deepseek-flash", "CNY", "0.02", "1", "4")))
            .build();
        var usage = new DigestAiUsageDTO(1000, 1000, 2000, 0, 0, 0, true);

        var peakCost = DigestAiPricing.estimate(config, "deepseek-flash", usage,
            at("2026-09-28T10:00")).orElseThrow();
        var offPeakCost = DigestAiPricing.estimate(config, "deepseek-flash", usage,
            at("2026-09-28T07:00")).orElseThrow();

        // 高峰：1000/1e6*1 + 1000/1e6*4 = 0.005；空闲按一半
        assertThat(peakCost).isEqualByComparingTo(new BigDecimal("0.005"));
        assertThat(offPeakCost).isEqualByComparingTo(new BigDecimal("0.0025"));
    }

    @Test
    void ledgerShouldPriceEachCallAtItsOwnMoment() {
        var config = DigestConfigDTO.builder().aiApiBase("").aiApiKey("")
            .aiPrices(java.util.List.of(
                DigestAiPriceDTO.of("deepseek-flash", "CNY", "0.02", "1", "4")))
            .build();
        var log = DigestAiUsageLog.forConfig(config);
        var channel = new DigestAiProviderDTO("ds", "DeepSeek", "https://api.deepseek.com/v1",
            "sk-ds", "deepseek-flash", true);
        var usage = new DigestAiUsageDTO(1000, 1000, 2000, 0, 0, 0, true);

        // 同一次运行里跨越时段：一条按高峰、一条按空闲
        log.record(DigestAiTask.SCORE, DigestAiReplyDTO.of("ok", channel, channel.model(), usage),
            at("2026-09-28T10:00"));
        log.record(DigestAiTask.SCORE, DigestAiReplyDTO.of("ok", channel, channel.model(), usage),
            at("2026-09-28T07:00"));

        var cost = log.snapshot();

        assertThat(cost.getTasks().get(0).getCalls()).isEqualTo(2);
        // 0.005 + 0.0025：逐次计价才拿得到这个数（收尾时统一算只会得出一条价格）
        assertThat(cost.getTasks().get(0).getAmount()).isEqualTo("0.0075");
        assertThat(cost.getTotals().get(0).getAmount()).isEqualTo("0.0075");
    }

    @Test
    void builtInTableShouldMatchTheOfficialNumbers() {
        // 开箱即用：不填任何价格也能算钱（数值对应官网 CNY 价目表）
        var config = DigestConfigDTO.builder().aiApiBase("").aiApiKey("").build();
        var flash = DigestAiPricing.match(config, "deepseek-flash").orElseThrow();

        assertThat(flash.currency()).isEqualTo("CNY");
        assertThat(flash.cacheHit()).isEqualByComparingTo(new BigDecimal("0.02"));
        assertThat(flash.cacheMiss()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(flash.output()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(DigestAiPricing.match(config, "deepseek-v4-pro").orElseThrow().output())
            .isEqualByComparingTo(new BigDecimal("13.5"));
    }

    @Test
    void mergingFetchedPricesShouldKeepOtherProviders() {
        var existing = java.util.List.of(
            DigestAiPriceDTO.of("deepseek-flash", "CNY", "9", "9", "9"),
            DigestAiPriceDTO.of("gpt-4o-mini", "USD", "0.075", "0.15", "0.6"));
        var fetched = java.util.List.of(
            DigestAiPriceDTO.of("deepseek-flash", "CNY", "0.02", "1", "4"));

        var merged = DigestAiPricing.mergeFetched(existing, fetched);

        // DeepSeek 的条目被官网价覆盖，其它供应商的条目必须留着
        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(DigestAiPriceDTO::model)
            .containsExactly("gpt-4o-mini", "deepseek-flash");
        assertThat(merged.get(1).cacheMiss()).isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    void offPeakRatioShouldBeClampedToSaneValues() {
        // 折扣填 0 或负数会让费用恒为 0（看起来「免费」），按官方默认 0.5 处理
        assertThat(DigestAiPriceDTO.of("m", "CNY", "1", "2", "4", "0").offPeakRatio())
            .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(DigestAiPriceDTO.of("m", "CNY", "1", "2", "4", "1.5").offPeakRatio())
            .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(DigestAiPriceDTO.of("m", "CNY", "1", "2", "4", "1").offPeakRatio())
            .isEqualByComparingTo(BigDecimal.ONE);
    }
}
