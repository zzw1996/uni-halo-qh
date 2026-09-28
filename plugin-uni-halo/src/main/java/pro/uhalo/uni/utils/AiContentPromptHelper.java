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
    public static String describe(List<String> categories, List<String> sources,
        List<String> keywords) {
        var parts = new ArrayList<String>();
        if (categories != null && !categories.isEmpty()) {
            parts.add("关注主题：" + String.join("、", categories));
        }
        if (sources != null && !sources.isEmpty()) {
            parts.add("关注来源：" + String.join("、", sources));
        }
        if (keywords != null && !keywords.isEmpty()) {
            parts.add("关注关键词：" + String.join("、", keywords));
        }
        return String.join("；", parts);
    }

    /** 兼容：模型偶尔会沿用日报那套字段名（results 而不是 picks） */
    private static JsonNode picksOf(JsonNode json) {
        var picks = json.path("picks");
        return picks.isArray() ? picks : json.path("results");
    }
}
