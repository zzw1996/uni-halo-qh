package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「商业化配置」页面里的一个配置分组。
 *
 * <p>{@link #formSchema} 直接来自 Halo 解析好的 {@code Setting.spec.forms}，
 * 不是本插件另抄一份：字段定义只有 {@code setting.yaml} 一份来源，
 * 以后加字段只改 YAML，这个页面自动就有，不会出现两处定义不一致。</p>
 */
@Data
@Builder
public class ConfigGroupDTO {

    private String group;

    private String label;

    /** setting.yaml 里该分组的 formSchema（结构化 JSON，前端按它递归渲染） */
    private List<Object> formSchema;

    /**
     * 该分组已保存的值（来自 ConfigMap，未配置时为空对象）。
     *
     * <p>用于判断「哪些字段是站长自己改过的」：页面据此给未自定义的字段打「默认」标记。</p>
     */
    private Object values;

    /**
     * 该分组<b>实际生效</b>的配置：把已保存的值交给运行时解析器补齐默认值后的结果。
     *
     * <p>为什么要单独给这个：配置有两套默认值——{@code setting.yaml} 里表单的 {@code value}，
     * 与 Java 解析器里的 {@code defaults()}。两者未必一致（例如 {@code pointsConfig} 未配置时
     * 表单显示 0 而运行时用浏览 1 分/评论 5 分）。如果页面只按表单默认值展示，
     * 站长看到的就不是系统真正在用的值，而且一保存就把表单里的数字固化下来、
     * 悄悄改变了实际行为。因此表单必须以这里为准来初始化与展示。</p>
     */
    private Object effective;
}
