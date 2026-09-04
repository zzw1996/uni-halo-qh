package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WxAvatarUploadDTO {

    private String avatarUrl;
}
