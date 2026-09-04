package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class UpdateProfileRequest {

    private String nickname;

    private String avatarUrl;
}
