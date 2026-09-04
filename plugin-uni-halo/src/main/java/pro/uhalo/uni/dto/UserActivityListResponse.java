package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserActivityListResponse {

    private List<UserActivityItemDTO> items;

    private long total;

    private boolean hasNext;

    private int page;

    private int size;
}
