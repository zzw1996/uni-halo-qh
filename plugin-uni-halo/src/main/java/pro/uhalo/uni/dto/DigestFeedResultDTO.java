package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RSS 抓取结果：文章列表 + 源站统计（用于日报的「扫描源」一栏）。
 */
@Data
@Builder
public class DigestFeedResultDTO {

    private int totalFeeds;

    /** 至少解析出 1 篇文章的源数量 */
    private int successFeeds;

    private List<DigestArticleDTO> articles;
}
