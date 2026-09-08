package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class RecordUserActivityRequest {

    private String type;

    /** post（默认）或 moment，moment 时 articleName 为瞬间 metadata.name */
    private String contentType;

    private String articleName;

    private String title;

    private String cover;

    private String excerpt;

    /** 表情反馈类型（仅 type=reaction 时使用） */
    private String reactionKind;
}
