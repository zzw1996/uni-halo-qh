package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;
import pro.uhalo.uni.utils.DigestCategoryCatalog;

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

    /**
     * 分类目录（站长配置的分类清单）。
     *
     * <p>跟着待发布内容一起传下去，而不是让发布器自己读一遍配置：两条发布路径
     * （官方 HTTP 接口与扩展客户端兜底）必须拿到同一份口径，而且「生成的是什么」
     * 与「发出去的是什么」要对得上——发布失败后补发时，用的仍是生成那一刻的清单。</p>
     *
     * <p>null = 内置六类（与升级前完全一致）。</p>
     */
    private DigestCategoryCatalog catalog;

    /**
     * {@link #aiCategories} 的落库模式：{@code create}（默认，不存在则创建）/
     * {@code reuse}（只复用已有站点分类）/ {@code map}（按清单里的映射指定）。
     *
     * <p>null 或留空按 {@code create} 处理，保持与升级前一致的默认行为。</p>
     */
    private String categoryMode;

    /** 文章作者，必须是已存在的 Halo 用户名 */
    private String owner;

    @Builder.Default
    private boolean allowComment = true;
}
