package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「思考 / 推理」参数按模型族的策略。
 *
 * <p>必须按代际分档，而不是「新模型统一发 {@code thinking.type=disabled}」一次了事：
 * 智谱这几年把思考的默认值一路从「关」推到「开」、再推到<b>不许关</b>。GLM-4.5~5.2 接受显式关闭；
 * GLM-5.3 与 GLM-5.3-Flash 起思考强制开启，传 {@code disabled} 不会被忽略、也不会降级，
 * 而是直接 400（业务码 1210「该模型始终思考，不支持关闭思考；请使用 low、high 或 max」）——
 * <b>一次请求都发不出去</b>。日报成稿是 10 篇一批的长 JSON，这条 400 会让整批拆半重试、
 * 单篇补请求，最后全塌到 RSS 描述兜底；站长看到的正是「换了强模型，文章反而更差」。</p>
 *
 * <p>参数形状（已核对智谱官方文档）：{@code thinking} 是对象，{@code reasoning_effort} 是
 * <b>请求体顶层</b>字段而不是 thinking 的子字段。GLM-5.3/5.3-Flash 只有 {@code low}、
 * {@code high}、{@code max} 三档且<b>默认 {@code max}</b>——不显式降档，思考会把正文预算
 * 吃得一干二净，所以「关不掉」时必须同时做两件事：改 {@code enabled} + 降档。</p>
 *
 * <p>这里只做纯字符串判断，不发请求、不依赖运行时，因此可以逐条单测钉住。</p>
 */
public final class AiThinkingPolicy {

    /** 一条模型名对思考参数的支持方式 */
    public enum Stance {
        /** 没有 thinking 这个参数（GLM-4 那一代、以及其它供应商）：多发字段只添风险 */
        NONE,
        /** 支持显式关闭：思考 token 与正文共享 max_tokens，关掉才不挤占成稿预算 */
        DISABLE,
        /** 思考强制开启：只能降档，不能关 */
        ALWAYS_ON
    }

    /**
     * 降档取值。{@code low} 是 GLM-5/5.1/5.2/5.3/5.3-Flash 全都认的那一个：
     * 5.3 系只有 low/high/max，5 系到 5.1 根本没有 max，写死成任一代的专有值都会撞别的代。
     */
    public static final String EFFORT = "low";

    /**
     * glm- 之后紧跟的版本号：glm-5.3-flash → 5 / 3，glm-4.6 → 4 / 6，glm-5 → 5 / 无。
     *
     * <p>小版本号后面不许直接跟字母：开源那批按参数量命名（{@code glm-4-9b-chat}），
     * 「4-9」会被读成 4.9 而误判成带 thinking 的新一代；接口版的小版本号后面只会是
     * 连字符或结尾。</p>
     */
    private static final Pattern GLM_VERSION = Pattern.compile("^glm-(\\d+)(?:[.\\-](\\d+)(?![a-z]))?");

    /** 「该模型不接受关闭思考」的报错特征：智谱中/英文文案，以及常见中转站的转述 */
    private static final String[] REJECTED_MARKERS = {
        "始终思考", "不支持关闭思考", "always think", "cannot be disabled",
        "not support disabling", "does not support disabled"};

    private AiThinkingPolicy() {
    }

    /** 模型名归一：小写、去中转站前缀（{@code ZHIPU/GLM-5.3-Flash} → {@code glm-5.3-flash}）、下划线当连字符 */
    public static String canonicalName(String model) {
        if (!StringUtils.hasText(model)) {
            return "";
        }
        var name = model.trim().toLowerCase(Locale.ROOT);
        var slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name.replace('_', '-');
    }

    /**
     * 这条模型名该按哪种方式处理思考参数。
     *
     * <p>6 代起一律按 {@code ALWAYS_ON} 处理：官方没有回头允许关闭的迹象，方向只会更强制；
     * 万一某条 6 代模型其实能关，多花的只是思考 token，而判反的代价是整批请求 400。</p>
     */
    public static Stance stance(String model) {
        var matcher = GLM_VERSION.matcher(canonicalName(model));
        if (!matcher.find()) {
            return Stance.NONE;
        }
        var major = Integer.parseInt(matcher.group(1));
        var minor = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
        if (major < 4) {
            return Stance.NONE;
        }
        if (major == 4) {
            return minor >= 5 ? Stance.DISABLE : Stance.NONE;
        }
        return major == 5 && minor < 3 ? Stance.DISABLE : Stance.ALWAYS_ON;
    }

    /** 该策略要往请求体里加的字段（{@code NONE} 时为空，不往请求体塞多余键） */
    public static Map<String, Object> requestFields(Stance stance) {
        var fields = new LinkedHashMap<String, Object>();
        if (stance == Stance.DISABLE) {
            fields.put("thinking", Map.of("type", "disabled"));
        } else if (stance == Stance.ALWAYS_ON) {
            fields.put("thinking", Map.of("type", "enabled"));
            fields.put("reasoning_effort", EFFORT);
        }
        return fields;
    }

    /**
     * 失败是否属于「这条模型关不掉思考」。
     *
     * <p>只认这句话，别把其它 400（Key、参数、超长）也拖去重发一次：那些重试同样会失败，
     * 只是把一份报错变成两份。</p>
     */
    public static boolean thinkingSwitchRejected(int status, String body) {
        if (status != 400 || !StringUtils.hasText(body)) {
            return false;
        }
        var text = body.toLowerCase(Locale.ROOT);
        for (var marker : REJECTED_MARKERS) {
            if (text.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
