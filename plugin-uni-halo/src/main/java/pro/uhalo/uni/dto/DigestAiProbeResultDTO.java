package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 「测试连通性」的结果。
 *
 * <p>只发一次极小的请求（8 个输出 token），所以可以放心点——测试的目的是回答
 * 「这条通道能不能用」，而不是跑一次真实任务。</p>
 *
 * <p>{@code message} 是给站长看的结论（Key 无效 / 少了 /v1 / 模型名不存在 …），
 * {@code detail} 是原始响应片段：结论可能因为对方的文案变化而失准，留一份原文才不会卡住。</p>
 */
@Data
@Builder
public class DigestAiProbeResultDTO {

    /** 是否连通（HTTP 2xx 且拿到了正文） */
    private boolean ok;

    /** 被测试通道的标识；草稿行为空 */
    private String key;

    private String label;

    private String apiBase;

    /** 本次实际发出的模型名 */
    private String model;

    /** HTTP 状态码；连接失败时为 null */
    private Integer status;

    /** 往返耗时（毫秒） */
    private Long latencyMs;

    /** 模型返回的正文（只取片段） */
    private String reply;

    /** 服务端回执里的模型名：有些中转会改写模型，回显出来能看出被折算成了什么 */
    private String servedModel;

    /** token 用量（对方返回了才有） */
    private Integer totalTokens;

    /** 结论（人话） */
    private String message;

    /** 原始响应 / 异常片段 */
    private String detail;
}
