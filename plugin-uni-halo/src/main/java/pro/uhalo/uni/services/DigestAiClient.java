package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiProbeResultDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestAiReplyDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容的对话补全客户端（清单里的多条通道 + 旧的主 / 兜底兼容通道）。
 */
public interface DigestAiClient {

    /**
     * 用一次提示词换取一段模型输出。
     *
     * <p>调用方必须说明<b>这次调用属于哪个任务</b>：通道清单是按任务绑定的
     * （评分可以走便宜模型、成稿留给强模型），任务定了才知道该用哪几条通道。
     * 同一个任务在清单里勾了多条通道时按顺序故障转移，一轮内记住这次降级。</p>
     *
     * <p>故意的破坏性签名：不给这个参数一个默认值，避免新加的调用点悄悄跑到
     * 「评分用的便宜模型」上去写正文，而且这种错只能靠读日志发现。</p>
     *
     * <p>返回值带上了这次调用实际走的通道与 token 用量（{@link DigestAiReplyDTO}）：
     * 费用统计就是从返回值里累加的，不依赖任何共享状态——客户端是单例，
     * 付费简报与旧文归类也会调用它，挂在单例上的计数会把别人的开销算进来。</p>
     */
    Mono<DigestAiReplyDTO> call(DigestConfigDTO config, String prompt, DigestAiTask task);

    /**
     * 同 {@link #call(DigestConfigDTO, String, DigestAiTask)}，但可以指定采样温度。
     *
     * <p>存在的理由只有一个：<b>写稿类任务需要更高的温度</b>。日报/公众号的成稿沿用
     * 0.3 的低温是为了稳定与可复现；而今日头条号是站长自己发布的原创内容，
     * 低温恰恰是「AI 味」的来源之一——句子四平八稳、结构千篇一律。
     * 需要「像人写的」的调用点（写稿与去 AI 味重写）用 0.8~0.9。</p>
     *
     * <p>默认实现忽略温度参数（向后兼容：第三方实现或测试替身不必跟着改），
     * 由 {@code DigestAiClientImpl} 真正生效。</p>
     */
    default Mono<DigestAiReplyDTO> call(DigestConfigDTO config, String prompt, DigestAiTask task,
        double temperature) {
        return call(config, prompt, task);
    }

    /**
     * 查账户余额（DeepSeek 官方的 {@code GET /user/balance}，不消耗 token）。
     *
     * <p>用来给「这一轮花了多少」提供官方的旁证：运行前后各查一次，差值就是账号上
     * 实际的扣减。不支持的通道（非 DeepSeek、或中转站没实现）返回「不支持」结果，
     * <b>不会</b>让运行失败。</p>
     */
    Mono<DigestAiBalanceDTO> queryBalance(DigestAiProviderDTO channel);

    /**
     * 测试一条通道是否配通：只发一次极小的请求（8 个输出 token）。
     *
     * <p>与 {@link #call} 共用同一套请求构造与错误翻译，所以「测试通过」与
     * 「运行时不报错」是同一个口径，返回的结论可以直接给站长看。
     * 连通性与用途无关，因此这里不需要任务参数。</p>
     */
    Mono<DigestAiProbeResultDTO> probe(DigestAiProviderDTO channel);

    /** 每轮流水线开始前重置降级状态（所有任务） */
    void resetForNewRun();
}
