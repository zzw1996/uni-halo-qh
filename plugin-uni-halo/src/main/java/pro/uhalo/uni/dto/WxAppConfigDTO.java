package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WxAppConfigDTO {

    private String appId;

    private boolean loginEnabled;

    private int profileModifyLimit;

    private boolean haloBindEnabled;
}
