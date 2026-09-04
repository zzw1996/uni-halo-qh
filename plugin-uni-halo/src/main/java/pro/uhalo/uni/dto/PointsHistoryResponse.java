package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsHistoryResponse {

    private List<PointsHistoryItemDTO> items;

    private long total;

    private boolean hasNext;
}
