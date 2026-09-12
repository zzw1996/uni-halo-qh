package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 榜单配置（设置分组 rankConfig）。
 *
 * <p>周榜奖励默认关闭：发放积分属于权益变动，需站长确认奖励口径后主动开启。</p>
 */
@Data
@Builder
public class RankConfigDTO {

    /** 关闭后小程序「榜单」页不展示任何榜单内容 */
    private boolean enabled;

    /** 关闭后用户榜不显示具体分值（只保留名次、等级与称号） */
    private boolean showScore;

    private boolean enableInviteBoard;

    /** 互动动态（最近用户的评论/点赞/投票） */
    private boolean wallEnabled;

    private int wallLimit;

    /**
     * 互动动态是否展示「点赞」动作。
     *
     * <p>默认关闭：点赞是低频可见的轻动作，默认公开「谁赞了什么」对用户偏侵扰，
     * 站长可按站点氛围自行开启。评论与投票本就是公开行为，不受该开关影响。</p>
     */
    private boolean wallIncludeUpvote;

    /** 周榜奖励：每周一结算上一自然周活跃榜，给前 N 名发放积分 */
    private boolean weeklyRewardEnabled;

    private int weeklyRewardTopN;

    /** 第 1 名奖励积分，之后每名递减 weeklyRewardStep */
    private int weeklyRewardPoints;

    private int weeklyRewardStep;
}
