package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 管理端编辑中奖单请求：状态与收货信息均可修正。
 */
@Data
public class LuckyWinningUpdateRequest {

    /** pending（待填写）| submitted（已提交待发货）| shipped（已发货） */
    private String status;

    private String contactName;

    private String phone;

    private String address;

    private String remark;
}
