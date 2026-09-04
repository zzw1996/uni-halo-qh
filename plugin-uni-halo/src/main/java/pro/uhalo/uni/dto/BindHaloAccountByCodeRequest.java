package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class BindHaloAccountByCodeRequest {

    private String account;

    private String code;
}
