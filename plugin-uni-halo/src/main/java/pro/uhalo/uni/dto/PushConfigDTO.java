package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 微信订阅消息推送配置（设置分组 {@code pushConfig}）。
 *
 * <p><b>为什么能力有限但值得做</b>：普通类目只能用<b>一次性订阅消息</b>——用户点一次授权，
 * 只能发一条（官方原文：「用户订阅后，开发者可不限时间地下发一条对应的服务消息」）。
 * 长期订阅只对政务民生/医疗/交通/金融/教育等类目开放，本项目拿不到。
 * 所以本功能的体验是「用户点一次 → 有内容时收到一条 → 想继续收就再点一次」，
 * 配置里所有开关都是为了<b>把额度花在真有内容的那一天</b>。</p>
 *
 * <p><b>合规红线</b>：订阅消息不得用于营销/广告，也不得诱导订阅（「订阅送积分」这类玩法违规）。
 * 因此本功能只做真实的内容更新提醒，入口保持克制。</p>
 */
@Data
@Builder
public class PushConfigDTO {

    /** 总开关：关闭时不检查、不发送，小程序也不显示推送相关入口 */
    private boolean enabled;

    /** 新文章提醒模板 ID（在 MP 后台「订阅消息」里选好并绑定服务类目后填入） */
    private String templateId;

    /** 简报提醒模板 ID；留空表示复用 {@link #templateId} */
    private String aiDigestTemplateId;

    /** 新文章提醒开关 */
    private boolean newPostEnabled;

    /** AI 简报就绪提醒开关（付费权益） */
    private boolean aiDigestEnabled;

    /** 检查间隔（分钟）：每隔这么久看一次有没有新文章、今天的日报是否已就绪 */
    private int checkIntervalMinutes;

    /** 一条消息里最多提几篇（超出只报数量，避免标题字段被塞爆） */
    private int maxItemsPerMessage;

    /** 整站每日发送上限：防误配（例如把间隔配成 1 分钟）刷爆额度 */
    private int dailySendLimit;

    /** 水位缺失或过旧时的回看窗口（小时）：插件停机久了也不会漏推 */
    private int lookbackHours;

    /**
     * 点击消息跳转的小程序版本：{@code formal}（正式版）/ {@code trial}（体验版）/
     * {@code developer}（开发版）。
     *
     * <p>体验版测试阶段必须填 {@code trial}，否则用户点开的是线上正式版、看不到新页面，
     * 会误以为「推送坏了」。</p>
     */
    private String miniprogramState;

    /** 简报提醒使用的模板 ID（留空回落新文章模板） */
    public String resolvedAiDigestTemplateId() {
        return aiDigestTemplateId == null || aiDigestTemplateId.isBlank()
            ? templateId : aiDigestTemplateId.trim();
    }

    /** 模板是否已配置（没配就别去调微信，那是必然 40037） */
    public boolean hasNewPostTemplate() {
        return templateId != null && !templateId.isBlank();
    }

    public boolean hasAiDigestTemplate() {
        return resolvedAiDigestTemplateId() != null
            && !resolvedAiDigestTemplateId().isBlank();
    }
}
