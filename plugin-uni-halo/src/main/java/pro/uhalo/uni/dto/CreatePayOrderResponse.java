package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreatePayOrderResponse {

    private String outTradeNo;

    private String status;

    private PayDataDTO payData;
}
