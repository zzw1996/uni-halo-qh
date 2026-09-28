package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestCategoryConfigDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日报分类目录：**分类体系的唯一事实来源**。
 *
 * <p>改造前分类清单硬编码在三处（AI prompt 的白名单、渲染用的中文名/emoji 表、存量归类的校验），
 * 站长只能开关「是否自动归类」，不能改这份清单。现在统一收口到这里：</p>
 * <ul>
 *   <li><b>配置优先</b>：站长在后台「分类清单」里配了什么，AI 就只能从里面选，
 *       生成文章时的分类名/图标/说明也都按它来；</li>
 *   <li><b>空配置回落内置六类</b>：清单留空（或全是脏数据）时等于内置的那套，
 *       升级后不配置的站点行为与改造前完全一致；</li>
 *   <li><b>旧数据仍能显示</b>：{@link #labelOf}/{@link #emojiOf} 对清单外的 key
 *       （例如已有站点分类名、或改造前生成的旧分类）回落到内置表，再退到 key 本身，
 *       所以历史日报文章不会出现「分类名空白」。</li>
 * </ul>
 *
 * <p>消毒规则（配置是站长手输的，必须当作不可信输入）：key 只允许 {@code [a-z0-9_-]}、
 * 重复 key 只取第一条、label 为空时用内置表或 key 兜底、总数上限 {@value #MAX_CATEGORIES}。</p>
 */
public final class DigestCategoryCatalog {

    /** 清单里最多几个分类：太多会让 prompt 变长、也让 AI 选择变难 */
    public static final int MAX_CATEGORIES = 12;

    /** key 只允许小写字母/数字/下划线/短横线，避免拼进 prompt 或 slug 时出幺蛾子 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    /** 兜底分类的 key：AI 给出清单外的分类且策略是 other 时落到它 */
    public static final String FALLBACK_KEY = "other";
    private static final String FALLBACK_EMOJI = "📝";

    /**
     * 分类为空时的显示名。
     *
     * <p>两种情况下分类会是空的：AI 没给出有效分类且策略是「不打分类」，或者站长把兜底分类
     * 从清单里删掉了。这时如果直接渲染空字符串，日报里会出现一个没有标题的分组，
     * 所以统一给个「未分类」。</p>
     */
    public static final String UNCATEGORIZED_LABEL = "未分类";

    /** 内置清单：与改造前的六类一致，留空配置时使用 */
    private static final List<DigestCategoryConfigDTO> BUILT_IN = List.of(
        new DigestCategoryConfigDTO("ai-ml", "AI / ML", "🤖", "AI、机器学习、大模型、深度学习相关", ""),
        new DigestCategoryConfigDTO("security", "安全", "🔒", "安全、隐私、漏洞、加密相关", ""),
        new DigestCategoryConfigDTO("engineering", "工程", "⚙️", "软件工程、架构、编程语言、系统设计", ""),
        new DigestCategoryConfigDTO("tools", "工具 / 开源", "🛠", "开发工具、开源项目、新发布的库与框架", ""),
        new DigestCategoryConfigDTO("opinion", "观点 / 杂谈", "💡", "行业观点、个人思考、职业发展与文化评论", ""),
        new DigestCategoryConfigDTO(FALLBACK_KEY, "其他", FALLBACK_EMOJI, "暂时不适合归入其余主题的内容", ""));

    private final List<DigestCategoryConfigDTO> categories;
    private final Map<String, DigestCategoryConfigDTO> byKey;
    private final Map<String, DigestCategoryConfigDTO> builtInByKey;
    private final boolean fromConfig;

    private DigestCategoryCatalog(List<DigestCategoryConfigDTO> categories, boolean fromConfig) {
        this.categories = List.copyOf(categories);
        var map = new LinkedHashMap<String, DigestCategoryConfigDTO>();
        this.categories.forEach(item -> map.put(item.key(), item));
        this.byKey = Map.copyOf(map);
        var builtIn = new LinkedHashMap<String, DigestCategoryConfigDTO>();
        BUILT_IN.forEach(item -> builtIn.put(item.key(), item));
        this.builtInByKey = Map.copyOf(builtIn);
        this.fromConfig = fromConfig;
    }

    /** 内置目录（清单留空时的等价物） */
    public static DigestCategoryCatalog defaultCatalog() {
        return new DigestCategoryCatalog(BUILT_IN, false);
    }

    /**
     * 按站长配置构建目录；清单为空或全被消毒丢弃时回落内置。
     */
    public static DigestCategoryCatalog from(List<DigestCategoryConfigDTO> configured) {
        var sanitized = sanitize(configured);
        return sanitized.isEmpty() ? defaultCatalog() : new DigestCategoryCatalog(sanitized, true);
    }

    /** 消毒：去空、key 规范化与合法性、去重、补 label、截断到上限 */
    static List<DigestCategoryConfigDTO> sanitize(List<DigestCategoryConfigDTO> configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<DigestCategoryConfigDTO>();
        var seen = new LinkedHashMap<String, Boolean>();
        for (var item : configured) {
            if (item == null) {
                continue;
            }
            var key = normalizeKey(item.key());
            if (key.isEmpty() || seen.containsKey(key)) {
                continue;
            }
            seen.put(key, Boolean.TRUE);
            result.add(new DigestCategoryConfigDTO(
                key,
                StringUtils.hasText(item.label()) ? item.label().trim() : defaultLabelOf(key),
                StringUtils.hasText(item.emoji()) ? item.emoji().trim() : defaultEmojiOf(key),
                StringUtils.hasText(item.description()) ? item.description().trim()
                    : (StringUtils.hasText(item.label()) ? item.label().trim() : defaultLabelOf(key)),
                item.hasTarget() ? item.targetCategory().trim() : ""));
            if (result.size() >= MAX_CATEGORIES) {
                break;
            }
        }
        return result;
    }

    static String normalizeKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        var key = raw.trim().toLowerCase(Locale.ROOT);
        return KEY_PATTERN.matcher(key).matches() ? key : "";
    }

    /** 清单（已消毒，顺序即后台填写顺序） */
    public List<DigestCategoryConfigDTO> categories() {
        return categories;
    }

    /** 清单里的所有 key */
    public List<String> keys() {
        return categories.stream().map(DigestCategoryConfigDTO::key).toList();
    }

    /** 是否来自站长配置（false = 用内置兜底） */
    public boolean fromConfig() {
        return fromConfig;
    }

    /** key 是否在清单内（大小写不敏感、会先规范化） */
    public boolean contains(String key) {
        return byKey.containsKey(normalizeKey(key));
    }

    public DigestCategoryConfigDTO find(String key) {
        return byKey.get(normalizeKey(key));
    }

    /**
     * 规范化 AI 给出的分类 key：在清单内才返回它，否则返回空串。
     *
     * <p>「清单外的分类怎么办」是调用方的事（见 {@code unknownCategoryPolicy}）：
     * 这里只回答「是不是我们认识的分类」，不替它做决定。</p>
     */
    public String normalize(String key) {
        var normalized = normalizeKey(key);
        return byKey.containsKey(normalized) ? normalized : "";
    }

    /** 兜底分类的 key（清单里有 {@code other} 才有值；没有则返回空，等效于「不打分类」） */
    public String fallbackKey() {
        return byKey.containsKey(FALLBACK_KEY) ? FALLBACK_KEY : "";
    }

    /** 中文名：清单 → 内置表 → key 本身（旧分类不会显示成空白） */
    public String labelOf(String key) {
        if (!StringUtils.hasText(key)) {
            return UNCATEGORIZED_LABEL;
        }
        var normalized = normalizeKey(key);
        var configured = byKey.get(normalized);
        if (configured != null) {
            return configured.label();
        }
        var builtIn = builtInByKey.get(normalized);
        return builtIn != null ? builtIn.label() : key;
    }

    /** 图标：清单 → 内置表 → 统一兜底图标 */
    public String emojiOf(String key) {
        if (!StringUtils.hasText(key)) {
            return FALLBACK_EMOJI;
        }
        var normalized = normalizeKey(key);
        var configured = byKey.get(normalized);
        if (configured != null && StringUtils.hasText(configured.emoji())) {
            return configured.emoji();
        }
        var builtIn = builtInByKey.get(normalized);
        return builtIn != null && StringUtils.hasText(builtIn.emoji())
            ? builtIn.emoji() : FALLBACK_EMOJI;
    }

    /** 说明：清单 → 内置表 → 中文名 */
    public String descriptionOf(String key) {
        if (!StringUtils.hasText(key)) {
            return UNCATEGORIZED_LABEL;
        }
        var normalized = normalizeKey(key);
        var configured = byKey.get(normalized);
        if (configured != null && StringUtils.hasText(configured.description())) {
            return configured.description();
        }
        var builtIn = builtInByKey.get(normalized);
        if (builtIn != null && StringUtils.hasText(builtIn.description())) {
            return builtIn.description();
        }
        return labelOf(key);
    }

    /**
     * 生成 AI prompt 里的「分类标签（必须从以下选一个）」段落。
     *
     * <p>清单有几类就列几类——这是「AI 只能从站长配置的分类里选」的落点。</p>
     */
    public String promptSection() {
        var builder = new StringBuilder();
        for (var item : categories) {
            builder.append("- ").append(item.key()).append(": ")
                .append(StringUtils.hasText(item.description()) ? item.description() : item.label())
                .append('\n');
        }
        return builder.toString();
    }

    /** prompt 里 JSON 示例用的示例分类（取清单第一项，保证示例一定合法） */
    public String sampleKey() {
        return categories.isEmpty() ? FALLBACK_KEY : categories.get(0).key();
    }

    private static String defaultLabelOf(String key) {
        var builtIn = BUILT_IN.stream().filter(item -> item.key().equals(key)).findFirst();
        return builtIn.map(DigestCategoryConfigDTO::label).orElse(key);
    }

    private static String defaultEmojiOf(String key) {
        var builtIn = BUILT_IN.stream().filter(item -> item.key().equals(key)).findFirst();
        return builtIn.map(DigestCategoryConfigDTO::emoji).orElse(FALLBACK_EMOJI);
    }
}
