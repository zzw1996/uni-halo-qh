package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LevelStatusDTO {

    private boolean enabled;

    private int level;

    private String levelTitle;

    private long totalEarned;

    /** 当前等级所需最低累计积分 */
    private int currentMin;

    /** 下一等级所需最低累计积分（最高级时为 0） */
    private int nextMin;

    /** 距下一等级所需积分（最高级时为 0） */
    private int nextPoints;

    private String wornTitleId;

    private List<UnlockedTitleDTO> unlockedTitles;
}
