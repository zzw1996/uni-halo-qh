package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * AI 日报订阅：一个用户一条记录。
 *
 * <p>metadata.name 由 openid 派生，因此重复提交天然幂等（同名冲突即「已存在」），
 * 与积分账户用的是同一套做法。</p>
 *
 * <p><b>为什么偏好只存筛选项，不存"生成的日报"</b>：日报是服务端按天批量生成的，
 * 一个用户一份单独生成会让 AI 调用量随用户数线性增长，成本和时效都不可接受。
 * 因此偏好只是<b>从已生成的结果里筛</b>的依据——零额外 AI 成本。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "DigestSubscription", plural = "digestSubscriptions", singular = "digestSubscription")
public class DigestSubscription extends AbstractExtension {

    private String wxOpenid;

    private String wxUserId;

    /** 是否订阅（关掉后不展示「我的订阅」视角，也用于将来做推送开关） */
    private Boolean enabled;

    /** 关注的分类 key（AI 分类，如 ai-ml / security / engineering / tools / opinion / other） */
    private List<String> categories;

    /** 关注的关键词（与文章的 AI 关键词求交集） */
    private List<String> keywords;

    /** 关注的来源站点名（与文章的 sourceName 精确匹配） */
    private List<String> sources;

    /**
     * 关注的<b>站点分类</b>（Halo 分类的 metadata.name）。
     *
     * <p>与 {@link #categories} 的区别要分清：{@code categories} 是日报的 AI 分类，
     * 只存在于日报文章上；而首页主列表与分类页展示的是全站文章，它们带的是 Halo 站点分类。
     * 因此「让首页跟着订阅变」只能靠这个字段。</p>
     */
    private List<String> postCategories;

    /**
     * 从邀请人<b>继承</b>来的站点分类（上限 3）。
     *
     * <p>与 {@link #postCategories} 的区别：那个是用户自己选的，这个是「谁邀请了他，
     * 就用谁订阅的分类作为他首页的起点」。两者在首页取并集（自己优先，总共最多 8 个），
     * 但分成两个字段存，才能做到「自己订满 5 个也不挤掉继承来的」，也才能单独退订继承项。</p>
     */
    private List<String> inheritedCategories;

    /** 邀请人 openid：用于「来自 XX 的推荐」文案与退订；为空表示没有继承项 */
    private String inheritedFrom;

    private Instant createdAt;

    private Instant updatedAt;
}
