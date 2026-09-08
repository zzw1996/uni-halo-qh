package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 内容表情反馈状态：各表情计数 + 我当前的表情。
 */
@Data
@Builder
public class ContentReactionStatusDTO {

    /** kind -> 人数（每个用户每种内容只有一个表情） */
    private Map<String, Long> counts;

    /** 我当前的表情 kind（未登录或未参与时为空） */
    private String mine;
}
