package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminWxUserItemDTO {

    private String id;

    private String openid;

    private String unionid;

    private String nickname;

    private String avatarUrl;

    private Boolean profileComplete;

    private Integer profileModifyCount;

    private String haloUserName;

    private Instant creationTimestamp;
}
