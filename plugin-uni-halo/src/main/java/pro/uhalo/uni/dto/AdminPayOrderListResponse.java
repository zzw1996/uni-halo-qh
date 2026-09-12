package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 后台订单列表响应（分页）。 */
@Data
@Builder
public class AdminPayOrderListResponse {

    private List<AdminPayOrderItemDTO> items;

    private long total;

    private boolean hasNext;

    private int page;

    private int size;
}
