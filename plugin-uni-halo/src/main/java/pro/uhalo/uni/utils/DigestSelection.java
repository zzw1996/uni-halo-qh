package pro.uhalo.uni.utils;

import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 精选：把「有没有封面图」变成可配置的硬性条件，并保证每个分类都有份额。
 *
 * <p>改造前精选只有一条规则——全局按分数取前 N 名，分类只影响正文分组，于是
 * 「某个分类今天一篇都没有」是常态。现在分两轮选：</p>
 * <ol>
 *   <li><b>保底轮</b>：分类清单里的每个分类先各取 {@code minPerCategory} 篇
 *       （兜底分类「其他」除外，它是收集桶，为它专门抓一轮没有意义）；</li>
 *   <li><b>填充轮</b>：剩下的名额按统一排序补满 {@code limit}（精选篇数），
 *       受 {@code maxPerCategory} 约束。</li>
 * </ol>
 *
 * <p>排序口径：开了「只发布带封面图」时有图的排前面（保证保底名额尽量落在带图的文章上），
 * 其次是本期抓到的排在补抓回来的前面，最后才是分数。补位（{@code supplementary}）文章
 * 因此不会挤掉时间窗内正常抓到的同分文章。</p>
 */
public final class DigestSelection {

    private DigestSelection() {
    }

    /** 已解析到头图地址（抓取阶段：RSS 内嵌图 / og:image） */
    public static boolean hasLeadImage(DigestArticleDTO article) {
        return article != null && DigestTextUtils.isUsableImageUrl(article.getImageUrl());
    }

    /** 已成功上传到附件库（发布阶段才成立） */
    public static boolean hasUploadedImage(DigestArticleDTO article) {
        return article != null && DigestTextUtils.isUsableImageUrl(article.getUploadedImage());
    }

    /** 只保留已成功上传封面图的文章（发布前最后一道门槛） */
    public static List<DigestArticleDTO> keepUploaded(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        return articles.stream().filter(DigestSelection::hasUploadedImage).toList();
    }

    /** 被「必须有封面图」挡掉的数量，用于日志 */
    public static int missingImageCount(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return 0;
        }
        return (int) articles.stream().filter(article -> !hasLeadImage(article)).count();
    }

    /**
     * 按分类配额精选。
     *
     * @param candidates     候选池（已按需解析过头图）
     * @param catalog        分类清单
     * @param minPerCategory 每个分类至少几篇（0 = 不做保底，等于改造前的全局排名）
     * @param maxPerCategory 每个分类最多几篇（0 = 不限；只约束填充轮，保底轮不受它限制）
     * @param limit          精选篇数上限；保底名额优先于它，因此结果可能多于它
     * @param requireImage   开了「只发布带封面图」时，有图的文章排在前面
     */
    public static CategorySelection selectByCategory(List<DigestArticleDTO> candidates,
        DigestCategoryCatalog catalog, int minPerCategory, int maxPerCategory, int limit,
        boolean requireImage) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var quota = Math.max(0, minPerCategory);
        var cap = Math.max(0, maxPerCategory);
        var size = Math.max(0, limit);
        if (candidates == null || candidates.isEmpty()) {
            return CategorySelection.of(List.of(), Map.of(), directory, quota);
        }

        var order = order(requireImage);
        var groups = new LinkedHashMap<String, List<DigestArticleDTO>>();
        for (var article : candidates) {
            groups.computeIfAbsent(categoryKeyOf(article), key -> new ArrayList<>()).add(article);
        }
        groups.values().forEach(group -> group.sort(order));

        var selected = new ArrayList<DigestArticleDTO>();
        var taken = new LinkedHashSet<DigestArticleDTO>();
        var counts = new LinkedHashMap<String, Integer>();

        // 保底轮：每个分类各取 quota 篇（兜底分类不参与）
        if (quota > 0) {
            for (var key : directory.keys()) {
                if (key.equals(directory.fallbackKey())) {
                    continue;
                }
                var group = groups.get(key);
                if (group == null) {
                    continue;
                }
                var take = 0;
                for (var article : group) {
                    if (take >= quota) {
                        break;
                    }
                    if (taken.add(article)) {
                        selected.add(article);
                        take++;
                    }
                }
                if (take > 0) {
                    counts.merge(key, take, Integer::sum);
                }
            }
        }

        // 填充轮：剩余名额按统一排序补满
        if (selected.size() < size) {
            var all = new ArrayList<>(candidates);
            all.sort(order);
            for (var article : all) {
                if (selected.size() >= size) {
                    break;
                }
                var key = categoryKeyOf(article);
                if (cap > 0 && counts.getOrDefault(key, 0) >= cap) {
                    continue;
                }
                if (taken.add(article)) {
                    selected.add(article);
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }

        // 输出统一按分数降序：正文的「今日必读」与逐篇发布顺序都依赖它
        selected.sort(Comparator
            .comparingInt(DigestArticleDTO::getTotalScore).reversed()
            .thenComparing(DigestArticleDTO::getTitle,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return CategorySelection.of(selected, counts, directory, quota);
    }

    /**
     * 候选池排序：有图优先（仅当开了「只发布带封面图」）→ 本期抓到的优先 → 分数高的优先。
     */
    private static Comparator<DigestArticleDTO> order(boolean requireImage) {
        return Comparator
            .comparing((DigestArticleDTO article) -> requireImage && !hasLeadImage(article))
            .thenComparing(DigestArticleDTO::isSupplementary)
            .thenComparing(Comparator.comparingInt(DigestArticleDTO::getTotalScore).reversed())
            .thenComparing(DigestArticleDTO::getTitle,
                Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /** 分类 key：空分类（策略为「不打分类」时）归到一个空串分组，不参与保底 */
    private static String categoryKeyOf(DigestArticleDTO article) {
        var category = article.getCategory();
        return category == null ? "" : category.trim();
    }

    /** 配额精选结果 */
    public record CategorySelection(List<DigestArticleDTO> articles,
                                    Map<String, Integer> categoryCounts,
                                    List<String> requiredCategories,
                                    List<String> missingCategories,
                                    int minPerCategory) {

        static CategorySelection of(List<DigestArticleDTO> articles,
            Map<String, Integer> counts, DigestCategoryCatalog catalog, int quota) {
            var required = new ArrayList<String>();
            var missing = new ArrayList<String>();
            if (quota > 0) {
                for (var key : catalog.keys()) {
                    if (key.equals(catalog.fallbackKey())) {
                        continue;
                    }
                    required.add(key);
                    if (counts.getOrDefault(key, 0) < quota) {
                        missing.add(key);
                    }
                }
            }
            return new CategorySelection(List.copyOf(articles), Map.copyOf(counts),
                List.copyOf(required), List.copyOf(missing), quota);
        }

        public int countOf(String category) {
            return categoryCounts.getOrDefault(category, 0);
        }

        /** 还有分类没凑够保底篇数（补抓一轮仍可能凑不齐） */
        public boolean hasMissingCategories() {
            return !missingCategories.isEmpty();
        }

        /** 是否开启了「每个分类至少几篇」 */
        public boolean quotaEnabled() {
            return minPerCategory > 0;
        }
    }
}
