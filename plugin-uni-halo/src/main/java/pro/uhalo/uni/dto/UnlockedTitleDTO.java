package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UnlockedTitleDTO {

    private String id;

    private String name;

    /** total_browse | sign_in | vote | comment | invite | total_points */
    private String type;

    private String desc;

    private int target;

    private int progress;

    private boolean unlocked;

    private boolean worn;
}
