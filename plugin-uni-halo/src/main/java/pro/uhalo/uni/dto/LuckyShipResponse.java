package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LuckyShipResponse {

    private boolean ok;

    private String message;
}
