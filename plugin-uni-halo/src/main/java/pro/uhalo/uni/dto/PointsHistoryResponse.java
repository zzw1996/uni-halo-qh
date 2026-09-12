package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PointsHistoryResponse {

    private List<PointsHistoryItemDTO> items;

    private long total;

    /**
     * 本次查询范围内（含 action 过滤后）的积分合计，正负相加。
     *
     * <p>用于「抽奖消耗明细」这类按动作码筛选的页面直接展示累计消耗。
     * 取自流水本身而非「次数 × 单价」——站长调整过消耗积分后，历史数字依然准确。
     * 属于新增字段，老版本小程序不读它，不受影响。</p>
     */
    private long totalPoints;

    private boolean hasNext;
}
