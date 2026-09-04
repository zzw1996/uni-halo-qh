package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CancelUpvoteResponse {

    private boolean success;

    private boolean upvoted;

    public static CancelUpvoteResponse cancelled() {
        return CancelUpvoteResponse.builder()
            .success(true)
            .upvoted(false)
            .build();
    }
}
