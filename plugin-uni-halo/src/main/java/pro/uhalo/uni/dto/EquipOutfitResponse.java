package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EquipOutfitResponse {

    private String avatarFrame;

    private String nickFrame;

    private String theme;

    private String message;
}
