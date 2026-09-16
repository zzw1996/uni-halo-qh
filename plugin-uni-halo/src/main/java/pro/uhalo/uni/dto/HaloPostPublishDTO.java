package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 待发布的日报文章。
 */
@Data
@Builder
public class HaloPostPublishDTO {

    private String title;

    private String slug;

    /** 文章正文（Markdown 原文，由 Halo 服务端渲染成 HTML） */
    private String markdown;

    private String cover;

    private List<String> categoryNames;

    private List<String> tagNames;

    /**
     * AI 判定的分类 key：会「不存在则自动创建」为站点分类。
     *
     * <p>与 {@link #categoryNames} 的区别在于**是否需要预先存在**：
     * {@code categoryNames} 是站长手填的额外附加项，匹配不到就忽略（避免手误造出分类）；
     * 而这里是机器判定的固定枚举，建出来才算真正实现「不需要手工配置分类」。</p>
     */
    private List<String> aiCategories;

    /** AI 关键词：按整轮频次门槛筛过后，会「不存在则自动创建」为站点标签 */
    private List<String> keywords;

    /** 文章作者，必须是已存在的 Halo 用户名 */
    private String owner;

    @Builder.Default
    private boolean allowComment = true;
}
