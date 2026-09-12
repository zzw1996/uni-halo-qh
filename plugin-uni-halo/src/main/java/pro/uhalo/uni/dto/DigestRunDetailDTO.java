package pro.uhalo.uni.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 日报详情（后台详情面板用，含 Markdown 全文与统计）。
 */
@Data
@Builder
public class DigestRunDetailDTO {

    private DigestRunSummaryDTO summary;

    private String markdown;

    /** DigestStatsDTO 解析后的结构，解析失败时为 null */
    private JsonNode stats;
}
