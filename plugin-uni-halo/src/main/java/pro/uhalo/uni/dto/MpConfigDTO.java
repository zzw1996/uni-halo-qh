package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 微信公众号（公众号）配置：接入凭据 + 公众号专属文章生成 + 推送落点。
 *
 * <p>解析入口见 {@code MpConfigHelper}，容错口径与其它 ConfigHelper 一致：
 * 任何字段缺失/类型不符都退回默认值。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MpConfigDTO {

    // ── 接入 ──

    /** 总开关：关闭后定时生成与所有推送动作都不执行 */
    private boolean enabled;

    /** 公众号 AppID（与微信小程序的 AppID 不是同一个） */
    private String appId;

    /** 公众号 AppSecret（仅服务端保存，绝不下发到客户端） */
    private String appSecret;

    /** 图文作者名（官方上限 16 字） */
    private String author;

    /** 是否打开留言（个人订阅号一般没有留言权限，默认关闭） */
    private boolean needOpenComment;

    /** 仅粉丝可评论（仅在留言打开时有意义） */
    private boolean onlyFansCanComment;

    /** 推送落点：draft（只进草稿箱）/ publish（调发布接口）/ mass（群发推粉丝） */
    private String deliveryMode;

    /** 「阅读原文」指向：site（站点文章）/ fixed（固定链接）/ none（不设置） */
    private String contentSourceMode;

    /** contentSourceMode=fixed 时使用的固定链接 */
    private String contentSourceUrl;

    /** 推送/复制时尝试把正文图片换成微信图床地址（uploadimg） */
    private boolean uploadImages;

    // ── 公众号专属文章生成 ──

    /**
     * 定时生成开关。
     *
     * <p><b>默认关闭</b>：生成会消耗 AI 额度并可能产生待推送稿件，
     * 站长明确打开后才会自动跑；且只在该时刻后的 {@code MpScheduler} 窗口内触发
     * （错过不补跑）。手动「立即生成」不受此开关限制。</p>
     */
    private boolean scheduleEnabled;

    /** 每天生成时刻（HH:mm，多个用逗号分隔） */
    private String scheduleTimes;

    private String timezone;

    /** 发布方向清单（AI 只能从这些方向里选材；默认 AI 发展 / AI 安全 / Vibe Coding） */
    private List<DigestCategoryConfigDTO> directions;

    /** 每个方向每期精选几篇 */
    private int perDirectionTopN;

    /**
     * 文章粒度：
     * {@code direction} = 每个方向一篇（把该方向精选聚合成一篇）；
     * {@code article} = 每篇精选各自一篇（默认，推送时逐篇独立草稿）。
     */
    private String contentGranularity;

    /**
     * 方向保底：某方向素材不足时，用 AI 给出的「备选方向」补位。
     *
     * <p>不额外花 AI 调用：评分时就让模型在必要时给一篇文章一个备选方向，
     * 这里只是把命中的备选文章收进该方向。</p>
     */
    private boolean directionFallbackEnabled;

    /** 抓取时间窗（小时） */
    private int hours;

    /** 每篇点评的目标字数（沿用日报「成稿」提示词的口径） */
    private int words;

    /** 摘要（引言）目标字数：官方上限 120 字，默认 60，留出安全余量 */
    private int digestWords;

    /** 标题模板（{direction}=方向名、{date}=日期） */
    private String titleTemplate;

    /** 是否把公众号文章同时发布到站点（默认关闭，只给公众号生成） */
    private boolean postToSite;

    /** 发到站点时的文章作者（Halo 用户名） */
    private String postOwner;

    /** 发到站点时额外附加的分类名（逗号分隔） */
    private String postCategories;
}
