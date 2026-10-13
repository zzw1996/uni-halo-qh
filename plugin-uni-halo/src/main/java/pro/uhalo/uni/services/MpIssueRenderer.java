package pro.uhalo.uni.services;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.utils.DigestCategoryCatalog;

import java.util.List;

/**
 * 公众号文章成稿（Markdown）：导语 + 按序排列的精选点评 + 版权声明。
 *
 * <p>与站点日报的成稿（{@link DigestRenderer}）刻意分开：日报是给博客主题渲染的
 * （Mermaid、表格、折叠块都能用），公众号只吃内联样式的一段富文本，
 * 结构与措辞都要更克制。这份 Markdown 交给 {@link MpHtmlRenderer} 转成微信 HTML。</p>
 */
@Component
public class MpIssueRenderer {

    /**
     * 一篇公众号文章的成稿。
     *
     * @param directionLabel 方向中文名（如「AI 发展」）
     * @param intro          导语（AI 生成，可为空）
     * @param articles       本期精选（按顺序）
     * @param dateText       日期文案（yyyy-MM-dd）
     * @param catalog        方向目录（取分类名与图标）
     */
    public String render(String directionLabel, String intro, List<DigestArticleDTO> articles,
        String dateText, DigestCategoryCatalog catalog) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var report = new StringBuilder();

        if (StringUtils.hasText(intro)) {
            report.append("> ").append(intro.trim()).append("\n\n");
        }

        var index = 0;
        for (var article : articles) {
            index++;
            appendArticleSection(report, article, directory, index, true);
        }

        appendDisclaimer(report, dateText);
        return report.toString();
    }

    /**
     * 单篇成稿（「每篇精选各一篇」粒度）。
     *
     * <p>标题与摘要在外层已经按上限收敛过，这里只负责正文：来源行、配图、概述、
     * 核心信息、推荐理由、小编看法、关键词与版权声明。不带导语——单篇自成一篇，
     * 导语属于「方向聚合」那种形态。</p>
     */
    public String renderSingle(DigestArticleDTO article, String dateText,
        DigestCategoryCatalog catalog) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var report = new StringBuilder();
        appendArticleSection(report, article, directory, 0, false);
        appendDisclaimer(report, dateText);
        return report.toString();
    }

    /** 单篇文章块（聚合版带序号标题；单篇版直接从来源行开始） */
    private void appendArticleSection(StringBuilder report, DigestArticleDTO article,
        DigestCategoryCatalog directory, int index, boolean withHeading) {
        if (withHeading) {
            report.append("## ").append(index).append(". ")
                .append(DigestRenderer.displayTitle(article)).append("\n\n");
        }
        report.append('[').append(StringUtils.hasText(article.getTitle())
                ? article.getTitle() : DigestRenderer.displayTitle(article))
            .append("](").append(article.getLink()).append(") — ")
            .append(article.getSourceName()).append(" · ")
            .append(DigestRenderer.humanizeTime(article.getPubDate()));
        if (StringUtils.hasText(article.getCategory())) {
            report.append(" · ").append(directory.emojiOf(article.getCategory())).append(' ')
                .append(directory.labelOf(article.getCategory()));
        }
        report.append("\n\n");
        appendImage(report, article);
        if (StringUtils.hasText(article.getSummary())) {
            report.append(article.getSummary().trim()).append("\n\n");
        }
        appendPoints(report, article);
        if (StringUtils.hasText(article.getReason())) {
            report.append("💡 **为什么值得读**：").append(article.getReason().trim())
                .append("\n\n");
        }
        if (StringUtils.hasText(article.getAiTake())) {
            report.append("✍️ **小编看法**：").append(article.getAiTake().trim())
                .append("\n\n");
        }
        if (article.getKeywords() != null && !article.getKeywords().isEmpty()) {
            report.append("🏷️ ").append(String.join("、", article.getKeywords()))
                .append("\n\n");
        }
        if (withHeading) {
            // 聚合版：篇与篇之间加分隔线；单篇版不需要
            report.append("---\n\n");
        }
    }

    private void appendDisclaimer(StringBuilder report, String dateText) {
        report.append("> 本文由 AI 辅助整理与点评，事实与数据以原文为准，原文版权归原作者所有。");
        if (StringUtils.hasText(dateText)) {
            report.append("整理于 ").append(dateText).append('。');
        }
        report.append("\n");
    }

    /**
     * 正文配图。
     *
     * <p>与站点日报不同：公众号这边<b>标题卡片也要放进正文</b>——卡片就是这篇的配图
     * （原文头图缺失或格式不符合微信要求时的顶替品）。公众号正文必须至少有一张图，
     * 卡片是 PNG、必定能转成微信能显示的地址，所以它必须留在正文里。</p>
     */
    private void appendImage(StringBuilder report, DigestArticleDTO article) {
        if (!StringUtils.hasText(article.getUploadedImage())) {
            return;
        }
        report.append("![").append(DigestRenderer.displayTitle(article)).append("](")
            .append(article.getUploadedImage()).append(")\n\n");
        if (article.isPlaceholderCover()) {
            report.append("*（原配图缺失或不符合微信要求，此图为按标题生成的卡片）*\n\n");
        }
    }

    private void appendPoints(StringBuilder report, DigestArticleDTO article) {
        var points = article.getPoints();
        if (points == null || points.isEmpty()) {
            return;
        }
        report.append("**核心信息**\n\n");
        for (var point : points) {
            if (StringUtils.hasText(point)) {
                report.append("- ").append(point.trim()).append('\n');
            }
        }
        report.append('\n');
    }
}
