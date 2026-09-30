package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 「立即生成并推送」的结果。
 *
 * <p>没有推送额度不是错误（一次性订阅没用完/没授权过都是常态），所以用结果字段表达，
 * 让前端去引导授权，而不是抛一个 500 让用户看到「操作失败」。</p>
 */
@Data
@Builder
public class AiContentPushResultDTO {

    /** 是否真的发出去了 */
    private boolean pushed;

    /** 推送对应的简报日期 */
    private String date;

    /** 需要用户去授权/续额度（pushed=false 且此为 true） */
    private boolean needQuota;

    /** 给用户看的一句话 */
    private String message;
}
