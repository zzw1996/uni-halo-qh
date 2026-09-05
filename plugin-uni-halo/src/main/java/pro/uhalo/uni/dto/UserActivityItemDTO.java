package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserActivityItemDTO {

    private String articleName;

    /** 行为类型：comment / browse / upvote */
    private String type;

    /** 内容类型：post（默认）或 moment */
    private String contentType;

    private String title;

    private String cover;

    private String excerpt;

    private Instant publishTime;

    private Integer visit;

    private Instant recordTime;

    /** 可读时间（yyyy-MM-dd HH:mm:ss，Asia/Shanghai） */
    private String recordTimeText;

    /** 该行为对应领取的积分数量；无对应领分流水时为 null */
    private Integer pointsEarned;
}
