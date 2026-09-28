package pro.uhalo.uni.dto;

/**
 * 大模型账户余额（DeepSeek 官方 {@code GET /user/balance} 的结果）。
 *
 * <p>这是官方唯一与「花了多少钱」相关的公开接口：它不给用量与账单，只给余额。
 * 所以插件里用它做两件事：跑之前跑之后各查一次（差值就是这一轮在账号上实际扣掉的钱，
 * 用来校验本地估算准不准），以及面板上一个「查余额」按钮。</p>
 *
 * <p>{@link #supported()} 用来区分「这个端点根本不支持」与「支持但这次查失败」——
 * 非 DeepSeek 的通道会返回 404 或另一种结构的 JSON，那是预期情况，不该在面板上报红。</p>
 *
 * @param key            通道标识
 * @param label          通道显示名
 * @param apiBase        通道端点
 * @param supported      该端点看起来支持余额查询（DeepSeek 系）
 * @param ok             本次是否查到了余额
 * @param currency       币种
 * @param totalBalance   总余额
 * @param grantedBalance 赠送余额
 * @param toppedUpBalance 充值余额
 * @param message        结论（失败原因 / 不支持说明）
 */
public record DigestAiBalanceDTO(String key, String label, String apiBase, boolean supported,
                                 boolean ok, String currency, String totalBalance,
                                 String grantedBalance, String toppedUpBalance, String message) {

    /** 该端点不支持余额查询（非 DeepSeek、或中转站没实现）：这是预期情况，不算故障 */
    public static DigestAiBalanceDTO notSupported(String key, String label, String apiBase,
        String message) {
        return new DigestAiBalanceDTO(key, label, apiBase, false, false, null, null, null, null,
            message);
    }

    /** 支持但这次没查到（网络、Key 权限等） */
    public static DigestAiBalanceDTO failure(String key, String label, String apiBase,
        String message) {
        return new DigestAiBalanceDTO(key, label, apiBase, true, false, null, null, null, null,
            message);
    }

    public static DigestAiBalanceDTO balance(String key, String label, String apiBase,
        String currency, String total, String granted, String toppedUp, String message) {
        return new DigestAiBalanceDTO(key, label, apiBase, true, true, currency, total, granted,
            toppedUp, message);
    }
}
