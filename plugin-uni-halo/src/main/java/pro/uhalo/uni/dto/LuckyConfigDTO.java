package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LuckyConfigDTO {

    private boolean enabled;

    private int costPoints;

    private List<LuckyPrizeDTO> prizes;
}
