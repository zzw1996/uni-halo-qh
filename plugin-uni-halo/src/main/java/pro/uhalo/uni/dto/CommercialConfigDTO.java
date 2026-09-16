package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「商业化配置」页面数据：分组表单定义 + 当前值 + 关联检查结果。
 */
@Data
@Builder
public class CommercialConfigDTO {

    /** 设置资源名（写回时用于定位 ConfigMap） */
    private String settingName;

    /** 值存储的 ConfigMap 名 */
    private String configMapName;

    /** 被本页面接管的分组（其余分组原样保留，不受本页面保存影响） */
    private List<ConfigGroupDTO> groups;

    private List<ConfigCheckDTO> checks;
}
