package pro.uhalo.uni.dto;

import lombok.Data;

/**
 * 今日头条号生成请求体（控制台管理页）。
 */
@Data
public class ToutiaoRequest {

    /** 主题关键词（必填） */
    private String keyword;

    /** 写作风格（留空 = 用配置的默认风格） */
    private String style;

    /** 正文目标字数（留空 = 用配置的默认值；越界会被收敛） */
    private Integer words;

    /** 标题候选个数（留空 = 用配置的默认值；越界会被收敛） */
    private Integer titleCount;
}
