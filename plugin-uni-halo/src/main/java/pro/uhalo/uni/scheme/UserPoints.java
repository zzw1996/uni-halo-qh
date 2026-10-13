package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

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

    /**
     * 会员到期时间（null = 从未开通或已过期后清空）。
     *
     * <p>「单次购买的会员卡」的权益凭据：会员期内全站付费文章免券（可通过
     * memberConfig.freeContent 关闭）。续费时在<b>当前到期时间</b>上叠加天数，
     * 而不是从当下重新计时——否则用户在有效期内续费会白白损失剩余天数。</p>
     *
     * <p>不做自动续费订阅：该能力需单独签约、协议报审与交易保证金，
     * 详见 {@code MemberConfigDTO} 的说明。</p>
     */
    private Instant memberExpireAt;

    /**
     * 已购的「分类订阅额度」份数（null = 0）。
     *
     * <p>免费额度之外的订阅能力：用积分解锁，<b>永久有效、可累计</b>。它是容量而不是占用——
     * 取消某个订阅只腾出位置，额度本身不退还（也就不存在退款链路）。</p>
     *
     * <p>与会员权益取大者而不叠加：会员期内订阅上限走 {@code subscriptionConfig.memberMaxQuota}，
     * 会员到期后回落到「免费 + 已购」，超出部分的订阅会暂停但记录保留。</p>
     */
    private Integer subscribeQuota;

    /**
     * AI 定制内容权益到期时间（null = 从未购买；远期时间 = 永久买断）。
     *
     * <p>与 {@link #memberExpireAt} 同构：一次性买断 + 有效期，续费在当前到期时间上叠加。
     * 区分「永久」与「未开通」靠 {@code AiContentConfigHelper.isPermanent} 判断远期时间，
     * 因此不需要额外的布尔字段，权益判定也能与会员共用同一套口径。</p>
     */
    private Instant aiContentExpireAt;

    /**
     * AI 定制内容还能生成几次简报（null 视为 0）。
     *
     * <p>购买 / 积分兑换时按「天数 × 每天次数」发放，每次生成扣 1 —— 这是付费功能的成本阀门：
     * 用户随时可以按新方向重新生成，但不是无限次（否则一个 1 天的权益就能把 AI 额度刷爆）。
     * 与到期时间放在同一条账户记录里：一个用户一条，读写都在一处。</p>
     */
    private Integer aiContentCredits;
}
