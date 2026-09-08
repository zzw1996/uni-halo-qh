package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardTrendItemDTO {

    /** yyyy-MM-dd */
    private String date;

    /** 当日发布文章数 */
    private int count;
}
