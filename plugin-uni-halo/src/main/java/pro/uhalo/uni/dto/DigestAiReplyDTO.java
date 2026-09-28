package pro.uhalo.uni.dto;

/**
 * 一次大模型调用的返回值：正文 + 用量 + 这次实际走的通道。
 *
 * <p>为什么把用量放在返回值里，而不是让客户端自己在单例上记一笔：客户端是共享的，
 * 「付费简报」在用户打开时、旧文归类在站长点预览时都会调用它。若把累计挂在单例上，
 * 日报跑到一半被别人插进来的调用会算进日报账上（甚至会清掉已累计的），
 * 报告里的钱就不是这一轮花的钱。返回值是显式的：谁调用谁拿到自己那次的花费，也能单测。</p>
 *
 * <p>刻意不带 apiKey：这个对象会一路传进报告与日志，密钥不该跟着跑。</p>
 *
 * @param text         模型返回的正文
 * @param usage        token 用量（对方未上报时 {@code reported=false}）
 * @param channelKey   实际生效通道的标识（用于按通道汇总与回查余额）
 * @param channelLabel 通道显示名（报告与日志里给人看）
 * @param apiBase      通道端点（余额查询要用它推根地址）
 * @param model        本次实际发出的模型名
 */
public record DigestAiReplyDTO(String text, DigestAiUsageDTO usage, String channelKey,
                              String channelLabel, String apiBase, String model) {

    public static DigestAiReplyDTO of(String text, DigestAiProviderDTO channel, String model,
        DigestAiUsageDTO usage) {
        return new DigestAiReplyDTO(text, usage == null ? DigestAiUsageDTO.unreported() : usage,
            channel.key(), channel.label(), channel.apiBase(),
            model == null || model.isBlank() ? channel.model() : model);
    }
}
