package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「AI 定制内容」页面一次拉齐的数据：权益状态 + 当前偏好 + 可选清单 + 各维度上限。
 *
 * <p>与 {@code GET /digest/subscription} 同一思路：读偏好时顺带把「你能选什么」也发下去，
 * 前端不必自己猜候选、也不会出现「点了一个从来没内容的来源」。
 * 上限也在这里下发（而不是硬编码在前端），站长改配置后小程序立即跟着变。</p>
 */
@Data
@Builder
public class AiContentPreferenceViewDTO {

    private AiContentStatusDTO status;

    private AiContentPreferenceDTO preference;

    /** 可选的主题分类（来自站长的「日报分类」清单） */
    private List<DigestCategoryDTO> categoryOptions;

    /** 可选的来源站点（来自最近日报里实际出现过的来源，带篇数） */
    private List<DigestOptionItemDTO> sourceOptions;

    /** 各维度上限与简报参数（前端据此做计数与提示） */
    private Limits limits;

    /** 权益说明文案（未开通页展示；留空时前端用通用文案） */
    private String benefitNote;

    /** 预设定制主题选项（站长配置，前端一键加入自己的定制主题） */
    private List<String> presetTopics;

    /** 积分兑换通道（pointsPrice=0 表示未开启） */
    private Redeem redeem;

    /** 推送设置：可选整点、模板是否配好、用户当前的推送偏好由 preference 带回 */
    private PushSettings push;

    @Data
    @Builder
    public static class Limits {
        private int maxCategories;
        private int maxSources;
        private int maxKeywords;
        private int maxTopics;
        private int maxItems;
        private int windowDays;
        /** 是否开启 AI 导语（关闭时页面要说明「本次为纯筛选」） */
        private boolean aiIntroEnabled;
    }

    /** 每日推送的可选项与可用状态 */
    @Data
    @Builder
    public static class PushSettings {
        /** 用户可选的整点列表（站长配置） */
        private List<Integer> hourOptions;
        /** 简报模板与字段是否配好（没配好时前端不显示推送入口） */
        private boolean ready;
    }

    /** 积分解锁 AI 定制内容的通道参数 */
    @Data
    @Builder
    public static class Redeem {
        /** 每次兑换需要的积分；0 = 通道关闭 */
        private int pointsPrice;
        /** 每次兑换延长多少天 */
        private int pointsDays;
        /**
         * 当前积分余额（未登录为 null）。
         *
         * <p>下发它是为了「点击兑换时就能判断够不够」——否则用户要等确认弹窗之后再被服务端拒绝，
         * 白点一次。服务端仍会做权威校验（余额可能已经变了），这里只是把已知信息提前给前端。</p>
         */
        private Long pointsBalance;
    }
}
