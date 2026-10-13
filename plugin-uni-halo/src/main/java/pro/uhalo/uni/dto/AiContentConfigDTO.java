package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 定制内容配置（设置分组 aiContentConfig）。
 *
 * <p><b>这是什么</b>：一个付费权益——用户购买后填写定制主题 / 关键词、勾选主题分类，
 * 服务端据此<b>联网检索公开报道</b>（关键词检索）并以实时素材池兜底，再用一次 AI 调用
 * 挑出属于他的几条、写成中文标题摘要并给出导语与结尾。与免费的「分类订阅」是两条独立链路。</p>
 *
 * <p><b>成本边界（唯一阀门）</b>：{@link #creditsPerDay} 决定「有效期内能生成几次」，
 * {@link #dailyGenerationLimit} 决定「每天最多几次」；每次生成只调用一次 AI，
 * 站长可以用 {@link #aiIntroEnabled} 关掉 AI（退化为字符串匹配）。</p>
 *
 * <p><b>合规边界</b>：一次性买断 + 有效期（与会员卡同构），不做自动续费订阅。</p>
 */
@Data
@Builder
public class AiContentConfigDTO {

    /** 总开关：关闭时小程序不显示入口、接口一律拒绝（等同于没有这个功能） */
    private boolean enabled;

    /** 是否调用 AI 生成导语与推荐理由；关闭后退化为纯筛选（零 AI 成本） */
    private boolean aiIntroEnabled;

    /** 用户最多勾选几个主题分类 */
    private int maxCategories;

    /** 用户最多填几个关键词 */
    private int maxKeywords;

    /** 一份简报最多几条内容 */
    private int maxItems;

    /**
     * 实时素材的时间窗（小时）：从各源抓最近这段时间的内容来生成简报。
     *
     * <p>简报的卖点是「最新」，所以素材默认来自实时抓取（48 小时）；这个窗口决定
     * 「有多新」。站长把「生成 AI 导语」关掉时仍然照抓，只是不做 AI 挑选。</p>
     */
    private int liveHours;

    /** 兜底：实时抓不到素材时，从最近几天的站内日报里筛 */
    private int windowDays;

    /**
     * 每个权益日赠送几次生成（购买/兑换时按 天数 × 本值 发放）。
     *
     * <p>这是付费功能的成本阀门：用户随时可以按新方向重新生成，但总次数有限。</p>
     */
    private int creditsPerDay;

    /** 单次赠送的生成次数上限（永久卡不能按 36500 天算，否则等于无限） */
    private int maxCreditsPerGrant;

    /** 每天最多生成几次（0 = 不限；与总额度一起挡住「一直在输入一直在调用 AI」） */
    private int dailyGenerationLimit;

    /**
     * 是否按用户输入的关键词/定制主题做联网检索。
     *
     * <p>内置素材池是 AI 日报那批科技源，用户输入「邓紫棋」这类词在里面必然命不中；
     * 打开这一项后，检索到的公开报道会作为素材池的第一顺位。</p>
     */
    private boolean keywordSearchEnabled;

    /** 检索源：{@code bing} / {@code google} / {@code baidu}（可多选，逗号分隔） */
    private List<String> keywordSearchProviders;

    /** 只看最近多少小时内发布的检索结果 */
    private int keywordSearchHours;

    /** 检索结果里最多送多少条给 AI 挑选 */
    private int keywordSearchMaxItems;

    /** 是否给检索结果抓原文头图（会慢一点，但简报里带图更好看） */
    private boolean keywordSearchCoverEnabled;

    /** AI 导语的字数上限 */
    private int introWords;

    /** AI 结尾（小结/看法）的字数上限；0 = 不写结尾 */
    private int outroWords;

    /** 用户最多手输几个定制主题（0=关闭手输维度） */
    private int maxTopics;

    /** 积分解锁价格（积分）；0 = 关闭积分兑换通道（只能现金直购道具） */
    private int pointsPrice;

    /** 积分一次兑换的权益天数 */
    private int pointsDays;

    /** 预设定制主题选项（textarea，逗号/换行分隔）；空则前端只展示手输框 */
    private String presetTopics;

    /** 是否在首页展示运营位卡片 */
    private boolean homeCardEnabled;

    /** 首页卡片标题 */
    private String homeCardTitle;

    /** 首页卡片描述 */
    private String homeCardDesc;

    /** 主题检索不到足够素材时，是否允许 AI 直接按主题生成（兜底开关） */
    private boolean fallbackGenerateEnabled;

    /** 会员有效期内是否免条件使用（对齐会员免券/免订阅墙口径） */
    private boolean memberFree;

    /** 权益说明文案（未购买页展示，一行一条；留空则由前端用通用文案） */
    private String benefitNote;
}
