package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestPublicArticleDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 日报偏好匹配：把订阅偏好应用到一批日报文章上。
 *
 * <p>纯函数、不依赖 Halo 运行时，因此每条规则都能用单测钉住。这类「配了偏好却看不到东西」
 * 的功能最怕两种情况：匹配条件含糊（用户不知道为什么会/不会出现某篇），
 * 以及结果顺序随机（同样的偏好每次刷出来的顺序都不一样）。因此这里做了两件事：</p>
 * <ul>
 *   <li>每篇命中都给出<b>可读的命中原因</b>（分类匹配 / 关键词命中 / 来源关注），
 *       前端直接展示，用户能立刻判断该收紧还是放宽；</li>
 *   <li>排序是<b>确定</b>的：同一份日报内按命中强度倒序、同强度按标题升序——
 *       同一份数据任何时候算出来都一样。日报之间不重排（调用方按日期倒序传入），
 *       因此「最新的在最上面」这个直觉不会被打破。</li>
 * </ul>
 *
 * <p>匹配口径是「或」：命中分类、命中任一关键词、或来自关注的来源，都算命中。
 * 用「与」会让条件稍微一多就什么都不剩，而日报每天只有几篇，用户很容易配到空。</p>
 */
public final class DigestPreferenceMatcher {

    /** 命中强度权重：分类是用户最明确的选择，权重最高 */
    private static final int WEIGHT_CATEGORY = 3;

    private static final int WEIGHT_SOURCE = 2;

    /** 关键词命中数量上限，避免一个宽泛词把所有东西都排到前面 */
    private static final int KEYWORD_HIT_CAP = 3;

    private DigestPreferenceMatcher() {
    }

    /** 是否设过筛选条件；没设过时「我的订阅」应当等于「全部」，而不是空 */
    public static boolean hasPreferences(DigestSubscriptionDTO subscription) {
        return subscription != null
            && (!isEmpty(subscription.getFollowedCategories())
                || !isEmpty(subscription.getKeywords())
                || !isEmpty(subscription.getSources()));
    }

    /**
     * 应用偏好并排序。
     *
     * <p>命中的文章会被写入 {@code matchedReason}；未命中不返回。
     * 调用方传入的是刚反序列化出来的对象，因此就地写入不会影响别处。</p>
     */
    public static List<DigestPublicArticleDTO> apply(List<DigestPublicArticleDTO> articles,
        DigestSubscriptionDTO subscription) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        if (!hasPreferences(subscription)) {
            return List.copyOf(articles);
        }
        // 分类命中看「关注的主题分类」：postCategories 与旧的 AI 分类字段同源
        // （自动打标把 AI key 变成了站点分类名），合并起来才不会漏掉老订阅
        var categories = lowerSet(subscription.getFollowedCategories());
        var keywords = lowerSet(subscription.getKeywords());
        var sources = lowerSet(subscription.getSources());

        var scored = new ArrayList<Scored>();
        for (var article : articles) {
            var reason = new ArrayList<String>();
            var score = 0;

            var category = lower(article.getCategory());
            if (!category.isEmpty() && categories.contains(category)) {
                score += WEIGHT_CATEGORY;
                reason.add("分类匹配 · " + label(article));
            }

            var hits = new ArrayList<String>();
            for (var keyword : article.getKeywords() == null
                ? List.<String>of() : article.getKeywords()) {
                var normalized = lower(keyword);
                if (!normalized.isEmpty() && keywords.contains(normalized)) {
                    hits.add(keyword.trim());
                }
            }
            if (!hits.isEmpty()) {
                score += Math.min(hits.size(), KEYWORD_HIT_CAP);
                reason.add("关键词 · " + String.join("、", hits));
            }

            var source = lower(article.getSourceName());
            if (!source.isEmpty() && sources.contains(source)) {
                score += WEIGHT_SOURCE;
                reason.add("关注来源 · " + article.getSourceName().trim());
            }

            if (score <= 0) {
                continue;
            }
            article.setMatchedReason(String.join(" · ", reason));
            scored.add(new Scored(article, score));
        }

        scored.sort(Comparator
            .comparingInt(Scored::score).reversed()
            .thenComparing(item -> item.article().getTitle(), Comparator.nullsLast(
                Comparator.naturalOrder())));
        return scored.stream().map(Scored::article).toList();
    }

    private static String label(DigestPublicArticleDTO article) {
        return StringUtils.hasText(article.getCategoryLabel())
            ? article.getCategoryLabel() : article.getCategory();
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.stream().noneMatch(StringUtils::hasText);
    }

    private static Set<String> lowerSet(List<String> values) {
        var set = new LinkedHashSet<String>();
        if (values == null) {
            return set;
        }
        for (var value : values) {
            var normalized = lower(value);
            if (!normalized.isEmpty()) {
                set.add(normalized);
            }
        }
        return set;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Scored(DigestPublicArticleDTO article, int score) {
    }
}
