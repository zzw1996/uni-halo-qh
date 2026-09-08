package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 每日任务列表与进度汇总。
 */
@Data
@Builder
public class DailyTasksResponseDTO {

    /** 当前日期，格式 yyyy-MM-dd（Asia/Shanghai） */
    private String date;

    /** 今日已累计获得积分 */
    private long todayEarned;

    private long balance;

    private List<DailyTaskProgressItemDTO> items;
}
