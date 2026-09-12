package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestConfigDTO;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容的对话补全客户端（主通道 + 兜底通道）。
 */
public interface DigestAiClient {

    /**
     * 用一次提示词换取一段模型输出。
     *
     * <p>主通道失败时会自动切到兜底通道，并在同一轮运行内记住这次降级，
     * 避免后续每个批次都先在主通道上失败一次。</p>
     */
    Mono<String> call(DigestConfigDTO config, String prompt);

    /** 每轮流水线开始前重置降级状态 */
    void resetForNewRun();
}
