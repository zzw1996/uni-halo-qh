package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PointsConfigDTO {

    @Builder.Default
    private boolean enabled = false;

    @Builder.Default
    private boolean showInProfile = true;

    @Builder.Default
    private int minBrowseDuration = 5;

    @Builder.Default
    private PointsRulesDTO rules = PointsRulesDTO.builder().build();
}
