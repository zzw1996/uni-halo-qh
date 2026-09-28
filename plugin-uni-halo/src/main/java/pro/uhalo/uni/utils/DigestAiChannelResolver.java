package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 大模型通道解析：把配置归一成「一份有序的通道列表」，并回答「现在生效的是哪一条」。
 *
 * <p>这个类是通道口径的<b>唯一来源</b>：AI 客户端按它给的顺序做故障转移，后台面板按它算结论，
 * 测试接口按它定位已保存的 Key，日志按它报模型名。如果这几处各写一份判断，
 * 迟早会出现「面板说在用 A、实际调用走了 B」这种只能靠翻日志发现的问题。</p>
 *
 * <p><b>兼容规则</b>：{@code aiProviders} 清单非空 → 只用清单；
 * 留空 → 用旧的主 / 兜底字段（把它们当成清单的两条虚拟通道），行为与改造前一致。</p>
 */
public final class DigestAiChannelResolver {

    /** 兼容通道（旧字段）的标识：后台面板会把它们显示成清单外的通道 */
    public static final String LEGACY_PRIMARY_KEY = "legacy-primary";
    public static final String LEGACY_FALLBACK_KEY = "legacy-fallback";

    /** 兜底端点与模型名：与改造前的硬编码默认值保持一致，改这里等于改所有老站点的行为 */
    public static final String DEFAULT_FALLBACK_BASE = "https://api.openai.com/v1";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

    private DigestAiChannelResolver() {
    }

    /** 站长是否接管了通道配置（清单非空即为接管，旧字段不再生效） */
    public static boolean fromList(DigestConfigDTO config) {
        return config != null && config.getAiProviders() != null
            && !config.getAiProviders().isEmpty();
    }

    /** 配置里声明的全部通道（含被关掉、缺 Key 的），顺序即故障转移顺序 */
    public static List<DigestAiProviderDTO> channels(DigestConfigDTO config) {
        if (config == null) {
            return List.of();
        }
        if (!fromList(config)) {
            return legacyChannels(config);
        }
        var result = new ArrayList<DigestAiProviderDTO>();
        var index = 1;
        for (var provider : config.getAiProviders()) {
            if (provider == null) {
                continue;
            }
            result.add(withDefaults(provider, index++));
        }
        return List.copyOf(result);
    }

    /** 真正会被调用的通道：开关打开、端点与 Key 都配齐 */
    public static List<DigestAiProviderDTO> usableChannels(DigestConfigDTO config) {
        return channels(config).stream().filter(DigestAiProviderDTO::usable).toList();
    }

    public static boolean hasUsableChannel(DigestConfigDTO config) {
        return !usableChannels(config).isEmpty();
    }

    // ── 按任务解析（「评分用便宜模型、成稿用强模型」的落点） ──

    /**
     * 某个任务本轮实际会用的通道。
     *
     * @param task          任务
     * @param channels      勾了该任务的可用通道（按清单顺序），即故障转移顺序
     * @param fallback      true 表示没有通道勾选该任务，已<b>回退</b>到全部可用通道
     * @param fallbackFrom  回退时说明「本该用但没有」的原话，供面板与日志展示
     * @param note          一句话结论（没有可用通道时就是失败原因）
     */
    public record TaskPlan(DigestAiTask task, List<DigestAiProviderDTO> channels, boolean fallback,
                           String note) {

        public boolean usable() {
            return !channels.isEmpty();
        }

        public Optional<DigestAiProviderDTO> activeChannel() {
            return channels.stream().findFirst();
        }

        public String activeModel() {
            return activeChannel().map(DigestAiProviderDTO::model).orElse("");
        }

        public String activeLabel() {
            return activeChannel().map(DigestAiProviderDTO::label).orElse("");
        }
    }

    /**
     * 为某个任务挑通道，并在「没有通道勾选它」时**宽松回退**到全部可用通道。
     *
     * <p>回退而不是直接失败，是因为按用途绑定是<b>省钱手段</b>，不该变成可用性风险：
     * 只勾了「评分」的那条便宜通道挂了，若因此让整轮日报失败（哪怕成稿用的强模型完全正常），
     * 站长付出的代价远大于「这一轮贵了一点」。回退一定会写进 note，面板顶部与启动日志都能看到，
     * 不会悄悄花掉不该花的钱。</p>
     */
    public static TaskPlan plan(DigestConfigDTO config, DigestAiTask task) {
        var usable = usableChannels(config);
        var dedicated = usable.stream().filter(provider -> provider.handles(task)).toList();
        if (!dedicated.isEmpty()) {
            var used = dedicated.size() == 1
                ? "只有 1 条通道承担「" + task.getLabel() + "」，它挂了本轮该步骤就会失败"
                : "共 " + dedicated.size() + " 条通道承担「" + task.getLabel() + "」，按清单顺序故障转移";
            return new TaskPlan(task, dedicated, false,
                task.getLabel() + " → " + dedicateLabel(dedicated) + "（" + used + "）");
        }
        if (usable.isEmpty()) {
            return new TaskPlan(task, List.of(), false, noUsableChannelMessage(config, task));
        }
        return new TaskPlan(task, usable, true,
            "「" + task.getLabel() + "」没有通道勾选它，已回退到全部可用通道："
                + usable.get(0).label() + "（" + usable.get(0).model() + "）");
    }

    private static String dedicateLabel(List<DigestAiProviderDTO> channels) {
        var first = channels.get(0);
        return first.label() + "（" + first.model() + "）"
            + (channels.size() > 1 ? " 等 " + channels.size() + " 条" : "");
    }

    /** 某个任务有没有通道可用（含宽松回退，与运行时同口径） */
    public static boolean hasUsableChannel(DigestConfigDTO config, DigestAiTask task) {
        return plan(config, task).usable();
    }

    /**
     * 某个任务当前生效的模型名 / 端点。
     *
     * <p>回显必须按任务算：归类页显示的模型名要与归类真正调用的那条一致，
     * 否则站长会按一个错误的模型名去排查问题。</p>
     */
    public static String activeModel(DigestConfigDTO config, DigestAiTask task) {
        return plan(config, task).activeModel();
    }

    public static String activeApiBase(DigestConfigDTO config, DigestAiTask task) {
        return plan(config, task).activeChannel().map(DigestAiProviderDTO::apiBase).orElse("");
    }

    /** 一行串起 5 个任务的生效情况：启动日志与后台面板顶部共用同一句话 */
    public static String describeTasks(DigestConfigDTO config) {
        var parts = new ArrayList<String>();
        for (var task : DigestAiTask.values()) {
            var plan = plan(config, task);
            if (!plan.usable()) {
                parts.add(task.getLabel() + "=无可用通道");
                continue;
            }
            var first = plan.activeChannel().orElseThrow();
            parts.add(task.getLabel() + "=" + first.label() + "@" + first.model()
                + (plan.fallback() ? "(回退)" : ""));
        }
        return String.join("；", parts);
    }

    /** 某个任务没有可用通道时的报错文案（措辞与整体版一致，但要指出是哪个任务） */
    public static String noUsableChannelMessage(DigestConfigDTO config, DigestAiTask task) {
        return "任务「" + task.getLabel() + "」没有可用通道：" + noUsableChannelMessage(config);
    }

    /** 当前生效的通道：第一条可用的（故障转移链的起点） */
    public static Optional<DigestAiProviderDTO> activeChannel(DigestConfigDTO config) {
        return usableChannels(config).stream().findFirst();
    }

    /**
     * 当前生效的模型名 / 端点。
     *
     * <p>旧代码直接读 {@code config.getAiModel()} 来展示，清单生效后那个值是空或过期的，
     * 站长据此判断「是不是走了兜底通道」会得出错误结论，所以统一从这里取。</p>
     */
    public static String activeModel(DigestConfigDTO config) {
        return activeChannel(config).map(DigestAiProviderDTO::model).orElse("");
    }

    public static String activeApiBase(DigestConfigDTO config) {
        return activeChannel(config).map(DigestAiProviderDTO::apiBase).orElse("");
    }

    /** 面板顶部那句结论：与其它地方共用，避免「页面说的」和「日志说的」不一致 */
    public static String describe(DigestConfigDTO config) {
        var usable = usableChannels(config);
        if (!fromList(config)) {
            return usable.isEmpty()
                ? "当前没有可用的大模型通道：下面添加通道并填写 API Key 即可（也可以继续用旧的主 / 兜底通道）"
                : "通道清单留空，正在沿用旧的主 / 兜底通道：" + usable.get(0).label()
                    + "（" + usable.get(0).model() + "）";
        }
        if (usable.isEmpty()) {
            return "通道清单里有 " + channels(config).size() + " 条，但没有一条可用："
                + "要么开关被关掉，要么缺 API Base / API Key";
        }
        if (usable.size() == 1) {
            return "当前生效：" + usable.get(0).label() + "（" + usable.get(0).model()
                + "），只有一条可用通道，它挂了本轮就失败";
        }
        return "当前生效：" + usable.get(0).label() + "（" + usable.get(0).model() + "），"
            + "共 " + usable.size() + " 条可用通道按顺序故障转移";
    }

    /**
     * 一条可用通道都没有时的报错文案。
     *
     * <p>必须说清是「全关了」还是「缺 Key」：这两种情况的处置动作完全不同，
     * 笼统报一句「未配置 API Key」会让站长对着一个 Key 明明填了的页面反复检查。</p>
     */
    public static String noUsableChannelMessage(DigestConfigDTO config) {
        var all = channels(config);
        if (all.isEmpty()) {
            return "未配置 AI 接口：请在插件设置「AI 日报」中填写 API Key，"
                + "或在「大模型通道」里添加至少一条通道";
        }
        var reasons = new ArrayList<String>();
        for (var provider : all) {
            if (!provider.enabled()) {
                reasons.add("「" + provider.label() + "」已关闭");
            } else if (!provider.hasKey()) {
                reasons.add("「" + provider.label() + "」缺 API Key");
            } else if (!StringUtils.hasText(provider.apiBase())) {
                reasons.add("「" + provider.label() + "」缺 API Base");
            }
        }
        return "大模型通道全部不可用（" + String.join("；", reasons) + "）";
    }

    /** 端点归一：去空格、去结尾的 /（`.../v1/` 与 `.../v1` 是同一个地址） */
    public static String normalizeBase(String base) {
        if (!StringUtils.hasText(base)) {
            return "";
        }
        return base.trim().replaceAll("/+$", "");
    }

    /** 模型名推断：模型名留空时按端点猜一个，避免直接把空模型名发出去 */
    public static String inferModel(String base) {
        if (base != null && base.toLowerCase(Locale.ROOT).contains("deepseek")) {
            return DEFAULT_DEEPSEEK_MODEL;
        }
        return DEFAULT_OPENAI_MODEL;
    }

    /** 端点的主机名：通道显示名的兜底、报错文案里指代服务用 */
    public static String hostOf(String base) {
        var normalized = normalizeBase(base);
        if (normalized.isEmpty()) {
            return "";
        }
        var withoutScheme = normalized.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        var slash = withoutScheme.indexOf('/');
        return slash < 0 ? withoutScheme : withoutScheme.substring(0, slash);
    }

    /** 通道显示名：优先用站长填的，其次用主机名，最后用序号 */
    public static String labelOf(DigestAiProviderDTO provider, int index) {
        if (provider == null) {
            return "";
        }
        if (StringUtils.hasText(provider.label())) {
            return provider.label().trim();
        }
        var host = hostOf(provider.apiBase());
        return host.isEmpty() ? "通道 " + index : host;
    }

    private static DigestAiProviderDTO withDefaults(DigestAiProviderDTO provider, int index) {
        var base = normalizeBase(provider.apiBase());
        var model = StringUtils.hasText(provider.model())
            ? provider.model().trim() : inferModel(base);
        var label = labelOf(provider, index);
        var key = provider.key() == null ? "" : provider.key().trim();
        // 用途必须原样带过来：这里是所有通道的必经之路，漏了就变成
        // 「配置里勾了用途，运行时不认」——只能靠读日志发现的错
        return new DigestAiProviderDTO(key, label, base, provider.apiKey(), model, provider.enabled(),
            provider.tasks());
    }

    /**
     * 旧的主 / 兜底字段 → 两条虚拟通道。
     *
     * <p>只有「端点 + Key 都在」才产出：主通道没填 Key 时直接走兜底，与改造前
     * 「主通道没有 Key 就改用兜底」的判断结果一致。兜底端点留空时沿用 OpenAI 默认值。</p>
     */
    private static List<DigestAiProviderDTO> legacyChannels(DigestConfigDTO config) {
        var result = new ArrayList<DigestAiProviderDTO>();
        var primaryBase = normalizeBase(config.getAiApiBase());
        var primaryKey = text(config.getAiApiKey());
        if (!primaryBase.isEmpty() && !primaryKey.isEmpty()) {
            result.add(new DigestAiProviderDTO(LEGACY_PRIMARY_KEY, "兼容通道（主）", primaryBase,
                primaryKey, modelOrInfer(config.getAiModel(), primaryBase), true));
        }
        var fallbackKey = text(config.getFallbackApiKey());
        if (!fallbackKey.isEmpty()) {
            var fallbackBase = StringUtils.hasText(config.getFallbackApiBase())
                ? normalizeBase(config.getFallbackApiBase()) : DEFAULT_FALLBACK_BASE;
            result.add(new DigestAiProviderDTO(LEGACY_FALLBACK_KEY, "兼容通道（兜底）", fallbackBase,
                fallbackKey, modelOrInfer(config.getFallbackModel(), fallbackBase), true));
        }
        return List.copyOf(result);
    }

    private static String modelOrInfer(String model, String base) {
        return StringUtils.hasText(model) ? model.trim() : inferModel(base);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
