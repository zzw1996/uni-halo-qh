package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「我的订单」分页响应。
 */
@Data
@Builder
public class MyPayOrderListResponse {

    private List<MyPayOrderItemDTO> items;

    /** 该用户的订单总数（用于前端展示「共 N 笔」） */
    private long total;

    private int page;

    private int size;

    private boolean hasMore;
}
