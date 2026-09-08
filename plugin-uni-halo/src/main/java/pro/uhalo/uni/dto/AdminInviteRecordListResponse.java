package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminInviteRecordListResponse {

    /** 邀请明细（分页） */
    private List<AdminInviteRecordItemDTO> items;

    /** 按邀请人汇总（不分页，按总积分倒序） */
    private List<AdminInviteSummaryItemDTO> summary;

    private long total;

    private boolean hasNext;

    private int page;

    private int size;
}
