package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 大模型供应商预设：内置主流厂商的 OpenAI 兼容入口（API Base + 常见模型名）。
 *
 * <p>为什么需要这张表：主流大模型（智谱、DeepSeek、通义、Kimi、豆包、Gemini…）
 * 现在全都实现了 OpenAI 的 {@code /chat/completions} 协议，插件客户端一套代码就能通吃——
 * 真正的差别只剩「API Base 的路径各家不一样」和「模型名的写法各家不一样」。
 * 站长最容易栽在这两处：把完整接口地址当 Base 填（多了 {@code /chat/completions}）、
 * 或只填了域名（少了 {@code /api/paas/v4} 这类版本路径），得到一个 404 就以为「不支持」。</p>
 *
 * <p>这张表有三处用途：</p>
 * <ul>
 *   <li>控制台通道面板的「供应商」下拉：选中即自动填 API Base；</li>
 *   <li>保存/调用前的地址纠偏（{@link #normalizeApiBase}）；</li>
 *   <li>连通性测试 404 时的针对性提示（{@link #hintFor}：这个域名是哪家的、Base 该怎么填）。</li>
 * </ul>
 */
public final class AiProviderPresets {

    /** 一家供应商的 OpenAI 兼容入口 */
    public record Preset(String key, String label, String apiBase, String models, String note) {
    }

    /**
     * 内置预设（按国内常用度排序）。
     *
     * <p>模型名是「常见示例」而不是完整清单——模型迭代太快，这里只保证
     * 示例在写下时真实存在，具体可用清单以各家控制台为准。</p>
     */
    public static final List<Preset> ALL = List.of(
        new Preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            "glm-5.3-flash / glm-4.6 / glm-4-flash",
            "glm-4-flash 便宜，适合评分这类小任务；glm-5.3 起思考强制开启，插件会自动按 low 降档"),
        new Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            "deepseek-chat / deepseek-reasoner", ""),
        new Preset("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-max / qwen-plus / qwen-turbo",
            "必须用「兼容模式」这个地址，不是百炼的原生地址"),
        new Preset("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1",
            "moonshot-v1-8k / kimi-k2-0711-preview", ""),
        new Preset("volc", "火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3",
            "方舟控制台创建的接入点 ID（ep- 开头）",
            "模型名填接入点 ID，不是 doubao 型号名"),
        new Preset("siliconflow", "SiliconFlow 硅基流动", "https://api.siliconflow.cn/v1",
            "deepseek-ai/DeepSeek-V3 / Qwen/Qwen3-8B",
            "模型名带「供应商/模型」前缀"),
        new Preset("openai", "OpenAI", "https://api.openai.com/v1",
            "gpt-4o / gpt-4o-mini", ""),
        new Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash / gemini-2.5-pro",
            "用 Google 官方提供的 OpenAI 兼容地址"),
        new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "openai/gpt-4o-mini / deepseek/deepseek-chat",
            "模型名带「供应商/模型」前缀"),
        new Preset("groq", "Groq", "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile", ""),
        new Preset("minimax", "MiniMax", "https://api.minimax.chat/v1",
            "abab6.5s-chat", ""),
        new Preset("qianfan", "百度千帆", "https://qianfan.baidubce.com/v2",
            "ernie-4.0-turbo-8k 等", "用千帆 v2 接口（OpenAI 兼容）"),
        new Preset("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1",
            "hunyuan-turbo / hunyuan-lite", ""),
        new Preset("ollama", "Ollama（本机）", "http://127.0.0.1:11434/v1",
            "qwen3 / llama3.1（按 ollama list）",
            "仅当 Halo 与 Ollama 在同一台机器（或内网可达）时可用"));

    private AiProviderPresets() {
    }

    /**
     * API Base 归一化：去首尾空格、去结尾斜杠，并把「完整接口地址」纠正成 Base——
     * 站长最常见的填错方式就是直接把文档里的
     * {@code https://…/v1/chat/completions} 整条粘进来。
     */
    public static String normalizeApiBase(String raw) {
        var base = DigestAiChannelResolver.normalizeBase(raw);
        if (base.isEmpty()) {
            return "";
        }
        if (base.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length())
                .replaceAll("/+$", "");
        }
        return base;
    }

    /** 按域名识别供应商：主机名命中即算（不看路径——路径错了才需要这张表来救） */
    public static Optional<Preset> byHost(String apiBase) {
        var host = DigestAiChannelResolver.hostOf(normalizeApiBase(apiBase));
        if (host.isEmpty()) {
            return Optional.empty();
        }
        var normalizedHost = host.toLowerCase(Locale.ROOT);
        return ALL.stream()
            .filter(preset -> DigestAiChannelResolver.hostOf(preset.apiBase())
                .toLowerCase(Locale.ROOT).equals(normalizedHost))
            .findFirst();
    }

    /**
     * chat/completions 的完整地址：apiBase 先归一化（去尾斜杠），再把用户误填的
     * 「完整接口地址」纠正成 Base——把 {@code https://…/v1/chat/completions} 整条粘进
     * API Base 是最常见的填错方式，直接拼会变成 {@code …/chat/completions/chat/completions}。
     *
     * <p>客户端与连通性测试都用这里拼出来的地址；纯函数，方便单测钉住各家的填法。</p>
     */
    public static String chatCompletionsUrl(String apiBase) {
        var base = normalizeApiBase(apiBase);
        if (!StringUtils.hasText(base)) {
            return "/chat/completions";
        }
        if (base.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /**
     * 针对性提示：识别出供应商时告诉站长「这家的 Base 应该怎么填」；认不出返回空串。
     *
     * <p>用在连通性测试的 404 文案里——「少了 /v1」这种通用提示对智谱（/api/paas/v4）、
     * 通义（/compatible-mode/v1）、方舟（/api/v3）都不适用，按家说才有效。</p>
     */
    public static String hintFor(String apiBase) {
        var preset = byHost(apiBase).orElse(null);
        if (preset == null) {
            return "";
        }
        var current = normalizeApiBase(apiBase);
        var builder = new StringBuilder("这个域名是「").append(preset.label())
            .append("」的接口，API Base 应填 ").append(preset.apiBase());
        if (!current.equalsIgnoreCase(preset.apiBase())) {
            builder.append("（你填的是 ").append(current).append("）");
        }
        builder.append("，模型名如 ").append(preset.models());
        if (StringUtils.hasText(preset.note())) {
            builder.append("；").append(preset.note());
        }
        return builder.toString();
    }
}
