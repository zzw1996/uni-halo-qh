package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsDrawResponse {

    /** 本次中奖积分（已翻牌时为 0） */
    private int earned;

    /** 本次消耗积分（已翻牌时为 0） */
    private int cost;

    /** 当前余额 */
    private Long balance;

    /** 今日是否已翻牌 */
    private boolean alreadyDrawn;

    private String message;

    /** 翻牌名称（用于前端展示，如 "积分翻牌"） */
    @Builder.Default
    private String title = "每日翻牌";
}
