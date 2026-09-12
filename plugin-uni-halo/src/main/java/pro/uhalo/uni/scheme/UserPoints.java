package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "UserPoints", plural = "userPoints", singular = "userPoint")
public class UserPoints extends AbstractExtension {

    private String wxUserId;

    private String wxOpenid;

    private Long balance;

    /** 当前连续签到天数 */
    private Integer consecutiveDays;

    /** 最近一次签到日期，格式 yyyy-MM-dd（Asia/Shanghai） */
    private String lastSignInDate;

    /** 瞬间发布券余额（积分兑换获得，发布超出免费配额时消耗） */
    private Integer publishTickets;

    /**
     * 阅读券余额：解锁付费文章的消耗品。
     *
     * <p>来源两条：虚拟支付道具直购（content_ticket）、积分兑换。
     * 每解锁一篇付费文章扣 1 张，扣减与解锁记录在同一事务语义下完成。</p>
     */
    private Integer contentTickets;

    /** 当前佩戴的称号 id（必须已解锁） */
    private String wornTitleId;

    /** 已拥有装扮 key 列表（JSON 数组字符串） */
    private String outfitOwned;

    private String activeAvatarFrame;

    private String activeNickFrame;

    private String activeTheme;
}
