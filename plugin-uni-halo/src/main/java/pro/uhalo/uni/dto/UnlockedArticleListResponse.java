package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 已解锁文章列表响应。
 */
@Data
@Builder
public class UnlockedArticleListResponse {

    private List<UnlockedArticleItemDTO> items;

    /** 该用户的解锁记录总数 */
    private long total;

    /** 当前剩余阅读券张数，便于在列表页顶部直接展示 */
    private Integer tickets;
}
