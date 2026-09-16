package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 小程序端可见的一篇日报文章。
 */
@Data
@Builder
public class DigestPublicArticleDTO {

    private String title;

    private String cover;

    /** AI 分类 key（用于前端筛选） */
    private String category;

    private String categoryLabel;

    private String categoryEmoji;

    private List<String> keywords;

    private String sourceName;

    private String sourceUrl;

    /** AI 摘要：列表里展示，避免用户必须点进去才知道讲什么 */
    private String summary;

    /**
     * 打开这篇内容要跳转的文章（Halo metadata.name）。
     *
     * <p>逐篇发布模式下就是该文章自身；合并发布模式下回落到日报正文，
     * 因此前端不需要区分发布模式，拿到什么就跳什么。</p>
     */
    private String postName;

    /** 原文外链（留作「读原文」入口） */
    private String link;

    /**
     * 命中订阅偏好的原因（如「分类匹配 · AI / ML · 关键词 · transformer」）。
     *
     * <p>仅「我的订阅」视角有值。展示出来是为了让用户明白「为什么这篇出现了」，
     * 否则筛选对他来说就是个黑盒，不知道该收紧还是放宽。</p>
     */
    private String matchedReason;
}
