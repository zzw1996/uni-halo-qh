package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 会员配置（设置分组 memberConfig）。
 *
 * <p><b>合规边界</b>：这里做的是「单次购买的会员卡」——用户一次性付款，获得一段固定期限的
 * 权益。微信虚拟支付的适用场景明确包含「会员订阅：VIP 会员、专属权益包」，
 * 且 {@code short_series_goods}（道具直购）的定义就是「会员、课程等单品，支持单次购买」，
 * 因此本形态与插件里已在跑的「限时装扮」结构完全一致，属于合规范围。</p>
 *
 * <p><b>明确不做自动续费订阅</b>：连续包月需要单独申请签约能力、《会员自动续费服务协议》
 * 报平台审核、缴纳交易保证金、提前 5 日提醒与预扣费通知，并走
 * submit_subscribe_pay_order 等专用接口，还有一整套用户感知规范（醒目展示下次扣款时间、
 * 协议须勾选且不得默认勾选）。那是另一个量级的合规工程，不在本配置范围内。</p>
 */
@Data
@Builder
public class MemberConfigDTO {

    private boolean enabled;

    /** 商品未填有效期天数时的兜底值（天） */
    private int defaultDays;

    /** 会员期内全站付费文章免券 */
    private boolean freeContent;

    /** 开通/续费会员时额外赠送的阅读券张数（可与 freeContent 叠加，作为促单） */
    private int bonusTickets;
}
