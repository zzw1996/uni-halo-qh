package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LevelItemDTO {

    private int level;

    private int minPoints;

    private String title;
}
