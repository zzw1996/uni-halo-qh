package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布环境自检项。
 *
 * <p>把「令牌是否有效、作者是否存在、分类标签能不能匹配上」这类前置条件一次性查清楚，
 * 后台页面直接展示结果，不用去翻日志。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestPreflightItemDTO {

    /** 检查项名称 */
    private String item;

    private boolean ok;

    /** 结论与处置建议 */
    private String message;
}
