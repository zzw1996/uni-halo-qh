package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI 定制内容配置（设置分组 aiContentConfig）。
 *
 * <p><b>这是什么</b>：一个付费权益——用户购买「AI 定制内容」道具后，可自行勾选主题分类 /
 * 来源站点 / 关键词，服务端据此从<b>站长已生成的 AI 日报内容</b>里筛出属于他的每日简报，
 * 再用一次 AI 调用写导语与推荐理由。与免费的「分类订阅」是两条独立链路。</p>
 *
 * <p><b>成本边界（唯一阀门）</b>：每位付费用户每天最多 1 次 AI 调用（当天简报已生成即复用），
 * 站长可以用 {@link #aiIntroEnabled} 整体关掉导语生成，退化为零 AI 成本的纯筛选。</p>
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

    /** 用户最多勾选几个来源站点 */
    private int maxSources;

    /** 用户最多填几个关键词 */
    private int maxKeywords;

    /** 一份简报最多几条内容 */
    private int maxItems;

    /** 从最近几天的日报里筛 */
    private int windowDays;

    /** AI 导语的字数上限 */
    private int introWords;

    /** 权益说明文案（未购买页展示，一行一条；留空则由前端用通用文案） */
    private String benefitNote;
}
