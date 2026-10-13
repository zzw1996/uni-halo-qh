package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 公众号相关请求体（生成 / 历史补推）。
 */
@Data
public class MpRequest {

    /** 生成：只生成某个方向（留空 = 全部方向） */
    private String direction;

    /** 历史补推：日报记录名（DigestReport metadata.name） */
    private String reportName;

    /** 历史补推形态：merged（整期一篇，默认）/ per-article（逐篇） */
    private String mode;

    /** 推送：是否强制重推（已推送过的稿件默认拒绝） */
    private Boolean force;
}
