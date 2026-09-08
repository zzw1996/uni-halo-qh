package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LuckyPrizeDTO {

    private String name;

    private String emoji;

    private int weight;

    /** 中奖后是否需要填写收货信息（实物奖品） */
    private boolean needsAddress;

    /** 奖品说明（前端奖品墙展示） */
    private String desc;
}
