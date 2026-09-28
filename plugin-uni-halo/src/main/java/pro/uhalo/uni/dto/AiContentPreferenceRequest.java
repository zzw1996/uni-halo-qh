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
}
