package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 旧文章归类的预览结果（<b>不改动任何文章</b>）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostClassifyPreviewResponse {

    /** 本批判定结果，顺序与站点上的文章顺序一致 */
    private List<PostClassifyCandidateDTO> items;

    /** 本批实际分析的篇数 */
    private int analyzed;

    /** 整个站点还剩多少篇待归类（含本批；页面显示「还剩 N 篇」） */
    private int pendingPosts;

    /** 还有没有下一批（false 时页面隐藏「分析下一批」按钮） */
    private boolean hasMore;

    /** 本次使用的模型名，便于站长判断是不是走了兜底通道 */
    private String aiModel;

    private String message;
}
