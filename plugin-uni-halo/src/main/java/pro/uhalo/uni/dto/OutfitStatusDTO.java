package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OutfitStatusDTO {

    private boolean enabled;

    private List<OutfitItemDTO> items;

    private List<String> owned;

    private String avatarFrame;

    private String nickFrame;

    private String theme;
}
