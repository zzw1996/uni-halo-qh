package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 推送自检的一项结论。
 *
 * <p>{@link #message} 在失败时带上<b>原始错误消息</b>：推送失败几乎都是环境问题，
 * 「哪一步坏了、原话是什么」比一句「请稍后重试」有用得多。</p>
 */
@Data
@Builder
public class PushDiagnoseItemDTO {

    /** 检查项：配置 / 读取订阅记录 / 写入订阅记录 / 读取调度状态 / 微信令牌 */
    private String step;

    private boolean ok;

    private String message;
}
