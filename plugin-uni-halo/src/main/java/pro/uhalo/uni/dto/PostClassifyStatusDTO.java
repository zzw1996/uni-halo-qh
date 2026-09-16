package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 旧文章归类进度：站点上还有多少篇没进主题体系。
 *
 * <p>给后台页面显示进度用，站长据此决定还要点几次「分析下一批」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostClassifyStatusDTO {

    /** AI 日报生成的文章总数（只有这些文章参与归类） */
    private int digestPosts;

    /** 已经有主题分类的文章数 */
    private int classifiedPosts;

    /** 还没有主题分类的文章数（待归类） */
    private int pendingPosts;

    /** 本轮每批分析的篇数（页面提示「每批 N 篇」） */
    private int batchSize;

    /** AI 是否已配置好（未配 key 时页面直接提示，而不是让站长点出一个失败） */
    private boolean aiReady;

    /** 自动打标是否开启（关闭时这里归类也不会生效，页面据此提醒） */
    private boolean autoTagEnabled;

    /** 自动分类是否开启（关闭时预览不会给出主题建议） */
    private boolean autoCategoryEnabled;
}
