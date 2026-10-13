package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentItemDTO;
import java.util.ArrayList;
import java.util.List;

/**
 * 「我的 AI 简报」的提示词与响应解析。
 *
 * <p><b>每次生成只调用一次 AI</b>（成本阀门）：把已经筛好的候选交给模型，
 * 让它做三件事——写一段导语、在候选里挑出最值得看的顺序、给每篇一句推荐理由。
 * 它<b>不负责挑选内容本身</b>（那是纯筛选干的活），因此即使模型返回垃圾，
 * 用户看到的仍然是一份排好序的精选列表，只是没有导语与推荐语。</p>
 *
 * <p>因此解析必须是全容错的：任何字段读不出来都退化为「没有」，绝不抛异常——
 * 一次模型抽风不该让用户当天的简报打不开。</p>
 */
public final class AiContentPromptHelper {

    /** 送进提示词的摘要长度：够判断这篇讲什么，又不至于让一次请求撑爆上下文 */
    private static final int DESCRIPTION_LIMIT = 160;

    /** 导语字数下限（避免模型只回一句「今天有几篇好文」） */
    private static final int MIN_INTRO_WORDS = 30;

    /** 结尾字数下限（太短的「综上所述」不如不写） */
    private static final int MIN_OUTRO_WORDS = 40;

    private AiContentPromptHelper() {
    }

    /**
     * 组装简报提示词。
     *
     * @param picked      纯筛选后的候选（已按得分排序，最多 maxItems 条）
     * @param preferences 用户偏好的可读描述（如「关注主题：AI / ML、安全；关注来源：xxx」）
     * @param introWords  导语字数上限
     */
    public static String buildIntroPrompt(List<AiContentItemDTO> picked, String preferences,
        int introWords) {
        var builder = new StringBuilder();
        for (var i = 0; i < picked.size(); i++) {
            var item = picked.get(i);
            builder.append("Index ").append(i).append(": ").append(item.getTitle());
            if (StringUtils.hasText(item.getSourceName())) {
                builder.append("（来源：").append(item.getSourceName()).append("）");
            }
            if (StringUtils.hasText(item.getCategoryLabel())) {
                builder.append("〔").append(item.getCategoryLabel()).append("〕");
            }
            var keywords = item.getKeywords();
            if (keywords != null && !keywords.isEmpty()) {
                builder.append(" 关键词：").append(String.join(", ", keywords));
            }
            builder.append('\n').append(DigestAiHelper.truncateForPrompt(item.getSummary(),
                DESCRIPTION_LIMIT)).append("\n\n");
        }
        var words = Math.max(MIN_INTRO_WORDS, introWords);

        return """
            你是一位替固定读者挑内容的技术编辑。这位读者自己设了关注方向，系统已经按他的方向
            筛出了下面这些文章，请帮他写一段导语，并给出阅读顺序与每篇的推荐理由。

            ## 这位读者关注的方向
            """ + (StringUtils.hasText(preferences) ? preferences : "（未设置具体方向，给他当期全部精选）") + """

            ## 写作要求
            - 导语不超过 \
            """ + words + """
            字，直接讲「今天这几篇在讲什么、彼此有什么关系」，不要复述标题列表，
            不要写「以下是为你精选」这类套话，也不要编造候选里没有的信息
            - 导语用陈述句，不要用 Markdown 标题或列表，不要出现「读者」「您」这类称呼
            - 每篇给一句推荐理由（不超过 40 字），说清它为什么值得看，不要复述标题
            - 顺序由你决定：把最值得先看的放前面，picks 里不要遗漏候选中的任何一篇
            - 只允许使用候选里的 Index，不要新增、不要杜撰文章

            ## 候选文章

            """ + builder + """
            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "intro": "一段导语",
              "picks": [
                {"index": 0, "reason": "一句话推荐理由"}
              ]
            }""";
    }

    /**
     * 解析简报响应。
     *
     * <p>返回的 {@link Verdict#picks()} 只包含<b>合法 index</b> 的条目，
     * 调用方据此重排并补上推荐理由；导语为空表示这次没有可用导语（不是错误）。</p>
     */
    public static Verdict parse(String aiText, int candidateSize) {
        var json = DigestAiHelper.parseJson(aiText);
        var intro = json.path("intro").asText("");
        var picks = new ArrayList<Pick>();
        for (var node : picksOf(json)) {
            var index = node.path("index").asInt(-1);
            if (index < 0 || index >= candidateSize) {
                // 越界直接忽略：宁可少一条推荐理由，也不能把不存在的文章塞进列表
                continue;
            }
            picks.add(new Pick(index, node.path("reason").asText("")));
        }
        return new Verdict(intro.trim(), List.copyOf(picks));
    }

    /** 导语 + 阅读顺序（含每篇的推荐理由） */
    public record Verdict(String intro, List<Pick> picks) {

        public boolean hasIntro() {
            return StringUtils.hasText(intro);
        }
    }

    /** 一条推荐：候选里的下标 + 一句话理由 */
    public record Pick(int index, String reason) {

        public String reasonOrEmpty() {
            return reason == null ? "" : reason.trim();
        }
    }

    /** 把候选列表压成提示词里的「关注方向」描述（不暴露用户原始输入之外的信息） */
    public static String describe(List<String> categories, List<String> keywords) {
        return describe(categories, keywords, List.of());
    }

    /**
     * 同上，带用户手工输入的定制主题（主题检索命中时，AI 要围绕它组织导语与理由）。
     *
     * <p>「关注来源」这个维度已取消：内容改成从实时抓取的源里挑，来源不再由用户指定。</p>
     */
    public static String describe(List<String> categories, List<String> keywords,
        List<String> customTopics) {
        var parts = new ArrayList<String>();
        if (categories != null && !categories.isEmpty()) {
            parts.add("关注主题分类：" + String.join("、", categories));
        }
        if (keywords != null && !keywords.isEmpty()) {
            parts.add("关注关键词：" + String.join("、", keywords));
        }
        if (customTopics != null && !customTopics.isEmpty()) {
            parts.add("用户定制主题（本次简报要优先围绕它组织）：" + String.join("、", customTopics));
        }
        return String.join("；", parts);
    }

    // ── 实时素材的挑选（简报的主路径） ──

    /** 送进提示词的素材条数上限之外的兜底：标题也截一下，避免个别长标题把提示词撑大 */
    private static final int TITLE_LIMIT = 120;

    /**
     * 组装「从实时素材里挑今天这份」的提示词。
     *
     * <p>与 {@link #buildIntroPrompt} 的本质区别：那边的候选已经筛好，AI 只负责组织语言；
     * 这里把「挑哪些」也交给 AI——素材是从源里现抓的（可能上百条），纯字符串匹配挑不出
     * 「同一件事的多条报道哪条最值得看」，而这正是付费简报的价值。</p>
     *
     * <p>仍然只调用一次 AI：挑选、中文标题、摘要、推荐理由、导语都由这一次返回，
     * 因此每位用户每天的成本与旧实现完全一致。</p>
     */
    public static String buildPickPrompt(List<AiContentItemDTO> candidates, String preferences,
        int maxItems, int introWords, DigestCategoryCatalog catalog) {
        return buildPickPrompt(candidates, preferences, maxItems, introWords, catalog, 0, false);
    }

    /**
     * 同上，带「AI 结尾」字数与「素材来自关键词检索」标记。
     *
     * <p>{@code outroWords > 0} 时要求模型再写一段结尾（小结/看法）：它是这一份里唯一
     * 「由 AI 自己说」的内容，按防侵权口径必须是原创观点，不能复述摘要。</p>
     *
     * <p>{@code searched} 为真时说明素材是围绕读者输入的关键词联网检索来的，并明确
     * 「只挑与关键词直接相关的，明显无关的不要，一条都不相关就返回空」——
     * 否则模型会在检索噪声里凑数，用户就会看到「跟你输入的关键词没关系」的内容。</p>
     */
    public static String buildPickPrompt(List<AiContentItemDTO> candidates, String preferences,
        int maxItems, int introWords, DigestCategoryCatalog catalog, int outroWords,
        boolean searched) {
        var builder = new StringBuilder();
        for (var i = 0; i < candidates.size(); i++) {
            var item = candidates.get(i);
            builder.append('#').append(i).append(' ')
                .append(DigestAiHelper.truncateForPrompt(item.getTitle(), TITLE_LIMIT));
            if (StringUtils.hasText(item.getPublishedAt())) {
                builder.append(" 〔").append(item.getPublishedAt()).append('〕');
            }
            if (StringUtils.hasText(item.getSourceName())) {
                builder.append("（").append(item.getSourceName()).append('）');
            }
            var summary = DigestAiHelper.truncateForPrompt(item.getSummary(), DESCRIPTION_LIMIT);
            if (StringUtils.hasText(summary)) {
                builder.append('\n').append(summary);
            }
            builder.append('\n');
        }
        var limit = Math.max(1, maxItems);
        var words = Math.max(MIN_INTRO_WORDS, introWords);
        var categoryKeys = catalog == null ? List.<String>of() : catalog.keys();

        return """
            你是一位替固定读者挑内容的技术编辑。下面是刚刚从各个源抓到的技术资讯（按时间倒序），
            每条带编号、标题、发布时间与摘要。请挑出最值得这位读者看的至多 \
            """ + limit + """
            条，并给出中文标题、摘要与推荐理由。

            ## 这位读者关注的方向
            """ + (StringUtils.hasText(preferences) ? preferences : "（没有具体方向，替他挑今天最重要的几条）") + """

            ## 挑选与写作要求
            - 优先「新」与「重要」：同一个事件的重复报道只保留最值得看的一条，不要凑数，
              实在没有值得看的可以少给（宁少勿多）
            - title：中文标题，简短准确（原标题是外语时翻译成中文，不要保留原文）
            - summary：一句中文摘要（不超过 60 字），只依据上面给的摘要与标题，不要编造事实
            - reason：一句推荐理由（不超过 40 字），说清他为什么值得看，不要复述标题
            - index 必须是上面出现过的编号，不要新增、不要杜撰条目
            - 导语不超过 \
            """ + words + """
            字，直接讲「今天这几条在讲什么、彼此有什么关系」，不要写「以下是为你精选」这类套话，
            不要用 Markdown 标题或列表

            ## 素材说明
            """ + (searched
                ? "这批素材是围绕读者自己输入的关键词联网检索到的公开报道：只挑与关键词直接相关的，"
                    + "明显无关（同名的其他人、其他事）一律不要；一条都不相关就返回空列表。"
                : "这批素材是站点近期抓取的技术资讯，按与读者关注方向的相关性挑选。") + """

            ## 候选（共 """ + candidates.size() + """
             条）
            """ + builder + """
            ## 主题分类
            给每条挑一个最贴近的分类 key，只能从下面这些里选（都不贴近就留空字符串）：
            """ + (categoryKeys.isEmpty() ? "（无可用分类，category 一律留空）"
            : String.join("、", categoryKeys)) + """

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "intro": "一段导语",
              "outro": "一段结尾（小结/看法）",
              "picks": [
                {"index": 0, "title": "中文标题", "summary": "一句中文摘要",
                 "reason": "一句推荐理由", "category": "分类 key 或空字符串"}
              ]
            }
            """ + outroInstruction(outroWords);
    }

    /** 结尾那段要求（站长把 outroWords 配成 0 时返回空串：这一份不要结尾） */
    private static String outroInstruction(int outroWords) {
        if (outroWords <= 0) {
            return "";
        }
        return "## 结尾（outro）\n"
            + "再写一段不超过 " + Math.max(MIN_OUTRO_WORDS, outroWords) + " 字的结尾："
            + "它不是复述上面的条目，而是你作为编辑的判断——这几条连起来说明了什么、"
            + "读者可以从中记住什么（或接下来值得观察什么）。"
            + "不要出现「AI」「模型」「生成」这类字样，不要用 Markdown 标题或列表。";
    }

    /**
     * 解析挑选结果；全容错——解析不出来就是「没挑到」，调用方退化为字符串匹配。
     */
    public static Picked parsePicked(String aiText, int candidateSize) {
        try {
            var json = DigestAiHelper.parseJson(aiText);
            var intro = json.path("intro").asText("");
            var outro = json.path("outro").asText("");
            var selections = new ArrayList<Selection>();
            for (var node : picksOf(json)) {
                var index = node.path("index").asInt(-1);
                if (index < 0 || index >= candidateSize) {
                    // 越界直接忽略：宁可少一条，也不能把不存在的素材塞进简报
                    continue;
                }
                selections.add(new Selection(index, node.path("title").asText(""),
                    node.path("summary").asText(""), node.path("reason").asText(""),
                    node.path("category").asText("")));
            }
            return new Picked(intro.trim(), outro.trim(), List.copyOf(selections));
        } catch (Exception ex) {
            return new Picked("", "", List.of());
        }
    }

    /** 挑选结果：导语 + 结尾 + 选中的素材（下标与 AI 改写后的文案） */
    public record Picked(String intro, String outro, List<Selection> selections) {

        public boolean hasIntro() {
            return StringUtils.hasText(intro);
        }

        public String outroOrEmpty() {
            return outro == null ? "" : outro.trim();
        }
    }

    /** 一条被选中的素材：下标 + AI 写的中文标题/摘要/理由/分类 */
    public record Selection(int index, String title, String summary, String reason,
                            String category) {

        public String titleOrEmpty() {
            return title == null ? "" : title.trim();
        }

        public String summaryOrEmpty() {
            return summary == null ? "" : summary.trim();
        }

        public String reasonOrEmpty() {
            return reason == null ? "" : reason.trim();
        }

        public String categoryOrEmpty() {
            return category == null ? "" : category.trim();
        }
    }

    // ── 素材不足时的直接生成 ──

    /**
     * 组装「按定制主题直接生成」的提示词。
     *
     * <p>走到这里的场景：用户手输的主题在最近日报里检索不到足够素材。
     * 没有素材依据是这段内容最大的风险，因此约束必须狠——
     * 不编造数据与事件，只写观点、方法论与路线，宁可比摘要空泛也不能幻觉。</p>
     */
    public static String buildGeneratePrompt(List<String> topics, int words) {
        var topicText = String.join("、", topics);
        var floor = Math.round(words * 0.85f);
        return """
            你是一位技术博客的专栏小编。读者点了一份定制内容，想看这个主题：
            「%s」

            最近站内的日报素材里没有覆盖到它，请你直接写一篇围绕这个主题的短文。写作约束：

            - **不许编造**：没有素材依据，就绝不能出现具体的统计数据、版本号、CVE 编号、
              「某公司发布」「近日」这类具体事件表述——只写你能负责任地讲的：
              概念之间的关系、为什么值得关注、实践路径与方法论、常见的误区、
              下一步值得观察什么方向
            - 不要自称 AI，不要出现「AI」「模型」「生成式」这类字样：这段内容会以
              「小编看法」的署名展示
            - 结构建议：先一句话说清这个主题为什么值得现在关注，再分 2-3 个层次展开
              （每个层次一段），结尾给读者一句可操作的下一步建议
            - 全文约 %d 字（不少于 %d 字），纯文本段落，不要 Markdown 标题、不要列表
            - 语言全部是你自己的：不存在可照搬的原文，这本身就是防侵权的要求
            - 正文中不要使用英文半角双引号（避免破坏 JSON 结构）

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "title": "一个具体的标题（不要出现主题二字）",
              "body": "短文正文..."
            }
            """.formatted(topicText, words, floor);
    }

    /**
     * 解析直接生成的内容；全容错——解析不出来就是「没有」，调用方降级为普通筛选结果。
     */
    public static Generated parseGenerated(String aiText) {
        try {
            var json = DigestAiHelper.parseJson(aiText);
            var title = json.path("title").asText("").trim();
            var body = json.path("body").asText("").trim();
            if (!StringUtils.hasText(body)) {
                return Generated.empty();
            }
            return new Generated(title, body);
        } catch (Exception ex) {
            return Generated.empty();
        }
    }

    /** 直接生成结果：标题 + 正文；body 为空表示本次没有可用内容 */
    public record Generated(String title, String body) {

        public boolean hasBody() {
            return StringUtils.hasText(body);
        }

        public static Generated empty() {
            return new Generated("", "");
        }
    }

    /** 兼容：模型偶尔会沿用日报那套字段名（results 而不是 picks） */
    private static JsonNode picksOf(JsonNode json) {
        var picks = json.path("picks");
        return picks.isArray() ? picks : json.path("results");
    }
}
