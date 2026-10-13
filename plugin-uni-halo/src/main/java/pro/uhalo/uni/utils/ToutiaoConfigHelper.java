package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.ToutiaoConfigDTO;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「今日头条号」配置解析（设置分组 {@code toutiaoConfig}）。
 *
 * <p>容错原则与其它 ConfigHelper 一致：字段缺失或非法时回落默认值，不抛异常。
 * 字数与标题个数都收敛到安全区间——它们最终会写进提示词，配错只会浪费 token。</p>
 */
public final class ToutiaoConfigHelper {

    public static final String GROUP_NAME = "toutiaoConfig";

    /** 正文目标字数区间：下限保证稿子有信息量，上限防止一次调用把输出额度吃满 */
    private static final int MIN_WORDS = 300;
    private static final int MAX_WORDS = 3000;

    /** 风格清单：超过这个数量管理页下拉会变长，收益不大 */
    private static final int MAX_STYLES = 10;
    private static final int MAX_STYLE_CHARS = 12;

    /** 内置风格：清单留空时用它（与「分类清单留空回落内置」同一口径） */
    private static final List<String> BUILT_IN_STYLES =
        List.of("资讯综述", "深度解读", "干货教程", "情感故事");

    private ToutiaoConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /**
     * 默认值：功能开启。
     *
     * <p>与付费功能（默认关，避免露出买不了的入口）不同：这个页面只对管理员可见，
     * 误开也不会把任何东西暴露给小程序用户，所以开箱即用；站长要按成本控制时再关。</p>
     */
    public static ToutiaoConfigDTO defaults() {
        return ToutiaoConfigDTO.builder()
            .enabled(true)
            // 1500 字：模型总是写不到标称字数（下限按 85% 算 = 1275 字），低于这个数就不够撑一篇图文
            .words(1500)
            .titleCount(3)
            .styles(BUILT_IN_STYLES)
            .defaultStyle(BUILT_IN_STYLES.get(0))
            .voice(ToutiaoPromptHelper.DEFAULT_VOICE)
            .bannedPhrases("")
            .rewriteEnabled(true)
            .imageCount(ToutiaoImageHelper.MIN_IMAGES)
            .imageModel("")
            .imageChannelKey("")
            .imageSize(ToutiaoImageHelper.DEFAULT_SIZE)
            .imageStylePrompt(ToutiaoImageHelper.DEFAULT_STYLE_PROMPT)
            .build();
    }

    public static ToutiaoConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var fallback = defaults();
        var styles = parseStyles(node.path("styles").asText(""));
        var defaultStyle = node.path("defaultStyle").asText("").trim();
        if (!StringUtils.hasText(defaultStyle) || !styles.contains(defaultStyle)) {
            // 默认风格必须是清单里的一项：配了清单外的值就用第一项，
            // 否则管理页会出现一个「选中项不在下拉里」的怪状态
            defaultStyle = styles.isEmpty() ? "" : styles.get(0);
        }
        return ToutiaoConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(true))
            .words(clamp(node.path("words").asInt(fallback.getWords()), MIN_WORDS, MAX_WORDS))
            .titleCount(clamp(node.path("titleCount").asInt(fallback.getTitleCount()),
                1, ToutiaoPromptHelper.MAX_TITLES))
            .styles(styles)
            .defaultStyle(defaultStyle)
            // 口吻留空 = 内置第一人称口吻（去 AI 味的关键）；站长可以改成自己账号的视角
            .voice(text(node, "voice", ToutiaoPromptHelper.DEFAULT_VOICE))
            .bannedPhrases(text(node, "bannedPhrases", ""))
            .rewriteEnabled(node.path("rewriteEnabled").asBoolean(true))
            // 配图张数下限就是 3：这是产品口径（头条多图文章推荐表现更好），不允许配得更少
            .imageCount(clamp(node.path("imageCount").asInt(fallback.getImageCount()),
                ToutiaoImageHelper.MIN_IMAGES, ToutiaoImageHelper.MAX_IMAGES))
            .imageModel(text(node, "imageModel", ""))
            .imageChannelKey(text(node, "imageChannelKey", ""))
            .imageSize(text(node, "imageSize", ToutiaoImageHelper.DEFAULT_SIZE))
            .imageStylePrompt(text(node, "imageStylePrompt",
                ToutiaoImageHelper.DEFAULT_STYLE_PROMPT))
            .build();
    }

    private static String text(JsonNode node, String field, String fallback) {
        var value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    /**
     * 风格清单解析：逗号/顿号/分号/换行分隔，去重、限量、逐项截断。
     *
     * <p>留空 = 内置四种（与日报分类、公众号方向的「留空回落内置」同一口径）。</p>
     */    public static List<String> parseStyles(String raw) {
        if (raw == null || raw.isBlank()) {
            return BUILT_IN_STYLES;
        }
        var styles = new LinkedHashSet<String>();
        for (var piece : raw.split("[,，、;；\\n]")) {
            var value = piece.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_STYLE_CHARS) {
                value = value.substring(0, MAX_STYLE_CHARS);
            }
            styles.add(value);
            if (styles.size() >= MAX_STYLES) {
                break;
            }
        }
        return styles.isEmpty() ? BUILT_IN_STYLES : List.copyOf(styles);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /**
     * 入参收敛：管理页上每篇都能临时调整字数与标题个数，但不允许越过安全区间——
     * 它们最终会写进提示词，配错只会浪费 token。
     */
    public static int normalizeWords(int value) {
        return clamp(value, MIN_WORDS, MAX_WORDS);
    }

    public static int normalizeTitleCount(int value) {
        return clamp(value, 1, ToutiaoPromptHelper.MAX_TITLES);
    }
}
