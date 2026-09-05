package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminUserActivityItemDTO {

    private String wxUserId;

    private String wxOpenid;

    private String nickname;

    private String haloUserName;

    private String type;

    private String contentType;

    private String articleName;

    private String title;

    private String cover;

    private String excerpt;

    private Instant publishTime;

    private Integer visit;

    private Instant recordTime;

    /** 该行为当日已领取的积分，null 表示不适用 */
    private Integer pointsEarned;

    /** 可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai） */
    private String recordTimeText;
}
