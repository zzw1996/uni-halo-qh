package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 积分兑换「AI 定制内容」权益的结果。
 *
 * <p>兑换通道与现金道具共用同一个到期字段，前端拿到结果后刷新权益卡即可，
 * 不需要再区分「这条权益是积分换的还是钱买的」。</p>
 */
@Data
@Builder
public class AiContentRedeemResultDTO {

    /** 兑换后的积分余额 */
    private long balance;

    /** 本次兑换延长了多少天 */
    private int days;

    /** 新的到期时间展示串（永久时为「永久」） */
    private String expireAt;

    private int daysRemaining;

    private boolean permanent;

    /** 本次兑换获得的生成次数 */
    private int creditsAdded;

    /** 兑换后剩余的生成次数 */
    private int remainingGenerations;

    /** 给前端 toast 的提示语 */
    private String message;
}
