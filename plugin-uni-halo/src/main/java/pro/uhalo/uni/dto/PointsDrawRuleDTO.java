package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsDrawRuleDTO {

    /** 每次翻牌消耗积分 */
    @Builder.Default
    private int cost = 10;

    @Builder.Default
    private List<DrawPrizeDTO> prizes = List.of(
        DrawPrizeDTO.builder().points(2).weight(35).build(),
        DrawPrizeDTO.builder().points(5).weight(30).build(),
        DrawPrizeDTO.builder().points(10).weight(15).build(),
        DrawPrizeDTO.builder().points(20).weight(10).build(),
        DrawPrizeDTO.builder().points(50).weight(7).build(),
        DrawPrizeDTO.builder().points(100).weight(3).build()
    );
}
