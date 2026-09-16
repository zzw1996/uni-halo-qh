package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.Map;

/**
 * 「商业化配置」保存请求。
 *
 * <p>只提交页面管理的分组；服务端做<b>读-改-写合并</b>，
 * 未出现在 {@link #groups} 里的分组与字段一律原样保留，
 * 避免出现「在某处改个配置，另一处的高级字段被清空」这种最难排查的问题。</p>
 */
@Data
public class CommercialConfigSaveRequest {

    /** 分组名 → 该分组的字段值 */
    private Map<String, Map<String, Object>> groups;
}
