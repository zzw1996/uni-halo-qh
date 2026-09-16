package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.PostClassifyCandidateDTO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 「旧文章归类」的提示词与响应解析。
 *
 * <p>和日报流水线里的 {@link DigestAiHelper} 是同一套约定的复用：
 * 主题仍是那六个固定 key，关键词仍然要求英文简短，返回仍然必须是纯 JSON——
 * 这样旧文章和新日报进的是同一个分类体系，不会出现两套口径。</p>
 *
 * <p>与评分提示词的关键差别：<b>这里不做筛选</b>。日报要从几百篇里挑出 5 篇，
 * 所以让 AI 打三个维度的分；旧文章归类只是「这篇属于哪一类」，
 * 输入里也不给 AI 任何可以拒绝的机会——否则它很容易把一半文章判成「不值得读」，
 * 而那些文章恰恰是站长希望被整理的。</p>
 */
public final class PostClassifyPromptHelper {

    /** 送进提示词的摘要长度：够判断主题，又不至于让一批提示词把上下文撑满 */
    private static final int DESCRIPTION_LIMIT = 200;

    /** 每篇最多接受几个关键词（与日报口径一致） */
    private static final int MAX_KEYWORDS = 4;

    private PostClassifyPromptHelper() {
    }

    /**
     * 组装批量判定提示词。
     *
     * <p>带上「已有分类/标签」是刻意的：站长已经手工整理过一部分文章，
     * 让 AI 看见这些线索能显著提高判定准确率（例如一篇文章已有「微信小程序」标签，
     * 它多半该进 engineering 而不是 tools）。</p>
     */
    public static String buildPrompt(List<PostClassifyCandidateDTO> batch) {
        var builder = new StringBuilder();
        for (var i = 0; i < batch.size(); i++) {
            var article = batch.get(i);
            if (i > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append("Index ").append(i).append(": ").append(article.getTitle());
            var existingCategories = join(article.getCategories());
            if (StringUtils.hasText(existingCategories)) {
                builder.append("\n已有分类: ").append(existingCategories);
            }
            var existingTags = join(article.getTags());
            if (StringUtils.hasText(existingTags)) {
                builder.append("\n已有标签: ").append(existingTags);
            }
            builder.append('\n').append(DigestAiHelper.truncateForPrompt(
                article.getExcerpt(), DESCRIPTION_LIMIT));
        }

        return """
            你是一个内容分类助手，正在帮站长把网站上的历史文章归入统一的主题体系。

            请为每一篇文章判定它属于哪个主题，并提取 2-4 个关键词。

            ## 主题（必须从以下选一个）
            - ai-ml: AI、机器学习、LLM、深度学习相关
            - security: 安全、隐私、漏洞、加密相关
            - engineering: 软件工程、架构、编程语言、系统设计
            - tools: 开发工具、开源项目、新发布的库/框架
            - opinion: 行业观点、个人思考、职业发展、文化评论
            - other: 确实不属于以上任何一类时才选

            ## 判定要求
            - 每篇文章都必须给出一个主题，不要跳过、不要返回 null
            - 优先看文章讲了什么，而不是作者是谁、发布在哪个平台
            - 一篇文章同时涉及多个主题时，选它主要在讲的那个

            ## 关键词提取
            提取 2-4 个最能代表文章主题的关键词（用英文，简短，如 "Rust", "LLM", "database"）

            ## 待归类文章

            """ + builder + """

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "results": [
                {
                  "index": 0,
                  "category": "engineering",
                  "keywords": ["Rust", "compiler"]
                }
              ]
            }""";
    }

    /**
     * 解析批量判定结果。
     *
     * <p>容错策略：逐条判定，<b>一条坏数据不拖垮整批</b>——某个 index 缺失、
     * 主题名不认识、关键词写成了字符串，都只让那一篇退化成「未能判定 / 无关键词」，
     * 其余照常可用。整批都解析不出来才抛异常（那是模型或通道的问题，该暴露出来）。</p>
     *
     * @param batchSize 本批文章数，用于挡掉越界的 index
     */
    public static List<PostClassifyVerdict> parse(String aiText, int batchSize) {
        var json = DigestAiHelper.parseJson(aiText);
        var results = json.path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("AI 返回内容里没有 results 数组");
        }
        var verdicts = new ArrayList<PostClassifyVerdict>(batchSize);
        for (var i = 0; i < batchSize; i++) {
            verdicts.add(new PostClassifyVerdict(i, "", List.of()));
        }
        for (var node : results) {
            var index = node.path("index").asInt(-1);
            if (index < 0 || index >= batchSize) {
                continue;
            }
            var existing = verdicts.get(index);
            verdicts.set(index, new PostClassifyVerdict(index,
                normalizeCategory(node.path("category").asText("")),
                normalizeKeywords(node.path("keywords"))));
        }
        return List.copyOf(verdicts);
    }

    /** 单篇判定结果：主题为空表示 AI 没给出有效主题（页面提示需人工处理）。 */
    public record PostClassifyVerdict(int index, String category, List<String> keywords) {

        public boolean hasCategory() {
            return StringUtils.hasText(category);
        }
    }

    /**
     * 主题归一化。
     *
     * <p>与 {@link DigestAiHelper#normalizeCategory} 不同：那里把无法识别的值回落到 {@code other}，
     * 因为日报总得有个分类来分组；这里返回空串，因为「AI 说了个不存在的类别」
     * 和「AI 判定它属于其它」是两回事——前者该让站长看见并手工处理，
     * 悄悄塞进 other 会让人以为已经归好类了。</p>
     */
    public static String normalizeCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return "";
        }
        var key = category.trim().toLowerCase(Locale.ROOT);
        return DigestAiHelper.VALID_CATEGORIES.contains(key) ? key : "";
    }

    /** 关键词清理：去空去重、去重时忽略大小写（保留首次出现的写法）、限长限量。 */
    static List<String> normalizeKeywords(JsonNode node) {
        var keywords = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        if (node != null && node.isArray()) {
            for (var item : node) {
                addKeyword(keywords, seen, item.asText(""));
            }
        } else if (node != null && node.isTextual()) {
            // 模型偶尔会把数组写成逗号分隔的字符串，按分隔符拆开而不是整串丢掉
            for (var part : node.asText("").split("[,，、;；]")) {
                addKeyword(keywords, seen, part);
            }
        }
        return List.copyOf(keywords);
    }

    private static void addKeyword(java.util.Set<String> keywords, java.util.Set<String> seen,
        String raw) {
        if (!StringUtils.hasText(raw) || keywords.size() >= MAX_KEYWORDS) {
            return;
        }
        var keyword = raw.trim();
        if (!seen.add(keyword.toLowerCase(Locale.ROOT))) {
            return;
        }
        keywords.add(keyword);
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(", ", values);
    }
}
