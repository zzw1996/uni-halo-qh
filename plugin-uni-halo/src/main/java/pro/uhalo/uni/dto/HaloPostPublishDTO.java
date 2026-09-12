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

    /** 文章作者，必须是已存在的 Halo 用户名 */
    private String owner;

    @Builder.Default
    private boolean allowComment = true;
}
