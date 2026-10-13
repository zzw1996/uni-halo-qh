package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.List;

/**
 * AI 提示词与响应解析。
 *
 * <p>提示词一比一沿用 ac-daily-digest 脚本里的版本：评分维度、分类标签、篇幅约束与
 * 「不要用英文半角双引号」这类防 JSON 破损的要求都是调过的，改了会直接影响成稿质量。</p>
 */
public final class DigestAiHelper {

    /**
     * 内置分类清单（改造前的六类）。
     *
     * <p>现在分类清单由站长在后台上配置、运行期统一走 {@link DigestCategoryCatalog}；
     * 这里保留常量只为兼容旧调用与「清单留空时的兜底」判断，新代码不要再直接用它。</p>
     */
    public static final List<String> VALID_CATEGORIES =
        List.of("ai-ml", "security", "engineering", "tools", "opinion", "other");

    private static final int SCORING_DESCRIPTION_LIMIT = 300;
    /** 成稿素材：足够长的上下文才能写出观点而不是复述摘要；与评分侧分开限制，评分成本不变 */
    private static final int SUMMARY_DESCRIPTION_LIMIT = 2000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DigestAiHelper() {
    }

    /**
     * 这次回复是否被输出长度上限截断（{@code finish_reason=length}）。
     *
     * <p>成稿要的是长 JSON，撞上 {@code max_tokens} 时正文会被砍在半截——表现就是
     * 「模型只回了一半」（解析失败、只解析出前几篇、或字段缺一半）。拿到这个信号
     * 就能在日志里写清原因，并让调用方拆小批次重试，而不是把它当成「模型不听话」。</p>
     */
    public static boolean isOutputTruncated(pro.uhalo.uni.dto.DigestAiReplyDTO reply) {
        return reply != null && "length".equalsIgnoreCase(reply.finishReason());
    }

    // ── 评分 ──

    /**
     * 公众号评分提示词：在原版基础上追加「备选方向」要求。
     *
     * <p><b>原版一个字都不改</b>（那份提示词是调过的，改了会影响日报成稿质量），
     * 这里只在渲染结果上做两处追加：① 要求模型在「同时明显适合另一个方向」时给出
     * {@code altCategory}（公众号的「方向保底」靠它补位，不额外花调用）；
     * ② JSON 示例里的示例分类换成清单里的兜底项、并补上 altCategory 字段——
     * 原示例固定用清单第一项，会把模型往第一个方向带。</p>
     */
    public static String buildScoringPromptForMp(List<DigestArticleDTO> batch, int startIndex,
        DigestCategoryCatalog catalog) {
        var base = buildScoringPrompt(batch, startIndex, catalog);
        var sample = catalog.sampleKey();
        var fallbackKey = StringUtils.hasText(catalog.fallbackKey())
            ? catalog.fallbackKey() : sample;
        return base
            .replace("\"category\": \"" + sample + "\",",
                "\"category\": \"" + fallbackKey + "\",\n         \"altCategory\": \"\",")
            .replace("## 分类标签（必须从以下选一个）",
                "## 分类标签（必须从以下选一个；与所有标签都不相关的选兜底那一项）")
            .replace("并为每篇文章分配一个分类标签和提取 2-4 个关键词。",
                "并为每篇文章分配一个分类标签和提取 2-4 个关键词。\n"
                    + "\n## 备选方向（仅当确实合适时才给）\n"
                    + "如果这篇文章同时明显适合另一个方向（必须来自下面同一份清单），"
                    + "把那个方向的 key 填进 altCategory；不确定、只是沾点边、或找不到合适的，"
                    + "就留空字符串——宁缺毋滥。");
    }

    public static String buildScoringPrompt(List<DigestArticleDTO> batch, int startIndex) {
        return buildScoringPrompt(batch, startIndex, DigestCategoryCatalog.defaultCatalog());
    }

    /**
     * 评分提示词。
     *
     * <p>分类清单来自 {@code catalog}：清单里有几类就只让 AI 选几类（这是「站长可配分类」的落点），
     * JSON 示例里的 category 也取清单第一项，保证示例一定是合法值。
     * 拼接处行尾的 {@code \} 是文本块的续行转义，去掉它会让示例 JSON 里多出一个换行、断成两行。</p>
     */
    public static String buildScoringPrompt(List<DigestArticleDTO> batch, int startIndex,
                                            DigestCategoryCatalog catalog) {
        var builder = new StringBuilder();
        for (var i = 0; i < batch.size(); i++) {
            var article = batch.get(i);
            if (i > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append("Index ").append(startIndex + i)
                .append(": [").append(article.getSourceName()).append("] ")
                .append(article.getTitle()).append('\n')
                .append(truncate(article.getDescription(), SCORING_DESCRIPTION_LIMIT));
        }
        var articlesList = builder.toString();

        return """
            你是一个技术内容策展人，正在为一份面向技术爱好者的每日精选摘要筛选文章。

            请对以下文章进行三个维度的评分（1-10 整数，10 分最高），并为每篇文章分配一个分类标签和提取 2-4 个关键词。

            ## 评分维度

            ### 1. 相关性 (relevance) - 对技术/编程/AI/互联网从业者的价值
            - 10: 所有技术人都应该知道的重大事件/突破
            - 7-9: 对大部分技术从业者有价值
            - 4-6: 对特定技术领域有价值
            - 1-3: 与技术行业关联不大

            ### 2. 质量 (quality) - 文章本身的深度和写作质量
            - 10: 深度分析，原创洞见，引用丰富
            - 7-9: 有深度，观点独到
            - 4-6: 信息准确，表达清晰
            - 1-3: 浅尝辄止或纯转述

            ### 3. 时效性 (timeliness) - 当前是否值得阅读
            - 10: 正在发生的重大事件/刚发布的重要工具
            - 7-9: 近期热点相关
            - 4-6: 常青内容，不过时
            - 1-3: 过时或无时效价值

            ## 分类标签（必须从以下选一个）
            """ + catalog.promptSection() + """
            ## 关键词提取
            提取 2-4 个最能代表文章主题的关键词（用英文，简短，如 "Rust", "LLM", "database", "performance"）

            ## 待评分文章

            """ + articlesList + """

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "results": [
                {
                  "index": 0,
                  "relevance": 8,
                  "quality": 7,
                  "timeliness": 9,
                  "category": "\
            """ + catalog.sampleKey() + """
            ",
                  "keywords": ["Rust", "compiler", "performance"]
                }
              ]
            }""";
    }

    // ── 摘要 ──

    public static String buildSummaryPrompt(List<DigestArticleDTO> batch, int startIndex,
        String lang, int words) {
        var builder = new StringBuilder();
        for (var i = 0; i < batch.size(); i++) {
            var article = batch.get(i);
            if (i > 0) {
                builder.append("\n\n---\n\n");
            }
            builder.append("Index ").append(startIndex + i)
                .append(": [").append(article.getSourceName()).append("] ")
                .append(article.getTitle()).append('\n')
                .append("URL: ").append(article.getLink()).append('\n')
                .append(truncate(article.getDescription(), SUMMARY_DESCRIPTION_LIMIT));
        }
        var articlesList = builder.toString();

        var langInstruction = "en".equals(lang)
            ? "Write the summary, key points, AI take, and reason in English."
            : "请用中文撰写概述、核心信息、小编看法和推荐理由。如果原文是英文，请翻译为中文。标题翻译也用中文。";

        var overviewWords = Math.round(words * 0.35f);
        var overviewFloor = Math.round(overviewWords * 0.85f);
        var pointWords = Math.max(25, Math.round((words * 0.35f) / 3));
        var pointFloor = Math.round(pointWords * 0.85f);
        var takeWords = Math.max(80, Math.round(words * 0.3f));
        var takeFloor = Math.round(takeWords * 0.85f);
        var totalFloor = overviewFloor + pointFloor * 3 + takeFloor;

        return """
            你是一个技术内容写作者，正在为博客撰写每日精选。请为以下文章完成五件事：

            1. **中文标题** (titleZh): 将英文标题翻译成自然的中文。如果原标题已经是中文则保持不变。
            2. **概述** (summary): %d 字左右的客观概述段落（不少于 %d 字）。包含：
               - 文章讨论的核心问题或主题（1 句）
               - 关键事实、数据或方案（1-2 句）
               - 作者的核心结论（1 句）
            3. **核心信息** (points): 2-4 条分点，每条约 %d 字（不少于 %d 字）。用你自己的话重新组织文章里最关键的事实、数据、方案名称或指标，每条自成一句完整的话。
            4. **小编看法** (aiTake): %d 字左右的评论段落（不少于 %d 字）。以博客小编的身份给出你自己视角的评价与延伸：这件事为什么重要、有什么被忽略的代价或机会、对读者或行业意味着什么、下一步值得观察什么。这是你不可替代的价值——必须是原创观点，与概述里的事实严格区分。这段会以「小编看法」的署名展示，行文里不要自称 AI，也不要出现「AI」「模型」「生成式」这类字样。
            5. **推荐理由** (reason): 1 句话说明"为什么值得读"，区别于概述（概述说"是什么"，推荐理由说"为什么"）。

            %s

            写作要求：
            - **版权红线**：概述与核心信息必须是彻底重新组织过的转述——禁止照搬原文的句子或段落（连续 10 字以上的原文照抄一律不允许），只保留事实与数据，语言全部重写；小编看法则必须完全脱离原文语言，只基于事实给出你的观点
            - **硬性要求**：每篇文章的「概述 + 核心信息 + 小编看法」合计不少于 %d 字。字数不够时，补充更多技术细节、具体数据、方案对比、实现约束或行业背景，不要重复已有句子，也不要用空话凑数
            - 直接说重点，不要用"本文讨论了..."、"这篇文章介绍了..."这种开头
            - 保留关键数字和指标（如性能提升百分比、用户数、版本号、CVE 编号等）
            - 如果文章涉及对比或选型，要点出比较对象和结论
            - 概述、分点与小编看法三者不要重复：概述给客观事实，分点给硬信息，小编看法给观点
            - 正文中不要使用英文半角双引号，需要引号时用中文引号「」或“”（避免破坏 JSON 结构）

            ## 待撰写文章

            """.formatted(overviewWords, overviewFloor, pointWords, pointFloor,
            takeWords, takeFloor, langInstruction, totalFloor) + articlesList + """

            请严格按 JSON 格式返回：
            {
              "results": [
                {
                  "index": 0,
                  "titleZh": "中文翻译的标题",
                  "summary": "概述段落...",
                  "points": ["核心信息 1", "核心信息 2"],
                  "aiTake": "小编看法段落...",
                  "reason": "推荐理由..."
                }
              ]
            }""";
    }

    // ── 趋势总结 ──

    public static String buildOverviewPrompt(List<DigestArticleDTO> articles, String lang, int words) {
        var builder = new StringBuilder();
        var limit = Math.min(10, articles.size());
        for (var i = 0; i < limit; i++) {
            var article = articles.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(i + 1).append(". [").append(article.getCategory()).append("] ")
                .append(StringUtils.hasText(article.getTitleZh())
                    ? article.getTitleZh() : article.getTitle())
                .append(" — ")
                .append(truncate(article.getSummary(), 200));
        }
        var articleList = builder.toString();

        var langNote = "en".equals(lang) ? "Write in English." : "用中文回答。";
        var sectionWords = Math.min(800, Math.max(200, words));
        var sectionFloor = Math.round(sectionWords * 0.85f);

        return """
            根据以下今日精选技术文章列表，为一份博客日报写开头的两个部分。
            要求：
            - 看点：%d 字左右（不少于 %d 字），提炼今天技术圈的 2-3 个主要趋势或话题，不要逐篇列举，要做宏观归纳，风格简洁有力，像新闻导语
            - 背景：%d 字左右（不少于 %d 字），交代这些话题为什么会集中出现——涉及的行业脉络、外部事件或长期趋势，帮读者建立语境；不要逐篇列举，也不要重复看点已说的内容
            - 两部分都写成连贯段落，不要用列表；字数不足时补充具体事件、时间线、数据或案例，不要用空话凑数
            - 正文中不要使用英文半角双引号，需要引号时用中文引号「」或“”
            %s

            文章列表：
            """.formatted(sectionWords, sectionFloor, sectionWords, sectionFloor, langNote)
            + articleList + """

            请严格按下面的纯文本格式返回（不要用 JSON，不要 markdown 标题，不要任何额外说明）：
            【看点】
            （今日趋势总结，直接开始写）
            【背景】
            （背景铺垫，直接开始写）""";
    }

    // ── 响应解析 ──

    /**
     * 解析 AI 返回的 JSON，模型偶尔会套一层 markdown 代码块或在前后加说明文字。
     *
     * <p>思考型模型经部分网关（vLLM、Ollama、一些中转站）会把思考过程以
     * {@code <think>…</think>} 混进 content——先剥掉再解析，否则「找第一个 {」
     * 很可能落进思考文字里。没有闭合标签（被截断）时剥不掉，交给后面的兜底。</p>
     */
    public static JsonNode parseJson(String text) {
        var jsonText = text == null ? "" : text.trim();
        jsonText = jsonText.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (jsonText.startsWith("```")) {
            jsonText = jsonText.replaceFirst("^```(?:json)?\\s*\\n?", "")
                .replaceFirst("\\n?```\\s*$", "");
        }
        try {
            return OBJECT_MAPPER.readTree(jsonText);
        } catch (Exception original) {
            var start = jsonText.indexOf('{');
            var end = jsonText.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return OBJECT_MAPPER.readTree(jsonText.substring(start, end + 1));
                } catch (Exception ignored) {
                    // 落到下面的统一报错
                }
            }
            throw new IllegalStateException("AI 返回内容不是合法 JSON：" + snippet(jsonText), original);
        }
    }

    public static int clampScore(int value) {
        return Math.min(10, Math.max(1, value));
    }

    public static String normalizeCategory(String category) {
        return normalizeCategory(category, DigestCategoryCatalog.defaultCatalog(),
            DigestConfigHelper.UNKNOWN_CATEGORY_OTHER);
    }

    /**
     * 把 AI 给出的分类归一到清单内的 key。
     *
     * <p>清单外的值按 {@code unknownCategoryPolicy} 处理：{@code other} → 落到清单里的
     * 「其他」（清单没有它就等于不打分类）；{@code drop} → 返回空串。</p>
     */
    public static String normalizeCategory(String category, DigestCategoryCatalog catalog,
                                            String unknownCategoryPolicy) {
        var key = catalog.normalize(category);
        if (!key.isEmpty()) {
            return key;
        }
        // 空值 / 清单外：按策略兜底。注意不能用 VALID_CATEGORIES.contains(null)（不可变列表会抛 NPE），
        // 所以统一走 catalog.normalize（内部先规范化再查表）。
        return DigestConfigHelper.UNKNOWN_CATEGORY_OTHER.equalsIgnoreCase(unknownCategoryPolicy)
            ? catalog.fallbackKey() : "";
    }

    /**
     * 概览用【看点】/【背景】分隔的纯文本返回，避免长段中文在 JSON 里被引号、换行破坏结构；
     * 解析不到分隔符时退化为整段当看点，绝不把原始 JSON 写进报告。
     */
    public static Overview parseOverviewResponse(String text) {
        var cleaned = text == null ? "" : text.trim();
        var trend = "";
        var background = "";

        var trendIndex = cleaned.indexOf("【看点】");
        var backgroundIndex = cleaned.indexOf("【背景】");
        if (trendIndex >= 0) {
            var trendEnd = backgroundIndex > trendIndex ? backgroundIndex : cleaned.length();
            trend = cleaned.substring(trendIndex + "【看点】".length(), trendEnd).trim();
        }
        if (backgroundIndex >= 0) {
            background = cleaned.substring(backgroundIndex + "【背景】".length()).trim();
        }
        if (StringUtils.hasText(trend) || StringUtils.hasText(background)) {
            return new Overview(trend, background);
        }

        // 模型这次仍按 JSON 返回（但结构破损）时，把两个字段捞回来
        var jsonTrend = extractJsonField(cleaned, "trend");
        var jsonBackground = extractJsonField(cleaned, "background");
        if (StringUtils.hasText(jsonTrend)) {
            return new Overview(jsonTrend, jsonBackground);
        }

        var plain = cleaned
            .replaceFirst("^\\s*\\{?\\s*\"trend\"\\s*:\\s*\"?", "")
            .replaceFirst("\"?\\s*,?\\s*\"background\"\\s*:[\\s\\S]*$", "")
            .replace("\\n", "\n")
            .trim();
        return new Overview(plain, "");
    }

    private static String extractJsonField(String text, String field) {
        var pattern = java.util.regex.Pattern.compile(
            "\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        var matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return unescape(matcher.group(1));
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 供其它提示词复用的截断（null 安全）。 */
    public static String truncateForPrompt(String value, int limit) {
        return truncate(value, limit);
    }

    private static String snippet(String text) {
        var normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    /** 今日趋势总结 */
    public record Overview(String trend, String background) {
    }
}
