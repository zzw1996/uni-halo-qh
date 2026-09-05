package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * wx.requestVirtualPayment 的支付参数（服务器生成并签名）。
 */
@Data
@Builder
public class PayDataDTO {

    private String signData;

    private String paySig;

    private String signature;

    /** 支付类型：short_series_goods = 道具直购 */
    private String mode;
}
