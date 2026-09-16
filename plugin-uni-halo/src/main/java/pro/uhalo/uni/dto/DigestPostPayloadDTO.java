package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
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

    /**
     * 这篇文章的 AI 分类（{@code ai-ml} / {@code security} …）。
     *
     * <p>必须随载荷一起带上：发布阶段原本拿不到它，于是「逐篇发布」时每篇文章
     * 只能共用站长手填的那一组分类，机器判定的分类在打包那一刻就丢了。</p>
     */
    private List<String> aiCategories;

    /** 这篇文章的 AI 关键词（已按整轮频次门槛筛过，用来打标签） */
    private List<String> keywords;
}
