package pro.uhalo.uni.utils;

import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiCostDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestAiUsageDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮运行的 AI 用量账本：把每次调用的 token 与通道记下来，收尾时算成钱。
 *
 * <p>每轮运行<b>各自一个实例</b>（在流水线开始时创建、往下传），刻意不做成单例里的计数器：
 * 客户端是共享的，付费简报与旧文归类也会调用它，挂在单例上的累计会把别人的调用算进日报账上，
 * 甚至把已累计的清零。</p>
 *
 * <p>线程安全：日报的评分与成稿批次是并发 2 跑的（{@code flatMapSequential}），
 * 多个批次会同时记账。</p>
 */
public final class DigestAiUsageLog {

    /** 余额查询最多覆盖几条通道：再多不会有额外信息，只是多几次 HTTP */
    public static final int MAX_BALANCE_CHANNELS = 5;

    private final DigestConfigDTO config;

    /** 按任务汇总 */
    private final Map<DigestAiTask, Bucket> byTask = new ConcurrentHashMap<>();

    /** 按通道汇总 */
    private final Map<String, Bucket> byChannel = new ConcurrentHashMap<>();

    /** 通道标识 → 首次出现时的返回信息（显示名、端点、模型名） */
    private final Map<String, DigestAiReplyDTO> channelMeta = new ConcurrentHashMap<>();

    /** 运行前后的余额，key = 通道标识 */
    private final Map<String, DigestAiBalanceDTO> balanceBefore = new ConcurrentHashMap<>();
    private final Map<String, DigestAiBalanceDTO> balanceAfter = new ConcurrentHashMap<>();

    private DigestAiUsageLog(DigestConfigDTO config) {
        this.config = config;
    }

    public static DigestAiUsageLog forConfig(DigestConfigDTO config) {
        return new DigestAiUsageLog(config);
    }

    /**
     * 记一次调用（任务 + 通道 + 用量），按「现在」作为计费时刻。
     *
     * <p>要按指定时刻计价（测试、补记）请用三参重载。</p>
     */
    public void record(DigestAiTask task, DigestAiReplyDTO reply) {
        record(task, reply, Instant.now());
    }

    /**
     * 记一次调用，并按<b>调用发生的时刻</b>计价。
     *
     * <p>必须逐次计价：DeepSeek 的高峰价与空闲价差一倍，而日报可能横跨时段
     * （比如 08:5x 开跑、跑到 09:0x），收尾时再统一算会把两侧都算错。</p>
     */
    public void record(DigestAiTask task, DigestAiReplyDTO reply, Instant at) {
        if (task == null || reply == null) {
            return;
        }
        byTask.computeIfAbsent(task, key -> new Bucket()).add(reply, at, config);
        if (reply.channelKey() != null) {
            byChannel.computeIfAbsent(reply.channelKey(), key -> new Bucket())
                .add(reply, at, config);
            channelMeta.putIfAbsent(reply.channelKey(), reply);
        }
    }

    /** 运行<b>前</b>的余额：必须发生在第一次调用之前，所以与 after 分开记 */
    public void rememberBalanceBefore(DigestAiBalanceDTO balance) {
        if (balance != null && balance.key() != null) {
            balanceBefore.put(balance.key(), balance);
        }
    }

    public void rememberBalanceAfter(DigestAiBalanceDTO balance) {
        if (balance != null && balance.key() != null) {
            balanceAfter.put(balance.key(), balance);
        }
    }

    /** 本轮实际用到的通道标识（余额只查这些，按显示名排序保证输出稳定） */
    public List<String> usedChannelKeys() {
        var keys = new ArrayList<>(byChannel.keySet());
        keys.sort((left, right) -> labelOf(left).compareTo(labelOf(right)));
        return List.copyOf(keys);
    }

    /** 本轮是否发生过调用（没调用就不写花费块，省得报告里多一块空数据） */
    public boolean isEmpty() {
        return byTask.values().stream().noneMatch(bucket -> bucket.calls() > 0);
    }

    /** 收尾：算出花费块（按任务 / 按通道的估算金额 + 余额前后） */
    public DigestAiCostDTO snapshot() {
        var tasks = new ArrayList<DigestAiCostDTO.TaskCost>();
        var channels = new ArrayList<DigestAiCostDTO.ChannelCost>();
        var totals = new LinkedHashMap<String, BigDecimal>();
        var calls = 0;
        var unpriced = 0;
        var unknownUsage = 0;

        for (var task : DigestAiTask.values()) {
            var bucket = byTask.get(task);
            if (bucket == null || bucket.calls() == 0) {
                continue;
            }
            var usage = bucket.usage();
            var amount = bucket.amount();
            var currency = firstNonEmpty(bucket.currency(), currencyFor(bucket.model()));
            calls += bucket.calls();
            unknownUsage += bucket.unknownCalls();
            // 「未上报用量」与「没配单价」是两件事，分开数才不会误导站长去改价格表
            unpriced += bucket.unpricedCalls();
            if (amount != null) {
                accumulate(totals, currency, amount);
            }
            tasks.add(DigestAiCostDTO.TaskCost.builder()
                .task(task.getKey())
                .label(task.getLabel())
                .model(bucket.model())
                .calls(bucket.calls())
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens())
                .amount(amount == null ? null : DigestAiCostDTO.formatAmount(amount))
                .currency(currency)
                .priced(amount != null)
                .build());
        }

        for (var key : usedChannelKeys()) {
            var bucket = byChannel.get(key);
            var meta = channelMeta.get(key);
            var model = bucket == null ? "" : bucket.model();
            var usage = bucket == null ? DigestAiUsageDTO.unreported() : bucket.usage();
            var amount = bucket == null ? null : bucket.amount();
            var before = balanceBefore.get(key);
            var after = balanceAfter.get(key);
            channels.add(DigestAiCostDTO.ChannelCost.builder()
                .key(key)
                .label(labelOf(key))
                .apiBase(meta == null ? "" : meta.apiBase())
                .model(model)
                .calls(bucket == null ? 0 : bucket.calls())
                .totalTokens(usage.totalTokens())
                .amount(amount == null ? null : DigestAiCostDTO.formatAmount(amount))
                .currency(bucket == null ? currencyFor(model)
                    : firstNonEmpty(bucket.currency(), currencyFor(model)))
                .priced(amount != null)
                .balanceCurrency(balanceCurrency(before, after))
                .balanceBefore(before == null ? null : before.totalBalance())
                .balanceAfter(after == null ? null : after.totalBalance())
                .balanceDelta(delta(before, after))
                .balanceNote(balanceNote(before, after))
                .build());
        }

        var money = new ArrayList<DigestAiCostDTO.Money>();
        totals.forEach((currency, amount) -> money.add(DigestAiCostDTO.Money.builder()
            .currency(currency)
            .amount(DigestAiCostDTO.formatAmount(amount))
            .build()));

        return DigestAiCostDTO.builder()
            .tasks(List.copyOf(tasks))
            .channels(List.copyOf(channels))
            .totals(List.copyOf(money))
            .calls(calls)
            .unpricedCalls(unpriced)
            .unknownUsageCalls(unknownUsage)
            .note(describe(calls, unpriced, unknownUsage))
            .build();
    }

    private String labelOf(String channelKey) {
        var meta = channelMeta.get(channelKey);
        return meta == null || meta.channelLabel() == null ? String.valueOf(channelKey)
            : meta.channelLabel();
    }

    private static String firstNonEmpty(String left, String right) {
        return left == null || left.isEmpty() ? right : left;
    }

    private String currencyFor(String model) {
        return DigestAiPricing.match(config, model).map(DigestAiPricing::currencyOf).orElse("");
    }

    /** 按币种累计：不做汇率换算，混合币种就并列显示 */
    private static void accumulate(Map<String, BigDecimal> totals, String currency,
        BigDecimal amount) {
        totals.merge(currency, amount, BigDecimal::add);
    }

    /** 口径说明：既讲清估算的边界，也告诉站长缺失的那部分去哪补 */
    private static String describe(int calls, int unpriced, int unknownUsage) {
        var builder = new StringBuilder("费用为估算：按设置里的模型单价 × 实际 token。"
            + "DeepSeek 官方不提供用量/账单接口，账户实际扣减请对照下面的余额差。");
        if (unpriced > 0) {
            builder.append(" 有 ").append(unpriced)
                .append(" 次调用没匹配到单价，可在设置「AI 日报 → 模型单价」里补上。");
        }
        if (unknownUsage > 0) {
            builder.append(" 有 ").append(unknownUsage).append(" 次调用对方未上报用量，只能计次数。");
        }
        if (calls > 0 && unpriced == 0 && unknownUsage == 0) {
            builder.append(" 本轮 ").append(calls).append(" 次调用都拿到了用量与单价。");
        }
        return builder.toString();
    }

    /** 余额差：只有前后都查到才算得出来 */
    private static String delta(DigestAiBalanceDTO before, DigestAiBalanceDTO after) {
        var left = decimal(before == null ? null : before.totalBalance());
        var right = decimal(after == null ? null : after.totalBalance());
        if (left == null || right == null) {
            return null;
        }
        return DigestAiCostDTO.formatAmount(left.subtract(right));
    }

    private static String balanceCurrency(DigestAiBalanceDTO before, DigestAiBalanceDTO after) {
        if (after != null && after.currency() != null) {
            return after.currency();
        }
        return before == null ? null : before.currency();
    }

    /** 前后任一次失败都要说明原因：否则「实际扣减」空着会让人以为功能坏了 */
    private static String balanceNote(DigestAiBalanceDTO before, DigestAiBalanceDTO after) {
        if (before == null && after == null) {
            return null;
        }
        if (before != null && !before.ok()) {
            return after != null && !after.ok() ? after.message() : before.message();
        }
        if (after != null && !after.ok()) {
            return after.message();
        }
        return null;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            // 余额不是数字时按「查不到」处理：宁可不显示差额，也不显示一个错数字
            return null;
        }
    }

    /**
     * 一个维度的累计：次数 + 用量 + 金额。
     *
     * <p>金额在这里<b>逐次</b>累加，而不是收尾时用累计 token 乘一次单价：
     * 单价随时段变化，逐次算才能把跨时段的运行算准。</p>
     */
    private static final class Bucket {

        private int calls;
        /** 其中对方未上报用量的次数：合并后的 usage 分不出这一点，必须单独数 */
        private int unknownCalls;
        /** 其中「有用量但价格表里没有单价」的次数 */
        private int unpricedCalls;
        private DigestAiUsageDTO usage = DigestAiUsageDTO.unreported();
        private BigDecimal amount = BigDecimal.ZERO;
        private boolean hasAmount;
        /** 该维度主要用的模型名：同一任务正常只走一条通道，多通道时取第一次的那个 */
        private volatile String model = "";
        private volatile String currency = "";

        synchronized void add(DigestAiReplyDTO reply, Instant at, DigestConfigDTO config) {
            var delta = reply.usage();
            calls++;
            if (delta == null || delta.isEmpty()) {
                unknownCalls++;
            }
            usage = usage.plus(delta);
            if ((this.model == null || this.model.isEmpty()) && reply.model() != null
                && !reply.model().isBlank()) {
                this.model = reply.model();
            }
            var price = DigestAiPricing.match(config, reply.model()).orElse(null);
            if (price != null && (this.currency == null || this.currency.isEmpty())) {
                this.currency = DigestAiPricing.currencyOf(price);
            }
            var estimated = DigestAiPricing.estimate(config, reply.model(), delta, at);
            if (estimated.isPresent()) {
                amount = amount.add(estimated.get());
                hasAmount = true;
            } else if (delta != null && !delta.isEmpty()) {
                unpricedCalls++;
            }
        }

        synchronized int calls() {
            return calls;
        }

        synchronized int unknownCalls() {
            return unknownCalls;
        }

        synchronized int unpricedCalls() {
            return unpricedCalls;
        }

        /** 是否算得出金额：算不出与 0 元必须区分（0 会被读成免费） */
        synchronized boolean hasAmount() {
            return hasAmount;
        }

        synchronized BigDecimal amount() {
            return hasAmount ? amount : null;
        }

        synchronized DigestAiUsageDTO usage() {
            return usage;
        }

        String model() {
            return model;
        }

        String currency() {
            return currency;
        }
    }

    /** 供调试与测试：本轮出现过的任务标识 */
    public List<String> tasksSeen() {
        var result = new ArrayList<String>();
        for (var task : DigestAiTask.values()) {
            var bucket = byTask.get(task);
            if (bucket != null && bucket.calls() > 0) {
                result.add(task.getKey());
            }
        }
        return List.copyOf(result);
    }

    /** 某个任务的估算金额（测试与面板复用，避免两处各算一遍） */
    public Optional<BigDecimal> amountOf(DigestAiTask task) {
        var bucket = byTask.get(task);
        return bucket == null ? Optional.empty() : Optional.ofNullable(bucket.amount());
    }
}
