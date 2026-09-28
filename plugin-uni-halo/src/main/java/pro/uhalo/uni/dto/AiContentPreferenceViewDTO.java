package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「AI 定制内容」页面一次拉齐的数据：权益状态 + 当前偏好 + 可选清单 + 各维度上限。
 *
 * <p>与 {@code GET /digest/subscription} 同一思路：读偏好时顺带把「你能选什么」也发下去，
 * 前端不必自己猜候选、也不会出现「点了一个从来没内容的来源」。
 * 上限也在这里下发（而不是硬编码在前端），站长改配置后小程序立即跟着变。</p>
 */
@Data
@Builder
public class AiContentPreferenceViewDTO {

    private AiContentStatusDTO status;

    private AiContentPreferenceDTO preference;

    /** 可选的主题分类（来自站长的「日报分类」清单） */
    private List<DigestCategoryDTO> categoryOptions;

    /** 可选的来源站点（来自最近日报里实际出现过的来源，带篇数） */
    private List<DigestOptionItemDTO> sourceOptions;

    /** 各维度上限与简报参数（前端据此做计数与提示） */
    private Limits limits;

    /** 权益说明文案（未开通页展示；留空时前端用通用文案） */
    private String benefitNote;

    @Data
    @Builder
    public static class Limits {
        private int maxCategories;
        private int maxSources;
        private int maxKeywords;
        private int maxItems;
        private int windowDays;
        /** 是否开启 AI 导语（关闭时页面要说明「本次为纯筛选」） */
        private boolean aiIntroEnabled;
    }
}
