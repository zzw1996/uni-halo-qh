package pro.uhalo.uni.dto;

import pro.uhalo.uni.enums.DigestAiTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大模型通道条目：插件设置 {@code aiProviders} 里的一行，也是后台「大模型通道」面板里的一条。
 *
 * <p>清单非空时 AI 调用只按清单走——数组顺序即优先级，第一条失败自动跳下一条，
 * {@link #enabled()} 决定这一条是否参与。清单留空则回落到旧的主 / 兜底两个字段，
 * 升级因此不会改变老站点的行为（见 {@code DigestAiChannelResolver}）。</p>
 *
 * <p>{@link #tasks()} 是这条通道<b>愿意承担哪些任务</b>：勾了「评分」的通道不会被用来成稿。
 * 空表示全部任务——这是兼容的关键：老配置、旧字段生成的兼容通道、以及不想细分的站长，
 * 行为都与「按任务绑定」上线前完全一致。</p>
 *
 * <p>同时用作「测试连通性」的请求体：后台可以把某一行原样发上来测，
 * 其中 Key 可能还是掩码串——{@link #isMasked(String)} 负责识别，
 * 服务端据此回查已保存的 Key，前端因此不必回传明文。</p>
 *
 * @param key     通道标识（稳定唯一，后台自动生成）。测试接口按它回指已保存的配置
 * @param label   显示名：日志与后台面板里用它区分通道（如「DeepSeek 主用」）
 * @param apiBase OpenAI 兼容的 chat/completions 端点（保存时去掉结尾的 /）
 * @param apiKey  密钥；列表接口下发前一律掩码
 * @param model   模型名；留空时按端点推断（见 {@code DigestAiChannelResolver#inferModel}）
 * @param enabled 启用开关：关掉的通道既不参与调用，也不参与故障转移
 * @param tasks   承担的任务标识（{@link DigestAiTask#key()}）；空 = 全部任务
 */
public record DigestAiProviderDTO(String key, String label, String apiBase, String apiKey,
                                  String model, boolean enabled, List<String> tasks) {

    /** 掩码里固定出现的一串字符：`isMasked` 与 `maskedApiKey` 共用同一份定义 */
    public static final String MASK = "••••";

    public DigestAiProviderDTO(String key, String label, String apiBase, String apiKey, String model,
        boolean enabled) {
        this(key, label, apiBase, apiKey, model, enabled, List.of());
    }

    public DigestAiProviderDTO(String key, String label, String apiBase, String apiKey, String model) {
        this(key, label, apiBase, apiKey, model, true, List.of());
    }

    /** 开关打开、端点与 Key 都配好了，这一条才可能被真正调用 */
    public boolean usable() {
        return enabled && hasText(apiBase) && hasKey();
    }

    public boolean hasKey() {
        return hasText(apiKey);
    }

    /**
     * 这条通道是否承担某个任务。
     *
     * <p>没勾任何用途 = 什么任务都接：这样「清单里只填了端点与 Key、没动用途」的配置
     * 与改造前行为一致，不会出现「加了这个功能之后一条通道都用不上」。</p>
     */
    public boolean handles(DigestAiTask task) {
        if (task == null || taskList().isEmpty()) {
            return true;
        }
        return taskList().contains(task.getKey());
    }

    /** 归一化后的用途列表：去空、去重、去非法值，顺序保持站长勾选的顺序 */
    public List<String> taskList() {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (var raw : tasks) {
            DigestAiTask.fromKey(raw).ifPresent(task -> unique.add(task.getKey()));
        }
        return List.copyOf(unique);
    }

    /** 用途的中文名列表（后台面板与错误信息里展示用） */
    public List<String> taskLabels() {
        var result = new ArrayList<String>();
        for (var key : taskList()) {
            result.add(DigestAiTask.labelOf(key));
        }
        return List.copyOf(result);
    }

    /**
     * 掩码后的 Key（后台面板展示用）。
     *
     * <p>保留头 3 位与尾 4 位：站长要能认出「这是哪一把 Key」，又不能让它整份出现在页面上。
     * 太短的 Key 一律整体掩掉——短串里露头露尾等于全露。</p>
     */
    public String maskedApiKey() {
        var key = text(apiKey);
        if (key.isEmpty()) {
            return "";
        }
        if (key.length() < 8) {
            return MASK;
        }
        return key.substring(0, 3) + MASK + key.substring(key.length() - 4);
    }

    /** 提交上来的 Key 是不是「没改过」的掩码串（是则保留服务端已存的那把） */
    public static boolean isMasked(String value) {
        return value != null && value.contains(MASK);
    }

    public DigestAiProviderDTO withEnabled(boolean value) {
        return new DigestAiProviderDTO(key, label, apiBase, apiKey, model, value, tasks);
    }

    /** 换一份 Key（保存时按掩码规则保留原 Key 用） */
    public DigestAiProviderDTO withApiKey(String value) {
        return new DigestAiProviderDTO(key, label, apiBase, value, model, enabled, tasks);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
