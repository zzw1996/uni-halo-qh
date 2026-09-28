package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 插件自动创建的分类，以及它当前的引用情况。
 *
 * <p>给后台「日报分类」面板用：换掉分类清单之后，老的自动分类会留在站点上，
 * 站长需要看到「哪些已经没人用了（可以删）」「哪些还有文章在用（删了会出问题）」。</p>
 */
@Data
@Builder
public class DigestAutoCategoryDTO {

    /** 分类的 metadata.name（也是 slug） */
    private String name;

    /** 站点上显示的中文名 */
    private String displayName;

    private String description;

    private Instant createdAt;

    /** 引用它的文章数（含草稿）。> 0 时不会被清理 */
    private int postCount;

    /** 本次请求是否真的把它删掉了（列表接口恒为 false） */
    private boolean deleted;

    /** 结论说明：可以安全删除 / 有文章在用已跳过 / 删除失败原因 */
    private String message;
}
