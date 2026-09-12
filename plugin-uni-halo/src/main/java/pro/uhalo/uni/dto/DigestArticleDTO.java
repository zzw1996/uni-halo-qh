package pro.uhalo.uni.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 日报文章：抓取阶段填入原始字段，AI 阶段填入评分与摘要，图片阶段填入上传后的地址。
 *
 * <p>流水线各步之间共享同一个可变对象，避免为每个阶段维护一套结构。</p>
 */
@Data
public class DigestArticleDTO {

    // ── 抓取阶段 ──

    private String title;

    private String link;

    private Instant pubDate;

    /** 已去除 HTML 标签的正文摘要（截断），用于喂给 AI */
    private String description;

    private String sourceName;

    private String sourceUrl;

    /** 原始头图地址（RSS 内嵌图或文章页 og:image），可能为空 */
    private String imageUrl;

    // ── 评分阶段 ──

    private int relevance;

    private int quality;

    private int timeliness;

    /** ai-ml / security / engineering / tools / opinion / other */
    private String category;

    private List<String> keywords = new ArrayList<>();

    // ── 摘要阶段 ──

    private String titleZh;

    private String summary;

    private List<String> points = new ArrayList<>();

    private String reason;

    // ── 图片上传阶段 ──

    /** 上传到 Halo 附件库后的访问地址，上传失败或未开启图片时为空 */
    private String uploadedImage;

    public int getTotalScore() {
        return relevance + quality + timeliness;
    }
}
