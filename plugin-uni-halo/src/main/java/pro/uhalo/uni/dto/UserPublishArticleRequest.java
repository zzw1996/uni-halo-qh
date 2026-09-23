package pro.uhalo.uni.dto;

import lombok.Data;

/** 投稿文章请求体（正文为 Markdown） */
@Data
public class UserPublishArticleRequest {

    private String title;

    /** Markdown 正文 */
    private String content;

    /** 摘要，可为空 */
    private String summary;

    /** 封面图地址，可为空 */
    private String cover;
}
