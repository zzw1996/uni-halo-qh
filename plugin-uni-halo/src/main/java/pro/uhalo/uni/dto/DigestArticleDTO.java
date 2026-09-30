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

    /**
     * AI 看法：AI 以自己视角给出的评价、延伸或对读者的意义。
     *
     * <p>防侵权的关键段落——与「概述/核心信息」（转述原文事实）严格区分，
     * 必须是 AI 的原创视角而非原文复述。</p>
     */
    private String aiTake;

    private String reason;

    // ── 精选阶段 ──

    /**
     * 补位文章：本期分类有缺口，靠放宽时间窗补抓找回来的。
     *
     * <p>只用于排序（同样有图、同分时让时间窗内的新文章优先）与统计标记。</p>
     */
    private boolean supplementary;

    // ── 图片上传阶段 ──

    /** 上传到 Halo 附件库后的访问地址，上传失败或未开启图片时为空 */
    private String uploadedImage;

    /**
     * 封面是服务端生成的标题卡片（不是原文头图）。
     *
     * <p>这种卡片只作封面用：正文里再插一遍它没有信息量，所以渲染时跳过。</p>
     */
    private boolean placeholderCover;

    public int getTotalScore() {
        return relevance + quality + timeliness;
    }
}
