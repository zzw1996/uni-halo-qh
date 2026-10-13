package pro.uhalo.uni.dto;

import lombok.Data;

import java.time.Instant;

/**
 * 公众号稿件列表项（控制台「公众号」页面与独立管理页共用）。
 */
@Data
public class MpIssueSummaryDTO {

    /** 资源名（详情、推送、删除都按它定位） */
    private String name;

    /** ISSUE（按方向生成）/ BACKFILL（历史日报补推） */
    private String sourceKind;

    private String date;

    private String direction;

    private String directionLabel;

    /** 触发来源：manual（管理页点击）/ scheduled（定时窗口） */
    private String trigger;

    private String title;

    private String digest;

    /** 摘要实际字数（页面显示 xx/120） */
    private Integer digestWords;

    private Integer articleCount;

    /** GENERATING / GENERATED / DRAFTED / PUBLISHED / MASSED / EMPTY / FAILED */
    private String state;

    /** draft / publish / mass（未推送时为空） */
    private String pushMode;

    private String articleUrl;

    /** 同步发到站点时的文章地址（「阅读原文」指向它） */
    private String sitePostUrl;

    private String cover;

    private String imageNote;

    /** 内容完整性说明：哪几篇没拿到完整的 AI 成稿、缺哪几件（空 = 全篇完整） */
    private String contentNote;

    private String error;

    private Integer errcode;

    private Instant createdAt;

    private Instant updatedAt;
}
