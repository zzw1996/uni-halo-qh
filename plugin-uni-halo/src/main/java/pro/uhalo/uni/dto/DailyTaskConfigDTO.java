package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 每日任务整体配置（dailyTaskConfig 设置分组）。
 */
@Data
@Builder
public class DailyTaskConfigDTO {

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private List<DailyTaskItemDTO> tasks = List.of();
}
