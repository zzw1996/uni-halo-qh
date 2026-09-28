package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 按 token 与单价估算一次调用（或一轮累计）的费用。
 *
 * <p><b>为什么是估算</b>：DeepSeek 官方没有用量/账单查询接口（只有余额查询），
 * 所以「花了多少钱」只能自己算；而且价目表本身还带两个坑——缓存命中与未命中的输入价
 * 差几十倍，高峰与低谷时段又差一倍。价格表只填一套数字，因此结果一律按估算对待，
 * 面板与报告里都带「≈」。真正的官方数字是余额差（见 {@code ChannelCost} 的余额字段），
 * 拿它来校验这里的估算。</p>
 *
 * <p>纯函数、不依赖运行时：费率匹配与算钱都能逐条单测钉住——算错的后果是站长对成本
 * 产生错误判断，而不是报错。</p>
 */
public final class DigestAiPricing {

    /** 计价基准：价格表按「每百万 token」填写 */
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /**
     * 内置默认单价：DeepSeek 官方价目表（人民币，每百万 token 的<b>高峰价</b>）。
     *
     * <p>数值抄自 {@code https://api-docs.deepseek.com/zh-cn/quick_start/pricing}
     * （2026-09-28 核对）。有了它，站长<b>不需要</b>手工填任何价格就能看到花费估算；
     * 官网改价时也不必等插件发版——面板上的「从官网刷新单价」会直接读那份文档，
     * 或让 {@code priceAutoRefreshEnabled} 在价格表为空时自动填一次。</p>
     */
    public static final List<DigestAiPriceDTO> DEFAULT_PRICES = List.of(
        DigestAiPriceDTO.of("deepseek-flash", "CNY", "0.02", "1", "4"),
        DigestAiPriceDTO.of("deepseek-v4-pro", "CNY", "0.15", "4.5", "13.5"));

    /** 内置表核对日期（面板展示「内置价目表（核对于 …）」，让站长知道该不该刷新） */
    public static final String DEFAULT_PRICES_DATE = "2026-09-28";

    /**
     * 官方价目表地址：刷新按钮与帮助文案共用同一份来源。
     *
     * <p>结尾的斜杠是必须的——少了它站点会 302 跳到带斜杠的地址（曾经因此报「HTTP 302」）。</p>
     */
    public static final String OFFICIAL_PRICING_URL =
        "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/";

    private DigestAiPricing() {
    }

    /** 价格表：配置里有就用配置的，没有就用内置默认（配置为空白时不算「接管」，避免升级后突然没价） */
    public static List<DigestAiPriceDTO> priceTable(DigestConfigDTO config) {
        var configured = config == null ? null : config.getAiPrices();
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_PRICES;
        }
        return configured;
    }

    /**
     * 匹配单价：先精确匹配模型名，再按最长的子串匹配。
     *
     * <p>「最长优先」保证 {@code deepseek-flash} 不会被更短的 {@code deepseek} 抢走；
     * 而写一条 {@code deepseek} 就能覆盖同族的新模型名，改价只改一处。</p>
     */
    public static Optional<DigestAiPriceDTO> match(DigestConfigDTO config, String model) {
        if (!StringUtils.hasText(model)) {
            return Optional.empty();
        }
        var normalized = model.trim().toLowerCase(Locale.ROOT);
        var table = priceTable(config);
        DigestAiPriceDTO best = null;
        for (var price : table) {
            if (price == null || !StringUtils.hasText(price.model()) || !price.usable()) {
                continue;
            }
            var pattern = price.model().trim().toLowerCase(Locale.ROOT);
            if (pattern.equals(normalized)) {
                return Optional.of(price);
            }
            if (normalized.contains(pattern)
                && (best == null || pattern.length() > best.model().trim().length())) {
                best = price;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 估算一次调用的费用。
     *
     * <p>返回空表示「算不出来」，有两种情况都要与「0 元」区分开：
     * 对方没上报 usage，或价格表里没有这个模型。0 元会被站长理解成「免费」。</p>
     */
    public static Optional<BigDecimal> estimate(DigestConfigDTO config, String model,
        DigestAiUsageDTO usage) {
        return estimate(config, model, usage, java.time.Instant.now());
    }

    /**
     * 估算一次调用的费用（含时段）。
     *
     * <p>必须带上调用发生的时刻：DeepSeek 的高峰与空闲价差一倍，而日报常见的执行时刻
     * （早上 7 点、下午）正好跨在两个时段上，不看时间就会差一倍。时刻由调用方给
     * （账本在记账那一刻取），而不是在这里取当前时间——不然补记、重跑都算不准。</p>
     */
    public static Optional<BigDecimal> estimate(DigestConfigDTO config, String model,
        DigestAiUsageDTO usage, java.time.Instant at) {
        if (usage == null || usage.isEmpty()) {
            return Optional.empty();
        }
        var price = match(config, model).orElse(null);
        if (price == null) {
            return Optional.empty();
        }
        var peak = DigestAiPeakWindow.isPeak(at);
        var hitPrice = price.priceAt(price.cacheHit(), peak);
        var missPrice = price.priceAt(price.cacheMiss(), peak);
        var outputPrice = price.priceAt(price.output(), peak);
        var total = BigDecimal.ZERO;
        if (usage.hasCacheBreakdown()) {
            total = total
                .add(cost(usage.cacheHitTokens(), hitPrice))
                .add(cost(usage.cacheMissTokens(), missPrice));
            // 有的服务给了明细但 prompt_tokens 里还包含未在明细中体现的部分，按未命中价补差
            var extra = usage.inputTokens() - usage.cacheHitTokens() - usage.cacheMissTokens();
            if (extra > 0) {
                total = total.add(cost(extra, missPrice));
            }
        } else {
            total = total.add(cost(usage.inputTokens(), missPrice));
        }
        total = total.add(cost(usage.completionTokens(), outputPrice));
        return Optional.of(total);
    }

    private static BigDecimal cost(int tokens, BigDecimal unitPrice) {
        if (tokens <= 0 || unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(tokens))
            .divide(PER_MILLION, MathContext.DECIMAL64);
    }

    /** 价格表的可读描述（设置页 help 与文档里用同一份口径） */
    public static String describeDefaults() {
        var parts = new ArrayList<String>();
        for (var price : DEFAULT_PRICES) {
            parts.add(price.model() + "：" + price.describe());
        }
        return String.join("；", parts);
    }

    /**
     * 合并官网读到的价格表：同名（匹配串相同）覆盖，其它条目原样保留。
     *
     * <p>保留是关键：站长的价格表里可能还有 OpenAI、通义千问等条目，
     * 刷新 DeepSeek 的价格不该把它们清掉。</p>
     */
    public static List<DigestAiPriceDTO> mergeFetched(List<DigestAiPriceDTO> existing,
        List<DigestAiPriceDTO> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            return existing == null ? List.of() : existing;
        }
        var result = new ArrayList<DigestAiPriceDTO>();
        var replaced = new java.util.HashSet<String>();
        for (var fetchedPrice : fetched) {
            replaced.add(fetchedPrice.model());
        }
        if (existing != null) {
            for (var price : existing) {
                if (!replaced.contains(price.model())) {
                    result.add(price);
                }
            }
        }
        result.addAll(fetched);
        return List.copyOf(result);
    }

    /** 金额相加（累计用），保留足够精度，展示时再截 */
    public static BigDecimal add(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    /** 币种归一：空值按 USD 处理会让人民币站点看错符号，所以留空时返回「未知」 */
    public static String currencyOf(DigestAiPriceDTO price) {
        if (price == null || !StringUtils.hasText(price.currency())) {
            return "未知";
        }
        return price.currency().trim().toUpperCase(Locale.ROOT);
    }

    /** 四舍五入到 6 位：内部累计用，避免长尾小数在求和时扩散 */
    public static BigDecimal round(BigDecimal amount) {
        return amount == null ? null : amount.setScale(6, RoundingMode.HALF_UP);
    }
}
