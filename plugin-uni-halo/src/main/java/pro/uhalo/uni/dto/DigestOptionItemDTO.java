package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 通用筛选项（关键词 / 来源站点）。
 */
@Data
@Builder
public class DigestOptionItemDTO {

    private String value;

    /** 该选项在统计窗口内出现的篇数 */
    private int count;
}
