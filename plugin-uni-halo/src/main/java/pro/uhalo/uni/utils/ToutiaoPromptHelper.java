package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「今日头条号」稿件的提示词与响应解析（标题候选 / 正文 / 标签）。
 *
 * <p>与付费简报的「直接生成」同一思路（见 {@link AiContentPromptHelper#buildGeneratePrompt}）：
 * 输入一个关键词直接成稿，没有素材依据，因此<b>不许编造</b>的约束写得更狠；
 * 头条号还会把正文粘进编辑器发布，所以正文按纯文本分段（不带 Markdown 标记），
 * 标题按平台上限（{@value #MAX_TITLE_CHARS} 字）收敛。</p>
 *
 * <p><b>另一半是「去 AI 味」</b>：这类稿子最容易被读者一眼看出是机器写的——
 * 铺垫式开头、段首「首先/其次/最后」、结尾升华、每段句数一样、通篇没有具体细节。
 * 所以提示词里带一份套话禁用清单（{@link #AI_TELL_PHRASES}），
 * 生成后再用 {@link #detectAiTells} 复查一遍，命中太多就交给
 * {@link #buildRepairPrompt} 重写（由调用方决定，见 {@code ToutiaoService}）。</p>
 */
public final class ToutiaoPromptHelper {

    /** 头条号标题上限（平台硬限制 30 字） */
    public static final int MAX_TITLE_CHARS = 30;

    /** 一次最多保留几个标题候选（模型多回几个不影响使用，但没必要全存） */
    public static final int MAX_TITLES = 5;

    /** 一次最多保留几个标签 */
    public static final int MAX_TAGS = 8;

    /** 单个标签的字数上限（标签是搜索入口，太长反而搜不到） */
    public static final int MAX_TAG_CHARS = 12;

    /** 默认口吻：第一人称 + 普通人的视角，是「去 AI 味」最有效的单一手段 */
    public static final String DEFAULT_VOICE =
        "一个在这个领域里摸爬滚打过的普通人，用第一人称「我」聊自己的观察、判断与一般经验，"
            + "像跟朋友说话一样，不端着";

    /**
     * 「AI 味」检测清单：命中越多，读者越容易看出是机器写的。
     *
     * <p>只放<b>几乎总是套话</b>的表述（「对于」「此外」这类正常写作也会用的词不进清单，
     * 否则会把好稿子判成坏稿子、白白多花一次重写调用）。</p>
     */
    private static final List<String> AI_TELL_PHRASES = List.of(
        // 铺垫式开头
        "在当今", "在如今", "众所周知", "近年来", "不可否认", "不可忽视",
        // 段首序号与总结
        "总而言之", "综上所述", "总的来说", "总的来看", "不难发现", "值得注意的是",
        "由此可见", "更重要的是",
        // 升华腔
        "值得深思", "意义重大", "深远影响", "注入了", "重要一环", "起到了",
        "提供了有力", "不仅", "更是",
        // 互动腔
        "你是否", "有没有想过", "让我们一起", "相信很多人",
        // 装腔与翻译腔
        "赋能", "抓手", "闭环", "底层逻辑", "生态位", "基于此");

    /** 破折号用太多也是「AI 味」特征（真人写作很少连用） */
    private static final int DASH_TELL_THRESHOLD = 4;

    /** 字数下限的比例：目标 1500 字 → 下限 1275 字（提示词、服务端校验、页面提示共用这一个口径） */
    private static final float FLOOR_RATIO = 0.85f;

    private ToutiaoPromptHelper() {
    }

    /**
     * 目标字数对应的「达标下限」。
     *
     * <p>模型总是写不到标称字数，所以提示词里就给一个下限；服务端复查与自动补写也用同一个数——
     * 两处各算一次迟早会不一致（页面写着 1275、校验按 1200 放行）。</p>
     */
    public static int floorOf(int words) {
        return Math.max(1, Math.round(Math.max(1, words) * FLOOR_RATIO));
    }

    /**
     * 成稿提示词：一次调用产出标题候选 + 正文 + 标签。
     *
     * @param keyword        主题关键词（用户输入）
     * @param style          写作风格（可为空，空则用「资讯综述」）
     * @param words          正文目标字数
     * @param titleCount     标题候选个数
     * @param voice          口吻 / 视角（可为空，空则用 {@link #DEFAULT_VOICE}）
     * @param extraBanned    站长另外禁用的词句（逗号/换行分隔，可为空）
     */
    public static String buildPrompt(String keyword, String style, int words, int titleCount,
        String voice, String extraBanned, int imageCount) {
        var theme = keyword == null ? "" : keyword.trim();
        var styleText = StringUtils.hasText(style) ? style.trim() : "资讯综述";
        var count = Math.max(1, Math.min(MAX_TITLES, titleCount));
        var floor = floorOf(words);
        var voiceText = StringUtils.hasText(voice) ? voice.trim() : DEFAULT_VOICE;
        var extra = bannedHint(extraBanned);
        return """
            你是今日头条号的主编，正在为一篇以「%s」为主题的文章写稿。

            写作风格：%s
            你的口吻：%s

            请严格按 JSON 格式返回，不要包含 markdown 代码块或其他文字：
            {
              "titles": ["标题候选，共 %d 个"],
              "body": "文章正文",
              "tags": ["标签1", "标签2", "标签3"]
            }

            【最重要的一条：不要有「AI 味」】
            下面这些写法一律不许出现（出现一次就要改掉）：
            - 铺垫式开头：「在当今…的时代」「随着…的发展」「近年来」「众所周知」
            - 段首序号与总结：「首先」「其次」「再次」「最后」「总而言之」「综上所述」
              「总的来说」「不难发现」「值得注意的是」「由此可见」「更重要的是」
            - 升华腔：「这不仅…更是…」「既是…也是…」「让我们…」「值得我们深思」
              「意义重大」「影响深远」「为…注入了活力」「是…的重要一环」「起到了…的作用」
            - 互动腔：「你是否…」「有没有想过…」「相信很多人…」
            - 装腔词：「赋能」「抓手」「闭环」「底层逻辑」「生态位」
            - 翻译腔：「对于…而言」「在…方面」「基于此」；少用破折号与括号补充%s

            【这篇要发在今日头条：是自媒体图文，不是站内的新闻摘要】
            走「聊一件事」的写法：从一件具体的小事或一个具体的观察切入，顺着讲它为什么值得说、
            带来了什么变化、你怎么看、别人该注意什么，结尾轻轻收住或抛一个具体的问题。
            下面这三种格式<b>绝对不要写</b>（那是本站 AI 日报与公众号文章的样子，发在头条会撞车）：
            - 「概述 + 核心论点 + 小编看法」式的点评结构
            - 「导语 + 精选清单」式的聚合罗列
            - 通篇「1. 2. 3.」的条目文，或每段一个加粗小标题的 PPT 式排版

            【配图位置（发布前会把标记换成真实图片）】
            在正文合适的地方<b>单独起一行</b>写「【配图1：画面描述】」这样的标记，一共 %d 个
            （画面描述写清楚画面里有什么，10~30 字）。位置跟着内容走：
            讲完一个场景、一个对比或一个细节，就配一张图；不要连续两张图挤在一起。

            结构与节奏：
            - 开头直接从一件具体的事、一个具体的场景或一句直白的判断切入，不要任何铺垫
            - 像跟朋友讲清楚一件事那样顺着写，正文是连续的自然段；
              小标题可加可不加（最多 2 个），用了也不要每层都是「xxx：yyy」的冒号句式
            - 段落长短错落：允许一句话单独成段，不要每段都是同样的句数
            - 写到实处：多写具体的做法、步骤、判断依据、能观察到的细节
              （例如某个操作最先卡在哪一步、某个东西摆在桌上是什么样）。
              但**不许编造**：具体统计数据、版本号、「某公司近日发布」这类事件、
              机构与人名都不要写——内容要落在常识与经验层面
            - 结尾用一句落地的话收住（一个判断、一条建议或一句自然的提问），
              不要总结升华，不要以「总之」收尾

            口吻的边界：
            - 用第一人称时，只表达观点与一般经验（「我的看法是」「我一般建议」），
              **不要编造具体经历**（「我上周试了」「我采访过」「我认识一个人」）
            - 同一段不要反复用同一个词开头；不要连续三句用同一种句式

            【篇幅是硬要求】
            - 正文约 %d 字，**不得少于 %d 字**。这是硬要求，不是建议
            - 写不满就继续写：多加一层展开、多给一个具体的例子、把方法和理由写细，
              而不是提前收尾
            - 反过来，也不许用套话、形容词堆砌或重复的话凑字数——字数是靠内容撑起来的

            其余要求：
            - 标题：每个不超过 %d 字（头条号上限）。%d 个候选从不同角度切入
              （例如：现象解读 / 观点判断 / 实用清单 / 趋势预测），要有信息量与点击欲，
              但不要用「震惊」「必看」「重磅」「速看」这类标题党词汇，不要书名号
            - 正文用纯文本分段、段落之间空一行（会直接粘进头条编辑器）
            - 全部用中文；正文中不要使用英文半角双引号（需要引号时用「」或“”）
            - 不要自称 AI，不要出现「AI 生成」「本文由模型撰写」这类表述
            - 标签：3-5 个能被搜索到的领域词（如「人工智能」「职场成长」），每个不超过 %d 个字
            写完请自己检查一遍：把上面列出的套话逐条删掉或改写；哪句话删掉不影响意思就删掉
            （**删的是空话套话，不是信息、例子与步骤**——删完必须仍然不少于 %d 字）；
            连续三段结构一样，就改掉其中一段。
            请只输出 JSON，不要包含任何解释文字。
            """.formatted(theme, styleText, voiceText, count, extra,
            Math.max(ToutiaoImageHelper.MIN_IMAGES, Math.min(ToutiaoImageHelper.MAX_IMAGES,
                imageCount)), words, floor, MAX_TITLE_CHARS, count, MAX_TAG_CHARS, floor);
    }

    /**
     * 「修复」提示词：把初稿交回模型再写一遍（去 AI 味、补足字数，或两件事一起）。
     *
     * <p>与首次写稿分开的理由：这时候模型手上有一份具体的东西可以改——
     * 「把这些套话删掉」「把这一层写透」比「不要写套话」「要写够字数」容易执行得多。</p>
     *
     * <p>两个问题合并成<b>一次</b>调用：站长的两个反馈（稿子像机器写的、稿子太短）
     * 经常同时出现，分两次修等于白花一次钱。</p>
     */
    public static String buildRepairPrompt(RepairBrief brief) {
        var count = Math.max(1, Math.min(MAX_TITLES, brief.titleCount()));
        var voiceText = StringUtils.hasText(brief.voice()) ? brief.voice().trim()
            : DEFAULT_VOICE;
        var floor = floorOf(brief.targetWords());
        var length = brief.body() == null ? 0 : brief.body().length();
        var problems = new StringBuilder();
        if (brief.tells() != null && !brief.tells().isEmpty()) {
            problems.append("- 去「AI 味」：这一稿里最扎眼的是这些表述：")
                .append(String.join("、", brief.tells()))
                .append("。把套话、机械的段落结构、升华句，以及每句删掉都不影响意思的空话都改掉。\n");
        }
        if (brief.tooShort()) {
            problems.append("- 补足字数：初稿只有 ").append(length).append(" 字，目标 ")
                .append(brief.targetWords()).append(" 字（**不得少于 ").append(floor)
                .append(" 字**）。把每一层都写透——补具体的例子、把步骤与判断理由说清楚。")
                .append("**不许注水**：不要用形容词堆砌、重复的话或口号凑字数；")
                .append("一个角度写完了就换一个更具体的角度接着写。\n");
        }
        var titleHint = brief.titles() == null || brief.titles().isEmpty() ? ""
            : "原标题候选（可沿用或微调）：" + String.join(" / ", brief.titles()) + "\n";
        var keepLength = brief.tooShort()
            ? "长度以「不得少于 " + floor + " 字」为准。"
            : "长度与初稿大致相当即可。";
        return """
            下面这一稿需要再改一遍。请按列出的问题重写，其余保持不变。

            保留：主题、信息点与事实边界（同样不许编造数据、事件、机构与人名）。
            需要改的地方：
            %s
            %s口吻：%s

            其余写作要求与首次写稿一致（纯文本分段、段落间空一行、不用英文半角双引号、
            不自称 AI、不出现「AI 生成」这类字样、至少一处能观察到的具体细节）。
            正文里的「【配图N：画面描述】」标记必须保留（位置可以随内容调整，每个标记单独一行，
            数量不少于 3 个）。
            %s
            %s
            初稿正文：
            %s

            请严格按 JSON 格式返回（titles 仍然给 %d 个候选），不要包含 markdown 代码块或其他文字：
            {"titles": ["..."], "body": "...", "tags": ["..."]}
            """.formatted(problems, titleHint, voiceText, keepLength,
            bannedHint(brief.extraBanned()), brief.body(), count);
    }

    /**
     * 修复请求的内容（参数一多，位置参数就开始骗人——这里用记录写清楚每个字段是什么）。
     *
     * @param tells        初稿命中的套话（空表示不修「AI 味」）
     * @param tooShort     初稿字数是否低于下限（true 才要求补足字数）
     * @param targetWords  目标字数（下限由 {@link #floorOf} 算）
     */
    public record RepairBrief(List<String> titles, String body, List<String> tells, String voice,
                              String extraBanned, int titleCount, int targetWords,
                              boolean tooShort) {
    }

    /**
     * 「AI 味」检查：返回命中的套话表述（去重、按清单顺序）。
     *
     * <p>命中数量是给站长看的判断依据，也是调用方决定「要不要重写一版」的阈值输入。
     * 这里只做字符串匹配，不做语义判断——宁可漏报，也不要误报害得每次生成都多花一次调用。</p>
     */
    public static List<String> detectAiTells(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        var hits = new ArrayList<String>();
        for (var phrase : AI_TELL_PHRASES) {
            if (body.contains(phrase)) {
                hits.add(phrase);
            }
        }
        // 「首先/其次/最后」三连是典型的 AI 骨架：三个都在才算一处
        if (body.contains("首先") && body.contains("其次") && body.contains("最后")) {
            hits.add("首先…其次…最后");
        }
        // 破折号扎堆也算一处
        var dashes = body.split("——", -1).length - 1;
        if (dashes >= DASH_TELL_THRESHOLD) {
            hits.add("破折号连用 " + dashes + " 处");
        }
        return List.copyOf(hits);
    }

    /** 站长自定义禁用词：作为一行追加进提示词的禁用块（留空则没有这一行） */
    private static String bannedHint(String extraBanned) {
        if (!StringUtils.hasText(extraBanned)) {
            return "";
        }
        var cleaned = extraBanned.trim().replaceAll("\\s*[,，、;；\\n]+\\s*", "、");
        var shortened = cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
        return "\n- 站长另外点名的词句（同样一律不许出现）：" + shortened;
    }

    /**
     * 比较初稿与修复版：<b>先看字数是否达标（站长的第一诉求），再看套话多少</b>。
     *
     * <p>纯函数（不碰记录、不打日志），这样取舍规则能被单测钉住——它出错的后果是
     * 「越修越坏」（把达标的稿子换成不达标的，或反过来），比不修还糟。</p>
     *
     * <p>四条规则：</p>
     * <ol>
     *   <li>修复版没拿到正文 → 保留初稿；</li>
     *   <li>初稿偏短、修复版达标 → 采用修复版（长度是主要矛盾）；</li>
     *   <li>初稿达标、修复版反而偏短 → 保留初稿（不许倒退）；</li>
     *   <li>两版长度状态相同 → 谁套话少用谁；都偏短时取更长的那版。</li>
     * </ol>
     */
    public static VersionChoice chooseBest(Generated original, String repairText, int titleCount,
        int targetWords) {
        var floor = floorOf(targetWords);
        var before = judge(original.body(), floor);
        var repaired = parseResult(repairText, titleCount);
        if (!repaired.hasBody()) {
            return new VersionChoice(original, false, before.tells(), before.length(),
                before.tells(), before.length(), "修复版没拿到正文，保留初稿");
        }
        var after = judge(repaired.body(), floor);
        boolean adopt;
        if (!before.lengthOk() && after.lengthOk()) {
            adopt = true;
        } else if (before.lengthOk() && !after.lengthOk()) {
            adopt = false;
        } else if (before.lengthOk()) {
            adopt = after.tells() < before.tells();
        } else {
            adopt = after.length() > before.length();
        }
        var chosen = adopt ? repaired : original;
        var chosenJudgement = adopt ? after : before;
        return new VersionChoice(chosen, adopt, chosenJudgement.tells(), chosenJudgement.length(),
            before.tells(), before.length(), describeChoice(adopt, before, after, targetWords,
            floor));
    }

    /** 一版稿子的体检结果：套话命中数 + 字数 + 是否达标（只在内部流转，不对外暴露） */
    private record Judgement(int tells, int length, boolean lengthOk) {
    }

    private static Judgement judge(String body, int floor) {
        var length = body == null ? 0 : body.length();
        return new Judgement(detectAiTells(body).size(), length, length >= floor);
    }

    /** 给站长看的一句话：改了什么、结果如何、还差什么 */
    private static String describeChoice(boolean adopt, Judgement before, Judgement after,
        int targetWords, int floor) {
        if (!adopt) {
            if (before.lengthOk() && !after.lengthOk()) {
                return "修复版字数掉到 " + after.length() + " 字（低于下限 " + floor
                    + " 字），保留初稿；建议换一条更强的通道";
            }
            if (!before.lengthOk() && !after.lengthOk()) {
                return "两版都没写够（" + before.length() + " / " + after.length() + " 字，目标 "
                    + targetWords + " 字），保留较长的那版；可把字数目标调低一点或换一条更强的通道";
            }
            return "修复版套话没变少（" + after.tells() + " 处），保留初稿：建议换一条更强的通道，"
                + "或到设置里把「口吻」改得更贴近自己账号";
        }
        var parts = new ArrayList<String>();
        if (adopt && !after.lengthOk()) {
            // 补也补不够：如实说清楚，别让「已自动补写」读起来像修好了
            return "两版都没写够（" + before.length() + " / " + after.length() + " 字，目标 "
                + targetWords + " 字），已取较长的那版；可把字数目标调低一点，"
                + "或换一条更强的通道";
        }
        if (!before.lengthOk()) {
            parts.add("初稿偏短（" + before.length() + " 字 → " + after.length() + " 字，目标 "
                + targetWords + " 字），已自动补写");
        }
        if (after.tells() < before.tells()) {
            parts.add("套话 " + before.tells() + " 处 → " + after.tells() + " 处");
        }
        return String.join("；", parts);
    }

    /**
     * 版本选择结果：采用哪一版，以及两版各自的体检数据（供页面提示与单测断言）。
     *
     * @param chosen        最终采用的那一版
     * @param repaired      是否换成了修复版
     * @param chosenTells   采用版的套话命中数
     * @param chosenLength  采用版的字数
     * @param originalTells 初稿的套话命中数
     * @param originalLength 初稿的字数
     * @param note          给站长看的一句话（写进记录的 note）
     */
    public record VersionChoice(Generated chosen, boolean repaired, int chosenTells,
                                int chosenLength, int originalTells, int originalLength,
                                String note) {
    }

    /**
     * 解析成稿结果，全容错。
     *
     * <p>模型偶发不按 JSON 回（套代码块、加了说明文字、引号破坏结构）时不做「当作失败」处理：
     * 首行当标题、其余当正文——一次成功的调用不应该因为格式问题丢掉稿子，
     * 站长在页面上看到内容不对可以直接重生成。</p>
     *
     * @param titleCount 本次要的标题候选个数：模型多回几个时按它收敛（页面上就是按这个数展示的）
     */
    public static Generated parseResult(String aiText, int titleCount) {
        var raw = aiText == null ? "" : aiText.trim();
        if (!StringUtils.hasText(raw)) {
            return Generated.empty();
        }
        var titleLimit = Math.max(1, Math.min(MAX_TITLES, titleCount));
        try {
            var json = DigestAiHelper.parseJson(raw);
            var titles = readTitles(json.path("titles"), titleLimit);
            var body = json.path("body").asText("").trim();
            if (!StringUtils.hasText(body)) {
                // JSON 合法但没有正文：这是「模型没写出稿子」，不是格式问题——
                // 不能像解析失败那样把整段 JSON 文本当正文存下来（那会是一份垃圾稿）
                return new Generated(titles, "", readTags(json.path("tags")));
            }
            return new Generated(titles, stripMarkdownHeadings(body), readTags(json.path("tags")));
        } catch (Exception ex) {
            return fallbackFromPlainText(raw);
        }
    }

    /** 解析失败时的兜底：首行当标题（去 Markdown 标题号与「标题：」前缀），其余当正文 */
    private static Generated fallbackFromPlainText(String raw) {
        var text = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").trim();
        var lines = text.split("\\R", 2);
        var first = tidyTitle(lines[0].trim().replaceFirst("^#+\\s*", "")
            .replaceFirst("^标题[：:]\\s*", ""));
        var rest = lines.length > 1 ? lines[1].trim() : "";
        if (!StringUtils.hasText(rest)) {
            // 只有一段：当正文处理，标题留空（页面会提示重新生成）
            return new Generated(List.of(), stripMarkdownHeadings(text), List.of());
        }
        return new Generated(first.isEmpty() ? List.of() : List.of(first),
            stripMarkdownHeadings(rest), List.of());
    }

    /** 标题候选：逐个清洗（去 Markdown/引号、按平台上限截断）、去重、按本次要的个数收敛 */
    private static List<String> readTitles(JsonNode node, int limit) {
        var result = new LinkedHashSet<String>();
        if (node == null || !node.isArray()) {
            return List.of();
        }
        for (var item : node) {
            var value = item == null ? "" : item.asText("").trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            var cleaned = tidyTitle(value);
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** 标签：逐个清洗（去 # 前缀与空格、截断）、去重、限量 */
    private static List<String> readTags(JsonNode node) {
        var result = new LinkedHashSet<String>();
        if (node == null || !node.isArray()) {
            return List.of();
        }
        for (var item : node) {
            var value = item == null ? "" : item.asText("").trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            var cleaned = tidyTag(value);
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
            if (result.size() >= MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** 标题清洗：去 Markdown 标记与包裹引号，超长截断 */
    private static String tidyTitle(String value) {
        var cleaned = stripMarkdown(value)
            .replaceAll("^[「『\"“]+", "")
            .replaceAll("[」』\"”]+$", "")
            .trim();
        if (cleaned.length() > MAX_TITLE_CHARS) {
            cleaned = cleaned.substring(0, MAX_TITLE_CHARS);
        }
        return cleaned;
    }

    /** 标签清洗：去掉 # 前缀与空格，超长截断 */
    private static String tidyTag(String value) {
        var cleaned = stripMarkdown(value).replaceFirst("^#+\\s*", "").replace("#", "").trim();
        if (cleaned.length() > MAX_TAG_CHARS) {
            cleaned = cleaned.substring(0, MAX_TAG_CHARS);
        }
        return cleaned;
    }

    /**
     * 去掉正文里的 Markdown 标题标记（{@code ## 小标题} → {@code 小标题}）。
     *
     * <p>提示词已要求不要 Markdown，但模型偶尔还是加；正文是要直接粘进头条编辑器的，
     * 留着 {@code #} 号很扎眼。只处理行首的标题号与粗体星号，不动其它内容。</p>
     */
    static String stripMarkdownHeadings(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        var lines = body.split("\\R", -1);
        var result = new ArrayList<String>(lines.length);
        for (var line : lines) {
            result.add(line.replaceFirst("^\\s{0,3}#{1,6}\\s+", ""));
        }
        return String.join("\n", result).trim();
    }

    private static String stripMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("**", "").replace("`", "").trim();
    }

    /** 成稿结果：标题候选（可能为空）+ 正文（为空表示本次没有可用内容）+ 标签 */
    public record Generated(List<String> titles, String body, List<String> tags) {

        public boolean hasBody() {
            return StringUtils.hasText(body);
        }

        public static Generated empty() {
            return new Generated(List.of(), "", List.of());
        }
    }
}
