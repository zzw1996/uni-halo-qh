package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 支付看板的单日趋势项。
 */
@Data
@Builder
public class PayStatsTrendItemDTO {

    /** 日期，格式 MM-dd（Asia/Shanghai） */
    private String date;

    /** 当日已到货订单数 */
    private long count;

    /** 当日已到货金额（分） */
    private long amountCents;
}
