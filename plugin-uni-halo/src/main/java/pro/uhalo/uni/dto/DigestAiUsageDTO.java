package pro.uhalo.uni.dto;

/**
 * 一次大模型调用的 token 用量（来自响应体的 {@code usage}）。
 *
 * <p>{@link #reported()} 是关键字段：中转站经常不回 {@code usage}，那种调用只能计「次数」，
 * 不能计钱——直接按 0 token 算成「免费」比不显示更糟，所以由调用方决定怎么标注。</p>
 *
 * <p>缓存命中/未命中单独记：DeepSeek 的命中价与未命中价差几十倍，而日报的评分 prompt
 * 有很长的固定前缀（评分维度说明 + 分类清单），跨批次很容易吃到前缀缓存——
 * 把它们合成一个「输入 token 数」会让费用算错一个数量级。</p>
 *
 * @param promptTokens     输入 token 总数（对方上报多少就记多少）
 * @param completionTokens 输出 token 数（含推理模型的思考 token）
 * @param totalTokens      合计；对方没给时按输入+输出兜底
 * @param cacheHitTokens   命中缓存的输入 token（未上报为 0）
 * @param cacheMissTokens  未命中缓存的输入 token（未上报为 0）
 * @param reasoningTokens  推理模型的思考 token（未上报为 0），已含在 completionTokens 里
 * @param reported         对方是否上报了 usage
 */
public record DigestAiUsageDTO(int promptTokens, int completionTokens, int totalTokens,
                               int cacheHitTokens, int cacheMissTokens, int reasoningTokens,
                               boolean reported) {

    /** 对方没上报 usage（中转站常见） */
    public static DigestAiUsageDTO unreported() {
        return new DigestAiUsageDTO(0, 0, 0, 0, 0, 0, false);
    }

    /**
     * 输入 token：优先用 promptTokens，缺失时用命中 + 未命中拼。
     *
     * <p>有的兼容服务只给缓存明细不给 prompt_tokens，两个都拿不到时才为 0。</p>
     */
    public int inputTokens() {
        if (promptTokens > 0) {
            return promptTokens;
        }
        return cacheHitTokens + cacheMissTokens;
    }

    /** 缓存明细是否可用（用来决定要不要按命中/未命中分别计价） */
    public boolean hasCacheBreakdown() {
        return cacheHitTokens > 0 || cacheMissTokens > 0;
    }

    public boolean isEmpty() {
        return !reported || (inputTokens() + completionTokens) <= 0;
    }

    public DigestAiUsageDTO plus(DigestAiUsageDTO other) {
        if (other == null) {
            return this;
        }
        return new DigestAiUsageDTO(
            promptTokens + other.promptTokens,
            completionTokens + other.completionTokens,
            totalTokens + other.totalTokens,
            cacheHitTokens + other.cacheHitTokens,
            cacheMissTokens + other.cacheMissTokens,
            reasoningTokens + other.reasoningTokens,
            reported || other.reported);
    }
}
