package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一轮 AI 日报的花费块（写进运行记录的 stats，也是后台详情里那张表的直接数据）。
 *
 * <p>两个来源刻意都留着，因为它们的可信度不一样：</p>
 * <ul>
 *   <li>{@link #tasks} / {@link #totals}：本地按 token × 单价<b>估算</b>，能拆到「评分花了多少、
 *       成稿花了多少」，这种拆解只有本地算得出来；</li>
 *   <li>{@link ChannelCost#balanceBefore()} 等：官方余额接口的前后差值，是<b>账号级实际扣减</b>，
 *       能反过来校验估算准不准，但它会把同账号下别的调用也一起算进来。</li>
 * </ul>
 *
 * <p>金额用字符串下发（如 {@code "0.4213"}）：前端直接显示，也免得浮点序列化出科学计数法。</p>
 *
 * @param tasks             按任务汇总（评分 / 成稿 / 看点）
 * @param channels          按通道汇总（含余额前后）
 * @param totals            按币种汇总（不做汇率换算，混合币种就并列显示）
 * @param calls             本轮 AI 调用总次数
 * @param unpricedCalls     有 token 但价格表里找不到单价的调用次数
 * @param unknownUsageCalls 对方未上报 usage 的次数
 * @param note              口径说明（展示在详情里）
 */
@Data
@Builder
public class DigestAiCostDTO {

    /** 有用量但算不出钱时的列文案 */
    public static final String UNPRICED_TEXT = "未配置单价";

    private List<TaskCost> tasks;
    private List<ChannelCost> channels;
    private List<Money> totals;
    private int calls;
    private int unpricedCalls;
    private int unknownUsageCalls;
    private String note;

    /** 按任务的一行：这是「按任务绑定模型」带来的最有用的一个视角 */
    @Data
    @Builder
    public static class TaskCost {
        private String task;
        private String label;
        /** 本次实际用的模型名：缺价时界面要能说出是哪个模型没配价 */
        private String model;
        private int calls;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        /** 估算金额；未配单价时为 null */
        private String amount;
        private String currency;
        /** 该任务的调用是否全部都有用量与单价 */
        private boolean priced;
    }

    /** 按通道的一行：顺手承载官方余额的前后对比 */
    @Data
    @Builder
    public static class ChannelCost {
        private String key;
        private String label;
        private String apiBase;
        private String model;
        private int calls;
        private int totalTokens;
        private String amount;
        private String currency;
        private boolean priced;
        private String balanceCurrency;
        private String balanceBefore;
        private String balanceAfter;
        private String balanceDelta;
        private String balanceNote;
    }

    /** 一个币种一个数字 */
    @Data
    @Builder
    public static class Money {
        private String currency;
        private String amount;
    }

    /** 有没有可展示的内容：老报告里没有这个字段，前端据此显示「未采集」而不是 0 */
    public boolean isEmpty() {
        return calls <= 0;
    }

    /**
     * 合计的中文短语（列表列与面板一行显示用）。
     *
     * <p>三种状态必须能分开，否则站长只看到一片空白，不知道是「没采集到」还是「算不出来」：</p>
     * <ul>
     *   <li>有金额 → {@code ≈¥0.42}</li>
     *   <li>记到了调用但价格表里没有这个模型 → {@code 未配置单价}（提示去补价，而不是显示空白）</li>
     *   <li>压根没采集（1.0.165 之前的记录）→ 空串，界面显示「-」</li>
     * </ul>
     */
    public static String summaryText(DigestAiCostDTO cost) {
        if (cost == null) {
            return "";
        }
        if (cost.getTotals() == null || cost.getTotals().isEmpty()) {
            return cost.getCalls() > 0 ? UNPRICED_TEXT : "";
        }
        var builder = new StringBuilder("≈");
        var first = true;
        for (var money : cost.getTotals()) {
            if (!first) {
                builder.append(" + ");
            }
            builder.append(symbol(money.getCurrency())).append(money.getAmount());
            first = false;
        }
        return builder.toString();
    }

    /** 币种符号：认识的用符号，不认识的带代码前缀，避免 ¥ 与 $ 混在一起看不出来 */
    public static String symbol(String currency) {
        if (currency == null) {
            return "";
        }
        return switch (currency.trim().toUpperCase()) {
            case "CNY", "RMB", "¥" -> "¥";
            case "USD", "$" -> "$";
            default -> currency.trim() + " ";
        };
    }

    /** 金额显示：小额保留 4 位小数（一次评分调用只有几厘钱），再小就写「<0.0001」而不是 0 */
    public static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        var rounded = amount.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0 && amount.signum() > 0) {
            return "<0.0001";
        }
        return rounded.toPlainString();
    }
}
