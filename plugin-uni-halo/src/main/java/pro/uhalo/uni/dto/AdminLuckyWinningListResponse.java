package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminLuckyWinningListResponse {

    private List<AdminLuckyWinningItemDTO> items;

    private long total;

    private boolean hasNext;

    private int page;

    private int size;
}
