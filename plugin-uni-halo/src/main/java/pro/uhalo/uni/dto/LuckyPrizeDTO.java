package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LuckyPrizeDTO {

    private String name;

    private String emoji;

    private int weight;

    /** 中奖后是否需要填写收货信息（实物奖品） */
    private boolean needsAddress;

    /** 奖品说明（前端奖品墙展示） */
    private String desc;

    /**
     * 中奖概率（百分比，保留 1 位小数），由服务端按权重统一计算后下发。
     *
     * <p>抽奖类活动需向用户公示中奖概率。放在服务端计算可避免前端算错或漏算，
     * 也无需把原始权重暴露给客户端。如实按 {@code weight / 总权重} 计算，
     * 不做凑整为 100% 的调整，以免歪曲真实概率。</p>
     */
    private double probability;
}
