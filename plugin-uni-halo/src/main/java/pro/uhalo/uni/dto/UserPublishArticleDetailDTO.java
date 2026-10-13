package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单篇投稿详情（「我的投稿」→ 编辑时回填用）。
 *
 * <p>与列表项的区别是带 <b>Markdown 正文原文</b>：编辑页要把正文放回输入框，
 * 拿渲染后的 HTML 回填会把用户的 Markdown 改写掉。正文取自 Halo 的
 * {@code posts/{name}/head-content}（head 快照 = 尚未发布的那份草稿），
 * 而不是 release 快照——作者接着改的应该是自己最后一次保存的内容。</p>
 */
@Data
@Builder
public class UserPublishArticleDetailDTO {

    /** 文章标识（Post 的 metadata.name） */
    private String name;

    private String title;

    /** Markdown 正文原文 */
    private String content;

    private String slug;

    private String cover;

    /** 分类的 metadata.name（与投稿时提交的口径一致，回填后再保存能原样匹配上） */
    private List<String> categories;

    /** 标签名 */
    private List<String> tags;

    private boolean allowComment;

    /** 是否已发布（决定保存后要不要提示「等站长重新发布」） */
    private boolean published;

    /** 已发布但有未生效的修改（见 {@link UserPublishArticleDTO#isHasPendingChanges()}） */
    private boolean hasPendingChanges;

    /** 直接给用户看的状态文案 */
    private String stateText;

    /** 已发布时的永久链接 */
    private String permalink;

    private String publishTime;

    private String createTime;
}
