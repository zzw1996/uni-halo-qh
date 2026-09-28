package pro.uhalo.uni.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型单价（设置分组里的 {@code aiPrices} 一行）。
 *
 * <p>三个价格填的都是<b>高峰时段</b>的价（DeepSeek 价目表的主价），空闲时段按
 * {@link #offPeakRatio()} 折算——官方原文是「空闲时段价格为高峰时段价格的一半」，
 * 所以默认 0.5。不区分时段的供应商把比例填 1 即可。</p>
 *
 * <p>命中/未命中分开是硬要求：DeepSeek 的缓存命中输入价是未命中价的<b>五十分之一</b>
 * （0.02 vs 1 元/百万 token），而日报评分的提示词前缀固定、跨批次极易命中前缀缓存，
 * 合起来算会让费用差一个数量级。</p>
 *
 * <p>{@code model} 是<b>匹配串</b>而不是精确模型名：先精确匹配，再按最长的包含匹配，
 * 这样填一条 {@code deepseek} 就能覆盖同族的新模型；模型改名时也不用改价格表。</p>
 *
 * @param model        模型匹配串（如 deepseek-flash、deepseek）
 * @param currency     币种代码（CNY / USD）
 * @param cacheHit     高峰时段「缓存命中输入」单价（每百万 token）
 * @param cacheMiss    高峰时段「缓存未命中输入」单价（每百万 token）
 * @param output       高峰时段输出单价（每百万 token）
 * @param offPeakRatio 空闲时段相对高峰的折扣（0.5 = 官方口径的一半；1 = 不区分时段）
 */
public record DigestAiPriceDTO(String model, String currency, BigDecimal cacheHit,
                               BigDecimal cacheMiss, BigDecimal output,
                               BigDecimal offPeakRatio) {

    /** 官方口径：空闲时段为高峰时段的一半 */
    public static final BigDecimal DEFAULT_OFF_PEAK_RATIO = new BigDecimal("0.5");

    public static DigestAiPriceDTO of(String model, String currency, String cacheHit,
        String cacheMiss, String output) {
        return of(model, currency, cacheHit, cacheMiss, output, null);
    }

    public static DigestAiPriceDTO of(String model, String currency, String cacheHit,
        String cacheMiss, String output, String offPeakRatio) {
        return new DigestAiPriceDTO(model, nullIfBlank(currency), decimal(cacheHit),
            decimal(cacheMiss), decimal(output), ratio(offPeakRatio));
    }

    public DigestAiPriceDTO {
        offPeakRatio = offPeakRatio == null ? DEFAULT_OFF_PEAK_RATIO : offPeakRatio;
    }

    /** 是否可用于计价：输入与输出至少各有一个价 */
    public boolean usable() {
        return cacheMiss != null || output != null;
    }

    /** 某个单价在指定时段下的实际价格（空闲时段按比例折算） */
    public BigDecimal priceAt(BigDecimal peakPrice, boolean peak) {
        if (peakPrice == null) {
            return null;
        }
        if (peak) {
            return peakPrice;
        }
        return peakPrice.multiply(offPeakRatio).setScale(8, RoundingMode.HALF_UP)
            .stripTrailingZeros();
    }

    /** 展示用：一行说清三个价（面板与日志里用） */
    public String describe() {
        return (currency == null ? "" : currency + " ") + "命中 " + plain(cacheHit)
            + " / 未命中 " + plain(cacheMiss) + " / 输出 " + plain(output)
            + "（每百万 token，高峰价；空闲 ×" + offPeakRatio.stripTrailingZeros().toPlainString()
            + "）";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "未填" : value.stripTrailingZeros().toPlainString();
    }

    /** 空闲折扣：填错（负数、超过 1）按官方默认处理，不让一个笔误把费用算歪 */
    private static BigDecimal ratio(String raw) {
        var parsed = decimal(raw);
        if (parsed == null || parsed.signum() <= 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
            return DEFAULT_OFF_PEAK_RATIO;
        }
        return parsed;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            // 单价填错不该让整轮运行出问题：当作没填，由面板提示「未配置单价」
            return null;
        }
    }
}
