package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 把「大模型调用失败」翻译成站长能照着动手的结论。
 *
 * <p>同一个翻译器同时服务两处：运行时的降级日志（日报跑失败时报告里写的原因），
 * 以及后台「测试连通性」按钮。两处共用是刻意的——如果测试按钮说「Key 无效」
 * 而运行时说「通道调用失败」，站长会以为自己测了个假的东西。</p>
 *
 * <p>只做纯字符串判断，不发请求、不依赖 Halo 运行时，因此可以逐条单测钉住文案。</p>
 */
public final class DigestAiErrorExplainer {

    /** 展示用：响应体 / 异常信息的截断长度 */
    private static final int SNIPPET_MAX = 200;

    private DigestAiErrorExplainer() {
    }

    /**
     * 有 HTTP 响应时的结论。
     *
     * @param status HTTP 状态码（null = 没有拿到响应，例如连接失败）
     * @param body   响应体（用于判断「模型名不存在」与展示片段）
     * @param base   端点地址（用于提示是否少了 /v1）
     * @param model  请求的模型名
     */
    public static String explain(Integer status, String body, String base, String model) {
        if (status == null) {
            return "没有拿到 HTTP 响应";
        }
        var text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 401 || status == 403) {
            return "API Key 无效或没有该模型的权限（检查是否多带了空格，或用错了平台的 Key）";
        }
        if (status == 404) {
            // 认得出是哪家就按家说（智谱是 /api/paas/v4、通义是 /compatible-mode/v1、方舟是 /api/v3，
            // 「少了 /v1」对它们全是误导）；认不出才给通用建议
            var hint = AiProviderPresets.hintFor(base);
            if (!hint.isEmpty()) {
                return "端点不存在（HTTP 404）：" + hint;
            }
            return "端点不存在：" + DigestAiChannelResolver.normalizeBase(base)
                + " —— OpenAI 兼容协议的地址通常以版本路径结尾（/v1、/v4、/api/v3 等），"
                + "请对照所用平台的接口文档核对 API Base";
        }
        if (status == 429) {
            return "被限流或账户余额不足（稍后再试，或换一条通道）";
        }
        if (status >= 500) {
            return "对方服务异常（HTTP " + status + "）：不是配置问题，稍后再试或换一条通道";
        }
        if (status == 400) {
            if (text.contains("model")) {
                return "模型名「" + model + "」在该端点上不存在，请核对模型名（大小写、版本后缀）";
            }
            return "请求被拒绝（HTTP 400）：" + snippet(body);
        }
        if (status == 200) {
            return "接口返回 200 但没有正文：返回体不是 chat/completions 格式，多半不是兼容端点";
        }
        return "调用失败（HTTP " + status + "）：" + snippet(body);
    }

    /** 没有拿到响应（连接失败、超时、TLS 问题）时的结论 */
    public static String explainThrowable(Throwable ex, String base) {
        var message = ex == null ? "" : String.valueOf(ex.getMessage());
        var text = message.toLowerCase(Locale.ROOT);
        var host = DigestAiChannelResolver.hostOf(base);
        var target = host.isEmpty() ? "该端点" : host;
        if (ex instanceof TimeoutException || text.contains("timeout") || text.contains("timed out")) {
            return "请求超时：连得上但对方太久没回，或服务器出网被限制（" + target + "）";
        }
        if (text.contains("unknownhost") || text.contains("failed to resolve")
            || text.contains("nodename") || text.contains("no such host")) {
            return "域名解析失败：服务器解析不了 " + target + "（检查出网 / DNS）";
        }
        if (text.contains("refused") || text.contains("connect")) {
            return "连不上 " + target + "：端口不通或服务器出网被限制";
        }
        if (text.contains("ssl") || text.contains("certificate") || text.contains("handshake")) {
            return "TLS 握手失败：证书校验没过（自建中转常见，改用 https 证书有效的地址）";
        }
        return "调用异常：" + snippet(message);
    }

    /** 响应体 / 异常信息的单行片段：日志与后台都能直接看，不会因为整段 HTML 影响阅读 */
    public static String snippet(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_MAX
            ? normalized : normalized.substring(0, SNIPPET_MAX) + "…";
    }
}
