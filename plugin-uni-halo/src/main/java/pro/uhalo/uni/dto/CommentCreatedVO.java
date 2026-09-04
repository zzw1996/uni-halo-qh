package pro.uhalo.uni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentCreatedVO {

    private boolean success;

    private MetadataVO metadata;

    private SpecVO spec;

    private OwnerVO owner;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetadataVO {
        private String name;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SpecVO {
        private String raw;
        private String creationTime;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OwnerVO {
        private String displayName;
        private String avatar;
    }
}
