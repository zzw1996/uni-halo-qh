package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutfitItemDTO {

    private String key;

    private String name;

    /** avatar_frame | nick_frame | theme */
    private String type;

    private int points;

    /** 0 = 永久 */
    private int durationDays;

    private String style;
}
