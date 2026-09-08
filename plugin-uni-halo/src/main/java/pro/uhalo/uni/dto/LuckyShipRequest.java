package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 管理端发货请求：物流信息均可为空（支持线下见面发放等场景）。
 */
@Data
public class LuckyShipRequest {

    /** 快递公司，如：顺丰速运 */
    private String expressCompany;

    /** 快递单号 */
    private String expressNo;

    /** 发放备注，如：线下领取 */
    private String remark;
}
