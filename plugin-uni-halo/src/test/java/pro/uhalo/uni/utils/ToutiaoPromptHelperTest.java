package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「今日头条号」的提示词与解析。
 *
 * <p>两道线：成稿是<b>唯一产出</b>，模型不按 JSON 回时绝不能当作失败丢掉稿子
 * （首行标题 + 其余正文兜底）；但标题与标签必须按平台口径收敛
 * （标题 30 字上限、正文不留 Markdown 标记——正文要直接粘进头条编辑器）。</p>
 */
class ToutiaoPromptHelperTest {

    @Test
    void promptShouldContainKeywordStyleAndConstraints() {
        var prompt = ToutiaoPromptHelper.buildPrompt("AI 编程助手", "深度解读", 1200, 3, "", "", 3);

        assertThat(prompt).contains("「AI 编程助手」");
        assertThat(prompt).contains("写作风格：深度解读");
        assertThat(prompt).contains("\"titles\"");
        assertThat(prompt).contains("\"body\"");
        assertThat(prompt).contains("\"tags\"");
        // 不许编造与不自称 AI 是这条链路的核心约束（无素材直接成稿）
        assertThat(prompt).contains("不许编造");
        assertThat(prompt).contains("不要自称 AI");
        // 正文要粘进头条编辑器：禁 Markdown 标记、禁英文半角双引号
        assertThat(prompt).contains("纯文本分段");
        assertThat(prompt).contains("不要使用英文半角双引号");
        assertThat(prompt).contains("不超过 30 字");
        assertThat(prompt).contains("正文约 1200 字，**不得少于 1020 字**");
    }

    @Test
    void promptShouldCarryAntiAiFlavorRules() {
        var prompt = ToutiaoPromptHelper.buildPrompt("通勤穿搭", "资讯综述", 800, 3, "", "", 3);

        // 「AI 味」是这条链路被投诉过的真问题：禁用清单与结构要求必须在提示词里
        assertThat(prompt).contains("不要有「AI 味」");
        assertThat(prompt).contains("在当今");
        assertThat(prompt).contains("综上所述");
        assertThat(prompt).contains("赋能");
        assertThat(prompt).contains("小标题可加可不加（最多 2 个）");
        assertThat(prompt).contains("段落长短错落");
        assertThat(prompt).contains("写到实处：多写具体的做法、步骤、判断依据、能观察到的细节");
        assertThat(prompt).contains("不要总结升华");
        assertThat(prompt).contains("写完请自己检查一遍");
        // 头条图文要有自己的格式：日报/公众号的两种格式被点名禁止
        assertThat(prompt).contains("是自媒体图文，不是站内的新闻摘要");
        assertThat(prompt).contains("概述 + 核心论点 + 小编看法");
        assertThat(prompt).contains("导语 + 精选清单");
        // 配图位置由模型在正文里标注（发布前替换成真实图片）
        assertThat(prompt).contains("【配图1：画面描述】");
        assertThat(prompt).contains("一共 3 个");
        // 默认口吻：第一人称普通人的视角
        assertThat(prompt).contains("你的口吻：").contains("第一人称");
    }

    @Test
    void promptShouldDemandTheWordCountAsAHardRequirement() {
        var prompt = ToutiaoPromptHelper.buildPrompt("远程办公", "干货教程", 1500, 3, "", "", 3);

        // 字数是被投诉过的真问题：必须是硬要求，且说清「删冗余 ≠ 删内容」
        assertThat(prompt).contains("【篇幅是硬要求】");
        assertThat(prompt).contains("不得少于 1275 字");
        assertThat(prompt).contains("写不满就继续写");
        assertThat(prompt).contains("也不许用套话、形容词堆砌或重复的话凑字数");
        assertThat(prompt).contains("删的是空话套话，不是信息、例子与步骤");
    }

    @Test
    void floorOfShouldBeTheSingleSourceOfTheLowerBound() {
        assertThat(ToutiaoPromptHelper.floorOf(1500)).isEqualTo(1275);
        assertThat(ToutiaoPromptHelper.floorOf(1000)).isEqualTo(850);
        // 非法值不能算出 0 下限（那等于没有下限）
        assertThat(ToutiaoPromptHelper.floorOf(0)).isGreaterThan(0);
        assertThat(ToutiaoPromptHelper.floorOf(-5)).isGreaterThan(0);
    }

    @Test
    void promptShouldUseConfiguredVoiceAndExtraBannedPhrases() {
        var prompt = ToutiaoPromptHelper.buildPrompt("AI 编程助手", "深度解读", 1000, 2,
            "一个冷静的行业观察者，不对读者说教", "家人们, 宝子们\n绝绝子", 3);

        assertThat(prompt).contains("你的口吻：一个冷静的行业观察者，不对读者说教");
        // 自定义禁用词归一成分隔符并把换行压平
        assertThat(prompt).contains("站长另外点名的词句（同样一律不许出现）：家人们、宝子们、绝绝子");
    }

    @Test
    void promptShouldFallBackToDefaultStyleAndClampTitleCount() {
        var prompt = ToutiaoPromptHelper.buildPrompt("通勤穿搭", "", 800, 99, "", "", 3);

        assertThat(prompt).contains("写作风格：资讯综述");
        // 标题候选上限 5：超过只留 5 个，提示词里也不该出现模型要不了的个数
        assertThat(prompt).contains("共 5 个");
        assertThat(prompt).doesNotContain("共 99 个");
    }

    @Test
    void parseShouldReadJsonResult() {
        var result = ToutiaoPromptHelper.parseResult("""
            {
              "titles": ["第一个标题", "第二个标题"],
              "body": "第一段。\\n\\n第二段。",
              "tags": ["人工智能", "职场"]
            }
            """, 3);

        assertThat(result.titles()).containsExactly("第一个标题", "第二个标题");
        assertThat(result.body()).isEqualTo("第一段。\n\n第二段。");
        assertThat(result.tags()).containsExactly("人工智能", "职场");
        assertThat(result.hasBody()).isTrue();
    }

    @Test
    void parseShouldAcceptJsonWrappedInCodeFence() {
        var result = ToutiaoPromptHelper.parseResult("""
            ```json
            {"titles": ["标题"], "body": "正文内容", "tags": []}
            ```
            """, 3);

        assertThat(result.titles()).containsExactly("标题");
        assertThat(result.body()).isEqualTo("正文内容");
    }

    @Test
    void parseShouldTidyTitlesAndTags() {
        var result = ToutiaoPromptHelper.parseResult("""
            {
              "titles": ["**带星号的标题**", "「带引号的标题」", "重复", "重复"],
              "body": "正文",
              "tags": ["#人工智能", " 职场 ", ""]
            }
            """, 3);

        assertThat(result.titles()).containsExactly("带星号的标题", "带引号的标题", "重复");
        assertThat(result.tags()).containsExactly("人工智能", "职场");
    }

    @Test
    void parseShouldCapTitleCandidatesToRequestedCount() {        // 模型多回了几个：按本次要的个数收敛（页面就是按这个数展示的）
        var result = ToutiaoPromptHelper.parseResult("""
            {"titles": ["一", "二", "三", "四", "五"], "body": "正文", "tags": []}
            """, 2);

        assertThat(result.titles()).containsExactly("一", "二");
    }

    @Test
    void parseShouldClipOverlongTitle() {        var longTitle = "标".repeat(80);
        var result = ToutiaoPromptHelper.parseResult(
            "{\"titles\": [\"" + longTitle + "\"], \"body\": \"正文\", \"tags\": []}", 1);

        assertThat(result.titles()).hasSize(1);
        assertThat(result.titles().get(0)).hasSize(ToutiaoPromptHelper.MAX_TITLE_CHARS);
    }

    @Test
    void parseShouldStripMarkdownHeadingsFromBody() {
        var result = ToutiaoPromptHelper.parseResult("""
            {"titles": [], "body": "## 小标题\\n正文一段。\\n\\n### 另一节\\n正文二段。", "tags": []}
            """, 3);

        assertThat(result.body()).isEqualTo("小标题\n正文一段。\n\n另一节\n正文二段。");
    }

    @Test
    void parseShouldFallBackToPlainTextWhenNotJson() {
        // 模型没按 JSON 回：首行当标题、其余当正文，不能丢掉这次成稿
        var result = ToutiaoPromptHelper.parseResult("""
            这里是一个标题

            第一段正文。

            第二段正文。
            """, 3);

        assertThat(result.titles()).containsExactly("这里是一个标题");
        assertThat(result.body()).contains("第一段正文。").contains("第二段正文。");
        assertThat(result.hasBody()).isTrue();
    }

    @Test
    void parseShouldFallBackToBodyOnlyWhenSingleLine() {
        var result = ToutiaoPromptHelper.parseResult("只有一段没有换行的正文内容", 3);

        assertThat(result.titles()).isEmpty();
        assertThat(result.body()).isEqualTo("只有一段没有换行的正文内容");
    }

    @Test
    void parseShouldReturnEmptyForBlankText() {
        assertThat(ToutiaoPromptHelper.parseResult("", 3).hasBody()).isFalse();
        assertThat(ToutiaoPromptHelper.parseResult(null, 3).hasBody()).isFalse();
    }

    @Test
    void parseShouldReportNoBodyWhenJsonBodyEmpty() {
        // JSON 合法但正文为空：这是「模型没写出稿子」，必须报没有正文，
        // 而不是把整段 JSON 文本当成稿子存下来
        var result = ToutiaoPromptHelper.parseResult("""
            {"titles": ["标题"], "body": ""}
            """, 3);

        assertThat(result.hasBody()).isFalse();
        assertThat(result.titles()).containsExactly("标题");
    }

    // ── 去 AI 味：检测与重写 ──

    @Test
    void detectAiTellsShouldCatchTypicalPhrases() {
        var body = """
            在当今这个时代，远程办公已经不是什么新鲜事。
            首先，工具要选对；其次，节奏要定好；最后，别把工作台搬进卧室。
            综上所述，远程办公的关键在于边界感——这条界线——得自己划。
            """;

        var tells = ToutiaoPromptHelper.detectAiTells(body);

        assertThat(tells).contains("在当今", "综上所述", "首先…其次…最后");
    }

    @Test
    void detectAiTellsShouldStayQuietOnHumanLikeWriting() {
        // 正常写作里会出现的词（「对于」「此外」「随着」）不进清单：误报会让每篇都白花一次重写
        var body = """
            我把桌面清空了一半，只留一台笔记本和一杯水。
            对于在家干活的人来说，最先垮掉的是腰。我一般建议先把椅子换了，再谈效率。
            """;

        assertThat(ToutiaoPromptHelper.detectAiTells(body)).isEmpty();
    }

    @Test
    void detectAiTellsShouldCountDashAbuse() {
        var body = "这事儿说简单也简单——无非是取舍——但真做起来——处处是坎——回头看全是经验。";

        assertThat(ToutiaoPromptHelper.detectAiTells(body)).anyMatch(tell -> tell.contains("破折号"));
    }

    @Test
    void detectAiTellsShouldReturnEmptyForBlankBody() {
        assertThat(ToutiaoPromptHelper.detectAiTells("")).isEmpty();
        assertThat(ToutiaoPromptHelper.detectAiTells(null)).isEmpty();
    }

    @Test
    void repairPromptShouldCarryTellsOriginalBodyAndConstraints() {
        var brief = new ToutiaoPromptHelper.RepairBrief(List.of("标题一", "标题二"),
            "在当今这个时代，工具的选择非常重要。", List.of("在当今", "综上所述"), "", "", 2,
            1200, false);
        var prompt = ToutiaoPromptHelper.buildRepairPrompt(brief);

        assertThat(prompt).contains("下面这一稿需要再改一遍");
        assertThat(prompt).contains("去「AI 味」：这一稿里最扎眼的是这些表述：在当今、综上所述");
        assertThat(prompt).contains("原标题候选（可沿用或微调）：标题一 / 标题二");
        assertThat(prompt).contains("在当今这个时代，工具的选择非常重要。");
        // 事实边界与格式要求必须跟着修复请求一起走，否则重写会开始编数据
        assertThat(prompt).contains("不许编造数据、事件、机构与人名");
        assertThat(prompt).contains("纯文本分段");
        assertThat(prompt).contains("给 2 个候选");
        // 重写时标记必须保留（否则修复版的图就没地方放了）
        assertThat(prompt).contains("「【配图N：画面描述】」标记必须保留");
        // 口吻留空 = 内置口吻
        assertThat(prompt).contains("第一人称");
        // 不短就不该提字数
        assertThat(prompt).doesNotContain("补足字数");
        assertThat(prompt).contains("长度与初稿大致相当即可");
    }

    @Test
    void repairPromptShouldDemandMoreWordsWhenTooShort() {
        var brief = new ToutiaoPromptHelper.RepairBrief(List.of("标题"), "太短的一稿。", List.of(),
            "一个冷静的观察者", "宝子们", 3, 1500, true);
        var prompt = ToutiaoPromptHelper.buildRepairPrompt(brief);

        assertThat(prompt).contains("补足字数：初稿只有 " + "太短的一稿。".length() + " 字");
        assertThat(prompt).contains("目标 1500 字");
        assertThat(prompt).contains("不得少于 1275 字");
        assertThat(prompt).contains("不许注水");
        assertThat(prompt).contains("口吻：一个冷静的观察者");
        assertThat(prompt).contains("宝子们");
        // 只说补字数时不该出现套话段落
        assertThat(prompt).doesNotContain("最扎眼");
    }

    // ── 版本取舍：先字数、后套话 ──

    @Test
    void chooseBestShouldAdoptRepairThatFixesLength() {
        // 初稿 30 字（远低于 1200 目标的下限 1020），修复版达标 → 采用（哪怕套话没变）
        var original = new ToutiaoPromptHelper.Generated(List.of("旧标题"), shortBody(), List.of());
        var repairText = json("新标题", body(1200));

        var choice = ToutiaoPromptHelper.chooseBest(original, repairText, 3, 1200);

        assertThat(choice.repaired()).isTrue();
        assertThat(choice.chosenLength()).isGreaterThanOrEqualTo(ToutiaoPromptHelper.floorOf(1200));
        assertThat(choice.note()).contains("已自动补写");
    }

    @Test
    void chooseBestShouldRejectRepairThatShrinksBelowFloor() {
        // 初稿长度达标，修复版反而掉到下限以下 → 拒绝（不许倒退）
        var original = new ToutiaoPromptHelper.Generated(List.of("旧标题"), body(1200), List.of());
        var repairText = json("新标题", shortBody());

        var choice = ToutiaoPromptHelper.chooseBest(original, repairText, 3, 1200);

        assertThat(choice.repaired()).isFalse();
        assertThat(choice.chosenLength()).isEqualTo(1200);
        assertThat(choice.note()).contains("低于下限");
    }

    @Test
    void chooseBestShouldPreferFewerTellsWhenBothAreLongEnough() {
        var original = new ToutiaoPromptHelper.Generated(List.of("旧标题"),
            body(1200) + "综上所述，值得我们深思。", List.of());
        var repairText = json("新标题", body(1200));

        var choice = ToutiaoPromptHelper.chooseBest(original, repairText, 3, 1200);

        assertThat(choice.repaired()).isTrue();
        assertThat(choice.chosenTells()).isLessThan(choice.originalTells());
        assertThat(choice.chosen().titles()).containsExactly("新标题");
        assertThat(choice.note()).contains("套话");
    }

    @Test
    void chooseBestShouldTakeTheLongerOneWhenBothAreShort() {
        var original = new ToutiaoPromptHelper.Generated(List.of("旧标题"), "短稿一。", List.of());
        var repairText = json("新标题", "短稿二，比上一篇长一点。");

        var choice = ToutiaoPromptHelper.chooseBest(original, repairText, 3, 1200);

        assertThat(choice.repaired()).isTrue();
        assertThat(choice.chosen().body()).isEqualTo("短稿二，比上一篇长一点。");
        assertThat(choice.note()).contains("两版都没写够");
    }

    @Test
    void chooseBestShouldKeepOriginalWhenRepairHasNoBody() {
        var original = new ToutiaoPromptHelper.Generated(List.of("旧标题"), body(1200), List.of());

        var choice = ToutiaoPromptHelper.chooseBest(original, "{\"titles\": [], \"body\": \"\"}", 3,
            1200);

        assertThat(choice.repaired()).isFalse();
        assertThat(choice.chosen().body()).isEqualTo(body(1200));
        assertThat(choice.note()).contains("没拿到正文");
    }

    private static String shortBody() {
        return "太短的一稿。";
    }

    /** 造一段指定字数的正文（内容无所谓，测的是长度口径） */
    private static String body(int length) {
        return "字".repeat(length);
    }

    private static String json(String title, String body) {
        return "{\"titles\": [\"" + title + "\"], \"body\": \"" + body + "\", \"tags\": []}";
    }
}
