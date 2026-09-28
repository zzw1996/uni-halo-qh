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
 * 付费「AI 定制内容」的用户偏好：一个用户一条记录。
 *
 * <p>metadata.name 由 openid 派生，因此重复提交天然幂等（与订阅、积分账户同一套做法）。</p>
 *
 * <p><b>与 {@link DigestSubscription} 的关系</b>：刻意分开存。订阅是免费能力，管的是
 * 首页/分类页按分类筛站点文章；这里是付费权益，管的是「我的 AI 简报」从已生成的日报内容里
 * 怎么挑。混在一张表上会让「付费过期后配置是否还能用」「上限该按谁算」这类问题纠缠不清。</p>
 *
 * <p><b>只存筛选项，不存生成结果</b>：简报按天生成后存在 {@link AiContentIssue}，
 * 偏好只回答「给他挑什么」。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "AiContentPreference", plural = "aiContentPreferences",
    singular = "aiContentPreference")
public class AiContentPreference extends AbstractExtension {

    private String wxOpenid;

    /** 是否启用简报（关掉后只是停止生成，配置保留） */
    private Boolean enabled;

    /** 关注的主题分类 key（取值来自站长的「日报分类」清单） */
    private List<String> categories;

    /** 关注的来源站点名（与文章的 sourceName 精确匹配） */
    private List<String> sources;

    /** 关注的关键词（与文章的 AI 关键词求交集） */
    private List<String> keywords;

    /** 最近一次生成简报的日期（yyyy-MM-dd），便于后台看活跃度 */
    private String lastIssueDate;

    private Instant createdAt;

    private Instant updatedAt;
}
