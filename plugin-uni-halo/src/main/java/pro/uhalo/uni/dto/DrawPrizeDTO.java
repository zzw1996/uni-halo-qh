package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DrawPrizeDTO {

    private int points;

    /** 抽中权重（总权重内按比例随机） */
    private int weight;
}
