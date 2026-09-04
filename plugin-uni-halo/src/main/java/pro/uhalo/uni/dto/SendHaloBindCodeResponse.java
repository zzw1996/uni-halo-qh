package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SendHaloBindCodeResponse {

    private Boolean success;

    private String maskedEmail;

    private Integer expireSeconds;
}
