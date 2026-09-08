package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class WxLoginRequest {

    private String code;

    private String nickname;

    private String avatarUrl;

    /** 邀请人的邀请码（可选，首次注册时生效） */
    private String inviteCode;
}
