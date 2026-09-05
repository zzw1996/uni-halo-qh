package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryPayOrderResponse {

    private String outTradeNo;

    /** pending | delivered | closed */
    private String status;

    private Boolean delivered;

    /** 到账积分（已发货时返回） */
    private Integer points;

    /** 当前积分余额（已发货时返回） */
    private Long balance;

    private String message;
}
