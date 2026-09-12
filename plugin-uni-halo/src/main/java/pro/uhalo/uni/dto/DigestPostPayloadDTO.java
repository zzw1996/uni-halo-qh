package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待发布的文章内容。
 *
 * <p>在生成阶段就固化并随报告一起保存：这样「只生成不发布」之后再点发布、
 * 或者只补发失败的那几篇，都不需要重新跑一遍抓取与 AI，也保证发出去的内容
 * 和当时生成的一模一样。</p>
 *
 * <p>{@code @NoArgsConstructor} 是必需的：{@code @Builder} 会生成全参构造，
 * 从而不再有默认构造，Jackson 反序列化会直接失败（而且是静默失败）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestPostPayloadDTO {

    private String title;

    private String slug;

    /** Markdown 原文（由 Halo 服务端渲染） */
    private String markdown;

    private String cover;
}
