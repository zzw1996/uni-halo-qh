package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpvoteStatusResponse {

    private boolean upvoted;
}
