package pro.uhalo.uni.utils;

import com.fasterxml.jackson.databind.JsonNode;
import pro.uhalo.uni.dto.MemberConfigDTO;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 会员配置解析（设置分组 {@code memberConfig}）。
 *
 * <p>容错原则与其它配置解析一致：字段缺失或非法时回落默认值，不抛异常。
 * 本配置在内容拦截的热路径上被读取，配置写错不应该让全站文章详情接口报错。</p>
 */
public final class MemberConfigHelper {

    public static final String GROUP_NAME = "memberConfig";

    /** 默认会员周期：30 天 */
    public static final int DEFAULT_DAYS = 30;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EXPIRE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MemberConfigHelper() {
    }

    public static String groupName() {
        return GROUP_NAME;
    }

    /**
     * 默认值：功能关闭（会员卡是付费商品，误开会让站长在没配商品的情况下
     * 露出一个买不了的入口），免券权益开启，周期 30 天，不额外送券。
     */
    public static MemberConfigDTO defaults() {
        return MemberConfigDTO.builder()
            .enabled(false)
            .defaultDays(DEFAULT_DAYS)
            .freeContent(true)
            .bonusTickets(0)
            .build();
    }

    public static MemberConfigDTO parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaults();
        }
        var defaults = defaults();
        var days = node.path("defaultDays").asInt(defaults.getDefaultDays());
        return MemberConfigDTO.builder()
            .enabled(node.path("enabled").asBoolean(false))
            // 周期至少 1 天：填 0 或负数会让用户付了钱却一刻钟会员都不是
            .defaultDays(days > 0 ? days : defaults.getDefaultDays())
            .freeContent(node.path("freeContent").asBoolean(true))
            .bonusTickets(Math.max(0, node.path("bonusTickets").asInt(0)))
            .build();
    }

    /**
     * 会员是否仍在有效期内。
     *
     * <p>判定口径只此一处：内容拦截、会员状态接口、发货时的到期时间叠加
     * 都调它，避免三处各写一套导致「页面显示还有 1 天但文章已经锁上」。</p>
     */
    public static boolean isActive(Instant expireAt) {
        return expireAt != null && expireAt.isAfter(Instant.now());
    }

    /** 剩余天数（不足一天按一天计）；未开通或已过期返回 0。 */
    public static int daysRemaining(Instant expireAt) {
        if (!isActive(expireAt)) {
            return 0;
        }
        var remainSeconds = expireAt.getEpochSecond() - Instant.now().getEpochSecond();
        return (int) Math.max(1, Math.ceil(remainSeconds / 86400.0));
    }

    /** 到期时间格式化；未开通返回 null。 */
    public static String formatExpireAt(Instant expireAt) {
        return expireAt == null ? null : expireAt.atZone(ZONE).format(EXPIRE_FMT);
    }
}
