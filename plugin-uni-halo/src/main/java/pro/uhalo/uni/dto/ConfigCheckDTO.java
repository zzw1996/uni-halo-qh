package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 一条配置关联检查结果。
 *
 * <p>这些检查是「商业化配置」页面存在的理由：分组之间存在的耦合在设置表单里完全看不出来，
 * 出问题的时间点是<b>用户付款之后</b>（例如商品类型选了会员卡但会员功能没开，
 * 发货会失败并转人工退款），而不是站长保存配置的那一刻。</p>
 */
@Data
@Builder
public class ConfigCheckDTO {

    /**
     * 严重程度：
     * <ul>
     *   <li>{@code error} —— 会导致功能不可用或付款后无法发放，<b>阻止保存</b>；</li>
     *   <li>{@code warn} —— 配置不协调或收益受损，允许保存但显著提示；</li>
     *   <li>{@code info} —— 中性事实（如券的成本折算），仅用于让关联关系可见。</li>
     * </ul>
     */
    private String level;

    /** 相关配置分组名，前端据此定位到具体表单 */
    private String group;

    private String title;

    private String detail;

    public static ConfigCheckDTO error(String group, String title, String detail) {
        return of("error", group, title, detail);
    }

    public static ConfigCheckDTO warn(String group, String title, String detail) {
        return of("warn", group, title, detail);
    }

    public static ConfigCheckDTO info(String group, String title, String detail) {
        return of("info", group, title, detail);
    }

    private static ConfigCheckDTO of(String level, String group, String title, String detail) {
        return ConfigCheckDTO.builder()
            .level(level)
            .group(group)
            .title(title)
            .detail(detail)
            .build();
    }
}
