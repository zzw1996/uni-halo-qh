package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 中奖后提交收货信息。
 */
@Data
public class LuckyClaimRequest {

    private String winningId;

    private String contactName;

    private String phone;

    private String address;
}
