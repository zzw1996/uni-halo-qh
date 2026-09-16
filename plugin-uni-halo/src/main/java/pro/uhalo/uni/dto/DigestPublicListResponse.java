package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 小程序端「AI 日报」列表响应。
 */
@Data
@Builder
public class DigestPublicListResponse {

    /** 站点是否开启了 AI 日报（关闭时前端不展示入口） */
    private boolean enabled;

    private List<DigestPublicReportDTO> items;

    /** 可用的分类（含中文名与图标），供前端做筛选条 —— 分类只有一份定义源 */
    private List<DigestCategoryDTO> categories;

    private int page;

    private int size;

    private boolean hasMore;
}
