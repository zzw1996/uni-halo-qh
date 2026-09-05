package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RankArticleDTO {

    /** Post metadata.name（slug），用于跳转文章详情 */
    private String name;

    private String title;

    private String cover;

    private int visit;

    private int upvote;
}
