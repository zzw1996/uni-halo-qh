package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LevelConfigDTO {

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private List<LevelItemDTO> levels = List.of();

    @Builder.Default
    private List<TitleItemDTO> titles = List.of();

    @Builder.Default
    private List<OutfitItemDTO> outfits = List.of();
}
