package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单篇文章的发布结果。
 *
 * <p>逐篇发布时一篇对应一条，合并发布时只有一条；失败的那篇不会影响其它篇，
 * 后台页面按篇展示状态，能直接看出是哪一篇、卡在哪一步。</p>
 *
 * <p>{@code @NoArgsConstructor} 是必需的：{@code @Builder} 会生成全参构造，
 * 从而不再有默认构造，Jackson 反序列化会直接失败（而且是静默失败）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestPostResultDTO {

    /** 文章标题（已发布到 Halo 的标题） */
    private String title;

    /** Halo 文章 metadata.name */
    private String postName;

    private String url;

    /** PUBLISHED / PENDING / FAILED */
    private String state;

    /** 失败或待确认时的原因 */
    private String message;
}
