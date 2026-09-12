package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 后台「立即生成」请求体。
 */
@Data
public class DigestRunRequest {

    /**
     * 是否在生成后自动发布。
     *
     * <p>不传（null）时按插件配置里的「自动发布」开关决定。</p>
     */
    private Boolean publish;
}
