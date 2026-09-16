package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 阅读券免费获取规则（设置分组 ticketConfig）。
 *
 * <p>读券（{@code content_ticket}）此前只有两条获取途径：现金直购与积分兑换，
 * 用户从没体验过「券能解锁什么」就先撞上了付费墙，付费转化因此很低。
 * 本配置提供「免费的第一次」：让用户零成本拿到券、用掉一张、看到墙后面的内容，
 * 才会为下一篇付钱。</p>
 *
 * <p>所有赠券力度默认克制（签到默认不送、连签按周结算），避免免费券把付费转化掏空；
 * 站长可按站点经营策略自行调整。</p>
 */
@Data
@Builder
public class TicketRewardConfigDTO {

    /** 每次签到赠送张数（默认 0：日活用户每天白拿券会直接吃掉付费转化，按需开启） */
    private int signInTickets;

    /** 连签门槛天数（0 = 不启用连签奖励）；达到后按周期重复发放 */
    private int streakDays;

    /** 达到连签门槛额外赠送张数 */
    private int streakTickets;

    /** 新人见面礼赠送张数 */
    private int newUserTickets;

    /** 首次现金购买（任意道具）额外赠送张数 */
    private int firstPurchaseTickets;

    /** 兑换中心每次兑换发放张数 */
    private int exchangeTickets;

    /** 兑换中心每次兑换消耗积分（仅用于内置券兑换项的默认定价） */
    private int exchangePoints;

    /** 是否有任何赠券规则生效（全为 0 时调用方可以整体跳过，省掉一次账户读改写） */
    public boolean hasAnyReward() {
        return signInTickets > 0 || newUserTickets > 0 || firstPurchaseTickets > 0
            || (streakDays > 0 && streakTickets > 0);
    }
}
