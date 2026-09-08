package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TitleItemDTO {

    private String id;

    private String name;

    /** total_browse | sign_in | vote | comment | invite | total_points */
    private String type;

    private int target;

    private String desc;
}
