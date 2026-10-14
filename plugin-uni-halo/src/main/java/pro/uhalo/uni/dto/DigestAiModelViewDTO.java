package pro.uhalo.uni.dto;

import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiPeakWindow;
import pro.uhalo.uni.utils.DigestAiPricing;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台「大模型通道」面板的视图数据：清单 + 每一条的结论 + 当前生效的通道。
 *
 * <p>Key 一律以掩码下发，页面拿不到明文；保存时把掩码原样传回来即表示「Key 没改」。</p>
 *
 * <p>{@code channels} 与 {@code providers} 的区别值得注意：前者是<b>实际会被调用的通道</b>
 * （清单为空时包含由旧字段生成的两条兼容通道），后者只是清单本身。
 * 面板顶部展示「当前生效」用的是前者，否则站长会以为「清单是空的所以没在用」。</p>
 *
 * @param fromList 站长是否接管了通道配置（false = 清单留空，正在用旧的主 / 兜底通道）
 * @param summary 顶部一句话结论
 * @param providers 清单（掩码后），顺序即故障转移顺序
 * @param channels 实际会被调用的通道（含被关掉 / 缺 Key 的，便于面板标出来）
 * @param active 当前生效通道的标识（取不到时为 null）
 * @param tasks 每个任务最终会用哪条通道（含「没有通道勾选它，已回退」的结论）
 * @param prices 当前生效的单价表与来源（内置 / 官网 / 站长手填），面板只读展示
 * @param legacy 旧的主 / 兜底字段现状，供「从兼容通道导入」使用
 * @param hints 需要站长注意的短句（面板按条列出）
 */
public record DigestAiModelViewDTO(
    boolean fromList,
    String summary,
    List<Row> providers,
    List<Row> channels,
    String active,
    List<TaskLine> tasks,
    Prices prices,
    Legacy legacy,
    List<String> hints) {

    /**
     * 「任务 → 生效通道」表的一行。
     *
     * <p>这张表存在的理由：勾选框只表达「这条通道愿意干什么」，而站长真正想知道的是
     * 「评分现在到底跑在哪个模型上」。让他自己在脑子里做一次匹配，迟早会出现
     * 「以为评分走的是便宜通道」这种误解。</p>
     *
     * @param key 任务标识
     * @param label 任务中文名
     * @param channel 生效通道的显示名（没有可用通道时为空）
     * @param model 生效的模型名
     * @param fallback true = 没有通道勾选该任务，已回退到全部可用通道
     * @param usable 该任务当前有没有通道可用
     * @param note 一句话结论（回退与失败原因都写在里面）
     */
    public record TaskLine(
        String key,
        String label,
        String channel,
        String model,
        boolean fallback,
        boolean usable,
        String note) {

        static TaskLine of(DigestAiChannelResolver.TaskPlan plan) {
            return new TaskLine(plan.task().getKey(), plan.task().getLabel(), plan.activeLabel(),
                plan.activeModel(), plan.fallback(), plan.usable(), plan.note());
        }
    }

    /**
     * 面板里的一行。
     *
     * @param key 通道标识（草稿行为空）
     * @param label 显示名
     * @param apiBase 端点
     * @param model 实际会发出的模型名（留空时已按端点推断好）
     * @param enabled 开关
     * @param hasKey 是否配了 Key
     * @param apiKeyMasked 掩码后的 Key
     * @param usable 会不会被真正调用
     * @param source {@code list} = 来自清单，{@code legacy} = 来自旧字段
     * @param tasks 承担的任务标识；空 = 全部任务
     * @param message 这一行的结论
     */
    public record Row(
        String key,
        String label,
        String apiBase,
        String model,
        boolean enabled,
        boolean hasKey,
        String apiKeyMasked,
        boolean usable,
        String source,
        List<String> tasks,
        String message) {

        public static Row of(DigestAiProviderDTO provider, String source, int index) {
            var enabled = provider.enabled();
            var hasKey = provider.hasKey();
            var hasBase = !provider.apiBase().isEmpty();
            return new Row(provider.key(), DigestAiChannelResolver.labelOf(provider, index),
                provider.apiBase(), provider.model(), enabled, hasKey, provider.maskedApiKey(),
                provider.usable(), source, provider.taskList(),
                describe(enabled, hasBase, hasKey, provider.taskList()));
        }

        private static String describe(boolean enabled, boolean hasBase, boolean hasKey,
            List<String> tasks) {
            if (!enabled) {
                return "已关闭：不参与调用，也不会被用作备用通道";
            }
            if (!hasBase) {
                return "缺少 API Base，不会被调用";
            }
            if (!hasKey) {
                return "缺少 API Key，不会被调用";
            }
            return "可用：按清单顺序参与调用与故障转移";
        }
    }

    /**
     * 当前生效的单价表。
     *
     * <p>单价不要求站长手填：内置表来自官方价目表，另外可以从官网直接读取。
     * 面板把它展示出来是为了「一眼能核对我这笔账是怎么算的」。</p>
     *
     * @param source 来源说明（内置价目表 / 官网读取 / 站长填写）
     * @param fetchedAt 从官网读到的时间（为空表示还在用内置表）
     * @param officialUrl DeepSeek 官方价目表地址
     * @param zhipuUrl 智谱官方价目表地址（GLM 的单价来源）
     * @param peakRule 高峰/空闲时段规则（一句话）
     * @param billingRules 官方计费口径（这钱按什么规则算、什么情况会偏差）
     * @param items 每一行：模型匹配串、三个高峰价、空闲折扣、币种、说明
     */
    public record Prices(
        String source,
        String fetchedAt,
        String officialUrl,
        String zhipuUrl,
        String peakRule,
        String billingRules,
        List<PriceRow> items) {
    }

    public record PriceRow(
        String model,
        String currency,
        String cacheHit,
        String cacheMiss,
        String output,
        String offPeakRatio,
        String description) {
    }

    /**
     * 旧字段现状（掩码后）。
     *
     * <p>清单非空时这些字段不生效，但页面上仍要能看见它们——站长点了「导入」之后
     * 才能确定自己没把哪把 Key 落在外头。</p>
     */
    public record Legacy(
        String apiBase,
        String model,
        boolean hasKey,
        String apiKeyMasked,
        String fallbackApiBase,
        String fallbackModel,
        boolean hasFallbackKey,
        String fallbackApiKeyMasked,
        boolean configured) {

        static Legacy of(DigestConfigDTO config) {
            var key = config.getAiApiKey() == null ? "" : config.getAiApiKey().trim();
            var fallbackKey = config.getFallbackApiKey() == null
                ? "" : config.getFallbackApiKey().trim();
            var primary = new DigestAiProviderDTO("legacy-primary", "兼容通道（主）",
                config.getAiApiBase(), key, config.getAiModel());
            var fallback = new DigestAiProviderDTO("legacy-fallback", "兼容通道（兜底）",
                config.getFallbackApiBase(), fallbackKey, config.getFallbackModel());
            return new Legacy(
                DigestAiChannelResolver.normalizeBase(config.getAiApiBase()),
                config.getAiModel(),
                !key.isEmpty(),
                primary.maskedApiKey(),
                DigestAiChannelResolver.normalizeBase(config.getFallbackApiBase()),
                config.getFallbackModel(),
                !fallbackKey.isEmpty(),
                fallback.maskedApiKey(),
                !key.isEmpty() || !fallbackKey.isEmpty());
        }
    }

    /** 按配置算出面板要展示的一切（纯函数，便于单测钉住结论文案） */
    public static DigestAiModelViewDTO of(DigestConfigDTO config) {
        var fromList = DigestAiChannelResolver.fromList(config);
        var channels = DigestAiChannelResolver.channels(config);
        var rows = new ArrayList<Row>();
        for (var index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            rows.add(Row.of(channel, fromList ? "list" : "legacy", index + 1));
        }
        var providers = new ArrayList<Row>();
        var sourceProviders = config == null || config.getAiProviders() == null
            ? List.<DigestAiProviderDTO>of() : config.getAiProviders();
        for (var index = 0; index < sourceProviders.size(); index++) {
            providers.add(Row.of(sourceProviders.get(index), "list", index + 1));
        }
        var active = DigestAiChannelResolver.activeChannel(config).map(DigestAiProviderDTO::key)
            .orElse(null);
        var taskLines = new ArrayList<TaskLine>();
        for (var task : DigestAiTask.values()) {
            taskLines.add(TaskLine.of(DigestAiChannelResolver.plan(config, task)));
        }
        return new DigestAiModelViewDTO(fromList, DigestAiChannelResolver.describe(config),
            List.copyOf(providers), List.copyOf(rows), active, List.copyOf(taskLines),
            pricesOf(config), Legacy.of(config), hints(config, fromList, channels));
    }

    /** 单价表（含来源）：面板上只读展示，站长不必手填任何价格 */
    private static Prices pricesOf(DigestConfigDTO config) {
        var fetchedAt = config == null ? "" : config.getAiPriceFetchedAt();
        var fromOfficial = fetchedAt != null && !fetchedAt.isBlank();
        var items = new java.util.ArrayList<PriceRow>();
        for (var price : DigestAiPricing.priceTable(config)) {
            items.add(new PriceRow(price.model(), price.currency(),
                plain(price.cacheHit()), plain(price.cacheMiss()), plain(price.output()),
                price.offPeakRatio() == null ? "0.5"
                    : price.offPeakRatio().stripTrailingZeros().toPlainString(),
                price.describe()));
        }
        var source = fromOfficial ? "官网读取" : "内置价目表（" + DigestAiPricing.DEFAULT_PRICES_DATE + " 核对）";
        return new Prices(source, fetchedAt, DigestAiPricing.OFFICIAL_PRICING_URL,
            DigestAiPricing.ZHIPU_PRICING_URL, DigestAiPeakWindow.describe(),
            DigestAiPricing.describeBillingRules(), List.copyOf(items));
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static List<String> hints(DigestConfigDTO config, boolean fromList,
        List<DigestAiProviderDTO> channels) {
        var hints = new ArrayList<String>();
        if (!fromList) {
            hints.add("通道清单为空：现在走的是旧的主 / 兜底通道，行为与升级前一致。"
                + "想在页面里管理（开关、排序、逐条测试），点「导入现有通道」再保存即可。");
        }
        if (channels.isEmpty()) {
            hints.add("还没有任何可用通道，AI 日报与旧文章归类都会直接报错。");
        }
        var disabled = channels.stream().filter(channel -> !channel.enabled()).count();
        if (disabled > 0) {
            hints.add("有 " + disabled + " 条通道被关闭，它们不参与调用。");
        }
        var keyless = channels.stream()
            .filter(channel -> channel.enabled() && !channel.hasKey()).count();
        if (keyless > 0) {
            hints.add("有 " + keyless + " 条通道开着但没填 API Key，不会被调用。");
        }
        // 花费算不出来最常见的原因就是这一条：通道用的模型名不在单价表里。
        // 不提示的话，站长只会看到报告里「未配置单价」而不知道缺的是哪个模型。
        var unpricedModels = new java.util.LinkedHashSet<String>();
        for (var channel : channels) {
            if (!channel.usable()) {
                continue;
            }
            if (DigestAiPricing.match(config, channel.model()).isEmpty()) {
                unpricedModels.add(channel.model() + "（通道「" + channel.label() + "」）");
            }
        }
        if (!unpricedModels.isEmpty()) {
            var table = new java.util.ArrayList<String>();
            for (var price : DigestAiPricing.priceTable(config)) {
                table.add(price.model());
            }
            hints.add("花费算不出来：这些模型不在单价表里 —— " + String.join("、", unpricedModels)
                + "；单价表里只有 " + String.join("、", table)
                + "。可以点「从官网刷新单价」，或在设置「AI 日报 → 模型单价」里补一条"
                + "（匹配串写 deepseek 即可覆盖同族模型）。");
        }
        var tagged = channels.stream().filter(channel -> !channel.taskList().isEmpty()).count();
        if (!channels.isEmpty() && tagged == 0) {
            hints.add("所有通道都没勾用途（等于都能干所有活）：想让便宜通道只做评分、"
                + "强通道只做成稿，就按用途打勾。");
        }
        var untagged = channels.stream().filter(channel -> channel.taskList().isEmpty()).count();
        if (tagged > 0 && untagged > 0) {
            hints.add("有 " + untagged + " 条通道没勾用途，它们会承担全部任务"
                + "（包括成稿），排在前面的会先把成稿接走。");
        }
        return List.copyOf(hints);
    }
}
