package pro.uhalo.uni.services;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestStatsDTO;
import pro.uhalo.uni.utils.DigestAiHelper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日报 Markdown 成稿。
 *
 * <p>板块顺序与原始脚本一致：今日看点（含背景铺垫）、今日必读 Top 3、数据概览
 * （Mermaid 图表 + ASCII 纯文本图 + 标签云）、按分类分组的文章列表。</p>
 */
@Component
public class DigestRenderer {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final int KEYWORD_CHART_LIMIT = 12;
    private static final int ASCII_CHART_LIMIT = 10;
    private static final int TAG_CLOUD_LIMIT = 20;
    private static final int ASCII_BAR_WIDTH = 20;

    private record CategoryMeta(String emoji, String label) {
    }

    private static final Map<String, CategoryMeta> CATEGORY_META = Map.of(
        "ai-ml", new CategoryMeta("🤖", "AI / ML"),
        "security", new CategoryMeta("🔒", "安全"),
        "engineering", new CategoryMeta("⚙️", "工程"),
        "tools", new CategoryMeta("🛠", "工具 / 开源"),
        "opinion", new CategoryMeta("💡", "观点 / 杂谈"),
        "other", new CategoryMeta("📝", "其他"));

    private static CategoryMeta metaOf(String category) {
        return CATEGORY_META.getOrDefault(category, CATEGORY_META.get("other"));
    }

    public String render(List<DigestArticleDTO> articles, DigestAiHelper.Overview overview,
        DigestStatsDTO stats, boolean mermaidEnabled, String footerNote, ZonedDateTime generatedAt) {        var dateStr = generatedAt.format(DATE_FORMAT);
        var avgWords = stats.getActualAvgWords() > 0 ? stats.getActualAvgWords() : stats.getWords();

        var report = new StringBuilder();
        report.append("# 📰 AI 博客每日精选 — ").append(dateStr).append("\n\n");
        report.append("> 来自 Karpathy 推荐的 ").append(stats.getTotalFeeds())
            .append(" 个顶级技术博客，AI 精选 Top ").append(articles.size())
            .append("，每篇约 ").append(avgWords).append(" 字\n\n");

        appendHighlights(report, overview);
        appendTopThree(report, articles);
        appendStats(report, articles, stats, mermaidEnabled);
        appendCategoryGroups(report, articles);
        appendFooter(report, articles, stats, generatedAt, footerNote);

        return report.toString();
    }

    // ── 单篇成稿 ──

    /**
     * 单篇精选文章的成稿。
     *
     * <p>「精选 N 篇就发 N 篇」模式下每篇文章单独成文：中文标题、原文出处、头图、
     * 概述、核心论点、推荐理由与关键词，末尾附一行来源说明。</p>
     */
    public String renderArticle(DigestArticleDTO article, DigestStatsDTO stats, String footerNote,
        ZonedDateTime generatedAt) {
        var meta = metaOf(article.getCategory());
        var report = new StringBuilder();
        report.append("# ").append(displayTitle(article)).append("\n\n");
        report.append('[').append(article.getTitle()).append("](").append(article.getLink())
            .append(") — ").append(article.getSourceName()).append(" · ")
            .append(humanizeTime(article.getPubDate())).append(" · ")
            .append(meta.emoji()).append(' ').append(meta.label()).append("\n\n");
        appendImage(report, article);
        if (StringUtils.hasText(article.getSummary())) {
            report.append("> ").append(article.getSummary()).append("\n\n");
        }
        appendPoints(report, article, true);
        if (StringUtils.hasText(article.getReason())) {
            report.append("💡 **为什么值得读**: ").append(article.getReason()).append("\n\n");
        }
        appendKeywords(report, article);

        report.append("---\n\n");
        // 文末不再输出「本文由…自动撰写／来源…」这类声明；署名行由配置决定（默认留空）
        if (StringUtils.hasText(footerNote)) {
            report.append("*").append(footerNote.trim()).append("*\n");
        }
        return report.toString();
    }

    // ── 今日看点 ──

    private void appendHighlights(StringBuilder report, DigestAiHelper.Overview overview) {
        if (overview == null || !StringUtils.hasText(overview.trend())) {
            return;
        }
        report.append("## 📝 今日看点\n\n");
        report.append(overview.trend()).append("\n\n");
        if (StringUtils.hasText(overview.background())) {
            report.append("### 🧭 背景铺垫\n\n");
            report.append(overview.background()).append("\n\n");
        }
        report.append("---\n\n");
    }

    // ── 今日必读 ──

    private void appendTopThree(StringBuilder report, List<DigestArticleDTO> articles) {
        if (articles.size() < 3) {
            return;
        }
        var medals = List.of("🥇", "🥈", "🥉");
        report.append("## 🏆 今日必读\n\n");
        for (var i = 0; i < 3; i++) {
            var article = articles.get(i);
            var meta = metaOf(article.getCategory());
            report.append(medals.get(i)).append(" **").append(displayTitle(article)).append("**\n\n");
            report.append('[').append(article.getTitle()).append("](").append(article.getLink())
                .append(") — ").append(article.getSourceName()).append(" · ")
                .append(humanizeTime(article.getPubDate())).append(" · ")
                .append(meta.emoji()).append(' ').append(meta.label()).append("\n\n");
            appendImage(report, article);
            if (StringUtils.hasText(article.getSummary())) {
                report.append("> ").append(article.getSummary()).append("\n\n");
            }
            appendPoints(report, article, true);
            if (StringUtils.hasText(article.getReason())) {
                report.append("💡 **为什么值得读**: ").append(article.getReason()).append("\n\n");
            }
            appendKeywords(report, article);
        }
        report.append("---\n\n");
    }

    // ── 数据概览 ──

    private void appendStats(StringBuilder report, List<DigestArticleDTO> articles,
        DigestStatsDTO stats, boolean mermaidEnabled) {
        report.append("## 📊 数据概览\n\n");
        report.append("| 扫描源 | 抓取文章 | 时间范围 | 精选 |\n");
        report.append("|:---:|:---:|:---:|:---:|\n");
        report.append("| ").append(stats.getSuccessFeeds()).append('/').append(stats.getTotalFeeds())
            .append(" | ").append(stats.getTotalArticles()).append(" 篇 → ")
            .append(stats.getFilteredArticles()).append(" 篇 | ")
            .append(stats.getHours()).append("h | **").append(articles.size()).append(" 篇** |\n\n");

        if (mermaidEnabled) {
            var pieChart = categoryPieChart(articles);
            if (StringUtils.hasText(pieChart)) {
                report.append("### 分类分布\n\n").append(pieChart);
            }
            var barChart = keywordBarChart(articles);
            if (StringUtils.hasText(barChart)) {
                report.append("### 高频关键词\n\n").append(barChart);
            }
        }

        var asciiChart = asciiBarChart(articles);
        if (StringUtils.hasText(asciiChart)) {
            report.append("<details>\n<summary>📈 纯文本关键词图（终端友好）</summary>\n\n")
                .append(asciiChart).append("</details>\n\n");
        }

        var tagCloud = tagCloud(articles);
        if (StringUtils.hasText(tagCloud)) {
            report.append("### 🏷️ 话题标签\n\n").append(tagCloud).append("\n\n");
        }

        report.append("---\n\n");
    }

    // ── 分类文章列表 ──

    private void appendCategoryGroups(StringBuilder report, List<DigestArticleDTO> articles) {
        var groups = new LinkedHashMap<String, List<DigestArticleDTO>>();
        for (var article : articles) {
            groups.computeIfAbsent(article.getCategory(), key -> new ArrayList<>()).add(article);
        }
        var sorted = groups.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, List<DigestArticleDTO>> entry)
                -> entry.getValue().size()).reversed())
            .toList();

        var globalIndex = 0;
        for (var entry : sorted) {
            var meta = metaOf(entry.getKey());
            report.append("## ").append(meta.emoji()).append(' ').append(meta.label()).append("\n\n");
            for (var article : entry.getValue()) {
                globalIndex++;
                report.append("### ").append(globalIndex).append(". ")
                    .append(displayTitle(article)).append("\n\n");
                report.append('[').append(article.getTitle()).append("](").append(article.getLink())
                    .append(") — **").append(article.getSourceName()).append("** · ")
                    .append(humanizeTime(article.getPubDate())).append(" · ⭐ ")
                    .append(article.getTotalScore()).append("/30\n\n");
                appendImage(report, article);
                if (StringUtils.hasText(article.getSummary())) {
                    report.append("> ").append(article.getSummary()).append("\n\n");
                }
                appendPoints(report, article, false);
                appendKeywords(report, article);
                report.append("---\n\n");
            }
        }
    }

    private void appendFooter(StringBuilder report, List<DigestArticleDTO> articles,
        DigestStatsDTO stats, ZonedDateTime generatedAt, String footerNote) {
        report.append("*生成于 ").append(generatedAt.format(DATE_FORMAT)).append(' ')
            .append(generatedAt.format(TIME_FORMAT))
            .append(" | 扫描 ").append(stats.getSuccessFeeds()).append(" 源 → 获取 ")
            .append(stats.getTotalArticles()).append(" 篇 → 精选 ")
            .append(articles.size()).append(" 篇*\n");
        report.append("*基于 [Hacker News Popularity Contest 2025]"
            + "(https://refactoringenglish.com/tools/hn-popularity/) RSS 源列表，"
            + "由 [Andrej Karpathy](https://x.com/karpathy) 推荐*\n");
        if (StringUtils.hasText(footerNote)) {
            report.append("*").append(footerNote.trim()).append("*\n");
        }
    }

    private void appendImage(StringBuilder report, DigestArticleDTO article) {
        if (!StringUtils.hasText(article.getUploadedImage())) {
            return;
        }
        report.append("![").append(displayTitle(article)).append("](")
            .append(article.getUploadedImage()).append(")\n\n");
    }

    private void appendPoints(StringBuilder report, DigestArticleDTO article, boolean withHeading) {
        var points = article.getPoints();
        if (points == null || points.isEmpty()) {
            return;
        }
        if (withHeading) {
            report.append("**核心论点**\n\n");
        }
        for (var point : points) {
            report.append("- ").append(point).append('\n');
        }
        report.append('\n');
    }

    private void appendKeywords(StringBuilder report, DigestArticleDTO article) {
        var keywords = article.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        report.append("🏷️ ").append(String.join(", ", keywords)).append("\n\n");
    }

    /** 展示用标题：有中文译名时优先用中文 */
    public static String displayTitle(DigestArticleDTO article) {
        return StringUtils.hasText(article.getTitleZh()) ? article.getTitleZh() : article.getTitle();
    }

    /** 相对时间：一小时内给到分钟，一周内给到天，再早直接给日期 */
    static String humanizeTime(Instant pubDate) {
        if (pubDate == null) {
            return "未知时间";
        }
        var diff = Duration.between(pubDate, Instant.now());
        var minutes = diff.toMinutes();
        var hours = diff.toHours();
        var days = diff.toDays();
        if (minutes < 60) {
            return Math.max(0, minutes) + " 分钟前";
        }
        if (hours < 24) {
            return hours + " 小时前";
        }
        if (days < 7) {
            return days + " 天前";
        }
        return ZonedDateTime.ofInstant(pubDate, java.time.ZoneOffset.UTC).format(DATE_FORMAT);
    }

    // ── 图表 ──

    private Map<String, Integer> keywordCounts(List<DigestArticleDTO> articles) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var article : articles) {
            if (article.getKeywords() == null) {
                continue;
            }
            for (var keyword : article.getKeywords()) {
                if (!StringUtils.hasText(keyword)) {
                    continue;
                }
                var normalized = keyword.toLowerCase(Locale.ROOT);
                counts.merge(normalized, 1, Integer::sum);
            }
        }
        return sortByCountDesc(counts);
    }

    private static Map<String, Integer> sortByCountDesc(Map<String, Integer> counts) {
        var sorted = new LinkedHashMap<String, Integer>();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    String categoryPieChart(List<DigestArticleDTO> articles) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var article : articles) {
            counts.merge(article.getCategory(), 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder("```mermaid\npie showData\n    title \"文章分类分布\"\n");
        sortByCountDesc(counts).forEach((category, count) -> {
            var meta = metaOf(category);
            builder.append("    \"").append(meta.emoji()).append(' ').append(meta.label())
                .append("\" : ").append(count).append('\n');
        });
        return builder.append("```\n").toString();
    }

    String keywordBarChart(List<DigestArticleDTO> articles) {
        var counts = keywordCounts(articles);
        var sorted = counts.entrySet().stream().limit(KEYWORD_CHART_LIMIT).toList();
        if (sorted.isEmpty()) {
            return "";
        }
        var labels = sorted.stream().map(entry -> "\"" + entry.getKey() + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        var values = sorted.stream().map(entry -> String.valueOf(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(", "));
        var maxValue = sorted.get(0).getValue();

        return "```mermaid\n"
            + "xychart-beta horizontal\n"
            + "    title \"高频关键词\"\n"
            + "    x-axis [" + labels + "]\n"
            + "    y-axis \"出现次数\" 0 --> " + (maxValue + 2) + "\n"
            + "    bar [" + values + "]\n"
            + "```\n";
    }

    String asciiBarChart(List<DigestArticleDTO> articles) {
        var counts = keywordCounts(articles);
        var sorted = counts.entrySet().stream().limit(ASCII_CHART_LIMIT).toList();
        if (sorted.isEmpty()) {
            return "";
        }
        var maxValue = sorted.get(0).getValue();
        var maxLabelLength = sorted.stream().mapToInt(entry -> entry.getKey().length()).max().orElse(0);

        var builder = new StringBuilder("```\n");
        for (var entry : sorted) {
            var barLength = Math.max(1, Math.round((float) entry.getValue() / maxValue * ASCII_BAR_WIDTH));
            var bar = "█".repeat(barLength) + "░".repeat(Math.max(0, ASCII_BAR_WIDTH - barLength));
            builder.append(padEnd(entry.getKey(), maxLabelLength))
                .append(" │ ").append(bar).append(' ').append(entry.getValue()).append('\n');
        }
        return builder.append("```\n").toString();
    }

    String tagCloud(List<DigestArticleDTO> articles) {
        var counts = keywordCounts(articles);
        var sorted = counts.entrySet().stream().limit(TAG_CLOUD_LIMIT).toList();
        if (sorted.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder();
        for (var i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                builder.append(" · ");
            }
            var entry = sorted.get(i);
            if (i < 3) {
                builder.append("**").append(entry.getKey()).append("**(")
                    .append(entry.getValue()).append(')');
            } else {
                builder.append(entry.getKey()).append('(').append(entry.getValue()).append(')');
            }
        }
        return builder.toString();
    }

    private static String padEnd(String value, int length) {
        if (value.length() >= length) {
            return value;
        }
        return value + " ".repeat(length - value.length());
    }
}
