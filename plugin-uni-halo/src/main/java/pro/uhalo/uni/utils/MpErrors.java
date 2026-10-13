package pro.uhalo.uni.utils;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信公众号错误码分类与处置建议。
 *
 * <p>公众号接口的错误码语义跨度很大：有的是「站长去改配置」（IP 白名单、权限、留言），
 * 有的是「正常业务状态」（群发额度用完），有的是「重试一次就好」（token 失效）。
 * 全部混在一句「推送失败」里，运营层面根本没法行动——所以统一在这里分类，
 * 管理页与日志都按同一份口径展示。</p>
 */
public final class MpErrors {

    /** 40164 errmsg 里带着服务器真实出口 IP，例如：invalid ip 1.2.3.4 ipv6 ::ffff:1.2.3.4, not in whitelist */
    private static final Pattern IP_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");

    private MpErrors() {
    }

    /**
     * 一次接口失败的分类结果。
     *
     * @param errcode          微信错误码
     * @param errmsg           微信原始 errmsg（不加工，便于搜索与对账）
     * @param suggestion       给站长的处置建议（管理页直接显示）
     * @param ip               40164 时解析出的服务器出口 IP（用于一键复制到公众号后台白名单）
     * @param tokenInvalid     40001/42001：清缓存重试一次
     * @param permissionDenied 权限不足（48001、发布/群发类接口的企业主体限制）
     * @param configProblem    需要站长改配置（IP 白名单、留言、素材 ID、参数超限等）
     */
    public record MpError(int errcode, String errmsg, String suggestion, String ip,
                          boolean tokenInvalid, boolean permissionDenied, boolean configProblem) {
    }

    /** 成功结果（errcode = 0） */
    public static MpError ok() {
        return new MpError(0, "", "", "", false, false, false);
    }

    /**
     * 插件侧错误的伪错误码（网络超时、参数缺失等——既不是微信错误码，也不是 HTTP 状态）。
     *
     * <p>约定：负数里 {@code -1} 留给微信自己的「系统繁忙」，{@code -4xx/-5xx} 表示 HTTP 状态，
     * 其余插件自己产生的错误一律用这个常量，避免和上面两类撞号。</p>
     */
    public static final int PLUGIN_CODE = -1000;

    public static MpError of(int errcode, String errmsg) {
        var message = errmsg == null ? "" : errmsg.trim();
        // 负数里：-1000 及以下是插件侧的通用错误，-4xx/-5xx 是 HTTP 状态
        if (errcode <= PLUGIN_CODE) {
            return new MpError(errcode, message,
                StringUtils.hasText(message) ? message : "插件侧请求失败，请查看日志",
                "", false, false, false);
        }
        // 微信自己的「系统繁忙」：重试即可，不能当成传输层错误（都是负数，顺序不能反）
        if (errcode == -1) {
            return new MpError(-1, message, "微信侧系统繁忙（errcode -1）：稍等片刻重试即可。",
                "", false, false, false);
        }
        if (errcode < 0) {
            return transportOf(-errcode, message);
        }
        return switch (errcode) {
            case 0 -> ok();
            case 40001, 42001, 41001 -> new MpError(errcode, message,
                "access_token 失效或缺失：插件会自动清缓存重试一次；仍失败时检查 AppID/AppSecret 是否填对。",
                "", true, false, false);
            case 40164 -> new MpError(errcode, message,
                "服务器出口 IP 不在公众号的 IP 白名单里：把下面的 IP 加到「公众号后台 → 设置与开发 → 开发接口管理 → IP 白名单」，"
                    + "等待约 1-5 分钟后重试。",
                extractIp(message), false, false, true);
            case 40125 -> new MpError(errcode, message,
                "AppSecret 无效：请到公众号后台重置 AppSecret 后重新填写（注意不要带入空格）。",
                "", false, false, true);
            case 40013 -> new MpError(errcode, message,
                "AppID 无效：请核对公众号后台「设置与开发 → 开发接口管理」里的 AppID（不是小程序的）。",
                "", false, false, true);
            case 40066 -> new MpError(errcode, message,
                "接口地址不合法（invalid url）：多是 URL 缺少或写错了 query 参数"
                    + "（如新增永久素材必须带 ?type=image）。属于插件侧问题，请反馈维护者。",
                "", false, false, true);
            case 48001 -> new MpError(errcode, message,
                "接口权限不足：该接口需要已认证的企业主体账号（2025 年 7 月起官方限制），"
                    + "个人订阅号请改用「复制正文」路径手动发布。",
                "", false, true, true);
            case 48002 -> new MpError(errcode, message,
                "接口被微信拒绝（api forbidden）：当前账号没有这个接口的权限，"
                    + "常见于个人订阅号调用「永久素材」——草稿箱的封面必须是永久素材，"
                    + "因此这条路走不通，请改用「复制正文」+「下载封面」手动发布。",
                "", false, true, true);
            case 41002 -> new MpError(errcode, message,
                "缺少 AppID：请核对插件设置里的公众号 AppID（不是小程序的）。",
                "", false, false, true);
            case 40004 -> new MpError(errcode, message,
                "文件类型不受支持：正文图片只支持 jpg/png，封面支持 jpg/png/gif/bmp；"
                    + "插件会自动转码，转码失败的那张图会被跳过。",
                "", false, false, true);
            case 45009 -> new MpError(errcode, message,
                "接口调用超过配额：稍后再试；群发接口的配额按天/按粉丝计算，可在公众号后台查看额度。",
                "", false, false, true);
            case 45011 -> new MpError(errcode, message, "接口调用过于频繁（分钟级限额）：等一分钟再重试。",
                "", false, false, false);
            case 45028 -> new MpError(errcode, message,
                "没有群发额度：订阅号每天 1 次、服务号每粉丝每月最多 4 条；额度用完请改用「发布」或手动群发。",
                "", false, true, true);
            case 88000 -> new MpError(errcode, message,
                "账号没有留言功能：插件会自动关闭留言后重试一次；也可以在插件设置里把「打开留言」保持关闭。",
                "", false, false, true);
            case 40007 -> new MpError(errcode, message,
                "media_id 无效或已过期：封面素材必须是「永久素材」；草稿被发布/群发后会失效，重新推送即可。",
                "", false, false, true);
            case 40005 -> new MpError(errcode, message,
                "文件类型不支持：正文图片只支持 jpg/png（1MB 以内）、封面支持 jpg/png/gif/bmp（10MB 以内）；"
                    + "插件会自动转码，转码失败的那张图会被跳过。",
                "", false, false, true);
            case 40006 -> new MpError(errcode, message,
                "文件大小超限：正文图片需小于 1MB、封面小于 10MB；压缩后仍超的图片会被跳过。",
                "", false, false, true);
            case 40008 -> new MpError(errcode, message, "参数不合法（消息/素材类型不符）：请把完整错误码发给插件维护者核对。",
                "", false, false, true);
            case 45002 -> new MpError(errcode, message,
                "内容超长或不合法：标题 32 字、摘要 120 字、正文 2 万字符以内；插件已做截断，出现该错误请检查是否是模板字段异常。",
                "", false, false, true);
            case 53500, 53501, 53502, 53503, 53504, 53505 ->
                new MpError(errcode, message, "发布/审核相关失败：请在公众号后台查看该草稿的状态与平台通知。",
                    "", false, false, true);
            default -> new MpError(errcode, message,
                // 未识别的错误码也必须把原始 errcode/errmsg 带上——
                // 只说「未知错误码」等于没有线索（线上为此白排查过一轮）
                "微信返回未识别的错误码：errcode=" + errcode
                    + (StringUtils.hasText(message) ? " errmsg=" + message : "（errmsg 为空）")
                    + "；可到公众号后台「设置与开发 → 接口告警 / 接口权限」核对，"
                    + "或把这条错误码反馈给插件维护者。",
                "", false, false, false);
        };
    }

    /** 从 40164 的 errmsg 里解析服务器出口 IP（解析不到返回空串） */
    public static String extractIp(String errmsg) {
        if (!StringUtils.hasText(errmsg)) {
            return "";
        }
        Matcher matcher = IP_PATTERN.matcher(errmsg);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 传输层失败（HTTP 状态码，不是微信的 errcode）。
     *
     * <p>{@code 412 Precondition Failed} 是线上真实踩过的：微信的前置网关在请求没带
     * {@code Content-Length}（Spring WebFlux 的 multipart 默认走 chunked）时会直接拒绝，
     * 返回体通常是空的、也没有 errcode——只会在日志里留下一句
     * 「412 Precondition Failed from POST ...」，站长完全无从下手。</p>
     */
    private static MpError transportOf(int status, String message) {
        return switch (status) {
            case 412 -> new MpError(-412, message,
                "微信前置网关拒绝了这次上传（HTTP 412）：常见于请求缺少 Content-Length 头，"
                    + "或图片体积贴着 1MB 上限。插件已显式带 Content-Length，并会把图压得更小后自动重试一次；"
                    + "若仍失败，请把该图片的原始地址反馈给插件维护者。",
                "", false, false, true);
            case 413 -> new MpError(-413, message,
                "请求体过大（HTTP 413）：图片超过微信网关限制。插件会自动压小后重试一次。",
                "", false, false, true);
            case 400 -> new MpError(-400, message,
                "请求被微信网关判为非法（HTTP 400）：多为参数或请求体格式问题，请把该条日志反馈给插件维护者。",
                "", false, false, true);
            case 401, 403 -> new MpError(-status, message,
                "微信网关拒绝访问（HTTP " + status + "）：请检查 AppID/AppSecret 与 IP 白名单。",
                "", false, false, true);
            default -> status >= 500
                ? new MpError(-status, message,
                    "微信侧网关异常（HTTP " + status + "）：多为瞬时故障，稍后重试即可。",
                    "", false, false, false)
                : new MpError(-status, message,
                    "上传被拒绝（HTTP " + status + "）：请把该条日志反馈给插件维护者。",
                    "", false, false, true);
        };
    }

    /**
     * 是否值得「压得更小再传一次」。
     *
     * <p>两种情况：网关层面的体积相关拒绝（HTTP 412/413），
     * 以及微信文档里的「图片尺寸/大小超限」（40006/40009）。</p>
     */
    public static boolean worthRetrySmaller(MpError error) {
        if (error == null) {
            return false;
        }
        var code = error.errcode();
        return code == -412 || code == -413 || code == 40006 || code == 40009;
    }

    /** 一行式描述（日志用） */
    public static String describe(MpError error) {
        if (error == null) {
            return "（无错误）";
        }
        return "errcode=" + error.errcode()
            + (StringUtils.hasText(error.errmsg()) ? " errmsg=" + error.errmsg() : "")
            + (StringUtils.hasText(error.suggestion()) ? " 处置=" + error.suggestion() : "");
    }
}
