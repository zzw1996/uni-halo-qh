package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminPointsRecordItemDTO {

    private String id;

    private String wxOpenid;

    private String nickname;

    private String haloUserName;

    private String action;

    private String actionLabel;

    private int points;

    private Long balance;

    private String description;

    private String refName;

    private String contentTitle;

    private String createTime;

    /** 可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai），优先展示此字段 */
    private String createTimeText;
}
