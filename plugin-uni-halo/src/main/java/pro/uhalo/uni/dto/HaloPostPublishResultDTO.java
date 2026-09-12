package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 发布结果。
 *
 * <p>即使发布没走完也会返回结果对象（state = PENDING/FAILED 并带 message），
 * 让后台能看到「卡在哪一步」，而不是只拿到一句异常。</p>
 */
@Data
@Builder
public class HaloPostPublishResultDTO {

    private String postName;

    private String permalink;

    /** PUBLISHED / PENDING / FAILED */
    private String state;

    private String message;
}
