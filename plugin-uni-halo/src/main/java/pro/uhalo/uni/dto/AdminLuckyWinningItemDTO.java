package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AdminLuckyWinningItemDTO {

    private String id;

    private String wxOpenid;

    private String nickname;

    private String avatarUrl;

    private String prizeName;

    private String emoji;

    private String status;

    private String contactName;

    private String phone;

    private String address;

    private Instant winTime;

    private Instant submitTime;

    private String expressCompany;

    private String expressNo;

    private Instant shipTime;

    private String remark;
}
