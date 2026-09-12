package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 未发布成功的残留文章（「清理残留」用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostResidueDTO {

    private String name;

    private String title;

    private Instant createdAt;
}
