package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsHistoryItemDTO {

    private String id;

    private String action;

    private String actionLabel;

    private int points;

    private Long balance;

    private String description;

    private String refName;

    private String createTime;

    /** 可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai），优先展示此字段 */
    private String createTimeText;
}
