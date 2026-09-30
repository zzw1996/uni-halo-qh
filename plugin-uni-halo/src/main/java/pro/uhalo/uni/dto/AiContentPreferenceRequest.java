package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.List;

/** 保存用户偏好的请求体（全量替换，与订阅同一套口径） */
@Data
public class AiContentPreferenceRequest {

    private Boolean enabled;

    private List<String> categories;

    private List<String> sources;

    private List<String> keywords;

    /** 用户手工输入的定制主题（如「想了解大模型在教育的落地」） */
    private List<String> customTopics;

    /** 是否开启每日定时推送 */
    private Boolean pushEnabled;

    /** 每日推送整点（0-23，必须落在站长配置的可选时段里） */
    private Integer pushHour;
}
