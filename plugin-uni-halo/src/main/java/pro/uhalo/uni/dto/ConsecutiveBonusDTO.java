package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConsecutiveBonusDTO {

    private int days;

    private int bonus;
}
