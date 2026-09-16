package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分享出去的个人精选（落地页数据）。
 *
 * <p>被分享者看到的是「某某为你精选的几篇」——比一张干巴巴的邀请海报有说服力得多，
 * 因为他能立刻看到内容本身。</p>
 *
 * <p><b>隐私边界</b>：只回传分享人昵称/头像、命中的文章、以及由这些文章推导出的
 * <b>分类</b>摘要（分类是内容属性，只有六种，粗粒度）。<b>不回传分享人的原始偏好</b>
 * （关键词、来源站点）——那是他个人的阅读兴趣，用户主动分享的是「我挑的内容」，
 * 不是「我的兴趣标签列表」。一键订阅时也只复制分类与关键词，不复制来源。</p>
 */
@Data
@Builder
public class DigestSharedDTO {

    /** 站点是否开启了 AI 日报 */
    private boolean enabled;

    /** 分享链接是否有效（邀请码能找到对应用户） */
    private boolean valid;

    /** 分享人昵称；未设置昵称时为 null，前端回落成「一位读者」 */
    private String sharerName;

    private String sharerAvatar;

    /** 精选篇数 */
    private int articleCount;

    /** 在分享人的筛选条件下，最近窗口内的总篇数（体现「从多少里挑」） */
    private int totalCount;

    private int days;

    /** 由命中文章推导出的分类摘要（粗粒度，不含关键词与来源） */
    private List<DigestCategoryDTO> categories;

    /** 分享人最近一次分享的精选文章（按日期倒序） */
    private List<DigestPublicArticleDTO> articles;

    /** 无效或为空时的提示 */
    private String message;
}
