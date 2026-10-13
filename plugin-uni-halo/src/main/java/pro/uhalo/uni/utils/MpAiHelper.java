package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestArticleDTO;

import java.util.List;

/**
 * 公众号文章的提示词与响应解析（标题 / 摘要 / 导语三件套）。
 *
 * <p>「每篇点评」（概述、核心信息、小编看法）复用 AI 日报的成稿提示词
 * （{@link DigestAiHelper#buildSummaryPrompt}），这里只补公众号特有的三件套：
 * 标题（官方上限 32 字）、摘要（上限 120 字、不填会被微信抓正文前 54 字）、
 * 导语（正文开头的整体观察）。</p>
 */
public final class MpAiHelper {

    private MpAiHelper() {
    }

    /** 三件套（已按上限收敛；缺省为空串，由调用方兜底） */
    public record Header(String title, String digest, String intro) {
    }

    /**
     * 三件套提示词。
     *
     * @param directionLabel 方向中文名（如「AI 发展」）
     * @param articles       本期精选（标题 + 一句话概述即可）
     * @param digestWords    摘要目标字数（配置值，上限 120）
     */
    public static String buildHeaderPrompt(String directionLabel, List<DigestArticleDTO> articles,
        int digestWords) {
        var builder = new StringBuilder();
        var index = 0;
        for (var article : articles) {
            if (article == null) {
                continue;
            }
            index++;
            var title = StringUtils.hasText(article.getTitleZh())
                ? article.getTitleZh() : article.getTitle();
            builder.append(index).append(". ")
                .append(DigestAiHelper.truncateForPrompt(title, 80))
                .append("（").append(article.getSourceName()).append("）：")
                .append(DigestAiHelper.truncateForPrompt(article.getSummary(), 120))
                .append('\n');
        }
        var list = builder.toString();
        var introWords = 200;

        return """
            你是微信公众号「%s」栏目的主编。下面是本期精选的 %d 篇文章，请产出本期文章的标题、摘要与导语。

            1. **标题** (title)：不超过 28 个字（官方硬上限 32 字）。要有信息量与画面感，像人写的，不要标题党（不用「震惊」「必看」「重磅」这类词），不要书名号与引号，不要出现栏目名之外的英文长串。
            2. **摘要** (digest)：不超过 %d 个字，写成一句通顺的话，点出本期最重要的 1-2 条信息。这一句会显示在公众号的文章列表里——不写会被系统抓取正文前 54 个字（通常是排版符号，很难看），所以必须认真写。
            3. **导语** (intro)：%d 字左右的一段话，在正文开头。概括这几条内容共同指向的趋势、为什么现在值得关注，帮读者建立语境。不要逐篇罗列，不要用列表，不要重复标题。

            写作要求：
            - 全部用中文；正文中不要使用英文半角双引号（需要引号时用「」或“”）
            - 不要出现「AI 生成」「本文由模型撰写」这类自述
            - 数字、版本号、专有名词保持原文写法

            ## 本期精选

            """.formatted(directionLabel, Math.max(1, index), digestWords, introWords)
            + list + """

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {"title": "标题", "digest": "摘要", "intro": "导语"}""";
    }

    /**
     * 解析三件套并按上限收敛。
     *
     * <p>模型偶发少字段/超长都在这里消化：标题按 32 字截断（补省略号）、
     * 摘要按配置目标字数截断并去掉截断留下的半截标点。</p>
     */
    public static Header parseHeader(String raw, int digestWords) {
        var node = DigestAiHelper.parseJson(raw == null ? "" : raw);
        return headerOf(node, digestWords);
    }

    /** 从已解析的 JSON 里取三件套（便于测试直接喂节点） */
    static Header headerOf(JsonNode node, int digestWords) {
        var title = node.path("title").asText("");
        var digest = node.path("digest").asText("");
        var intro = node.path("intro").asText("");
        return new Header(
            MpTextUtils.clipTitle(title, MpTextUtils.MAX_TITLE_CHARS),
            MpTextUtils.tidyTail(MpTextUtils.clip(digest, Math.max(10,
                Math.min(digestWords, MpTextUtils.MAX_DIGEST_CHARS)))),
            intro == null ? "" : intro.trim());
    }
}
