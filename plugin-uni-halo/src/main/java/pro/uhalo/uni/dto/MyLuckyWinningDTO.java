package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class MyLuckyWinningDTO {

    private String winningId;

    private String prizeName;

    private String emoji;

    /** pending（待填写收货信息）| submitted（已提交待发货）| shipped（已发货） */
    private String status;

    private Instant winTime;

    /** 已提交后回显收货信息，供用户确认 */
    private String contactName;

    private String phone;

    private String address;

    private String expressCompany;

    private String expressNo;

    private Instant shipTime;
}
