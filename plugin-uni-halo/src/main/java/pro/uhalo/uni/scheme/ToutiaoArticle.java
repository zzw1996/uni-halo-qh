package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 今日头条号稿件：一条记录对应管理页按一个关键词生成的一篇文章。
 *
 * <p>生成结果（标题候选 / 正文 / 标签）与花费快照同表保存：页面回看、再次复制、
 * 对账花费都不需要重跑 AI。这个页面只对管理员可见（控制台接口），
 * 因此记录里不存小程序 openid——全部记录同属站长本人。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "ToutiaoArticle", plural = "toutiaoArticles", singular = "toutiaoArticle")
public class ToutiaoArticle extends AbstractExtension {

    /** 主题关键词（管理员输入） */
    private String keyword;

    /** 写作风格（提示词口径，可为空 = 用配置的默认风格） */
    private String style;

    /** 正文目标字数（生成时使用的配置值） */
    private Integer words;

    /** 标题候选个数（生成时使用的配置值） */
    private Integer titleCount;

    /** 标题候选，多个用换行分隔（避免 JSON 二次转义；展示时拆行） */
    private String titles;

    /** 正文（纯文本分段，可直接粘进头条编辑器） */
    private String body;

    /** 标签，多个用逗号分隔 */
    private String tags;

    /**
     * 配图地址，多个用换行分隔（每篇至少 3 张）。
     *
     * <p>图片全部转存到站点附件库后再写这里：外链会过期、也会被头条的编辑器拒绝抓取。</p>
     */
    private String images;

    /** 配图来源说明（几张 AI 生成、几张文案卡片、失败原因），展示给站长 */
    private String imageNote;

    /** 本次实际使用的通道（显示名 / 模型名，供站长核对走了哪条通道） */
    private String channel;

    /**
     * 状态：GENERATING（生成中）/ READY（已生成）/ FAILED（失败，见 error）。
     *
     * <p><b>字段名刻意不叫 {@code kind}</b>：{@code AbstractExtension} 自带
     * {@code getKind()}，子类再声明会让 Lombok 生成的访问器覆盖基类方法，
     * Halo 会把状态值当扩展类型查 scheme（{@code Scheme not found}）、写入 500。
     * 同理不要取名 {@code apiVersion} / {@code metadata}（见 {@code MpIssue} 里的完整说明
     * 与 {@code HaloSchemeFieldGuardTest} 护栏）。</p>
     */
    private String state;

    /** 失败原因（展示给站长，需可操作） */
    private String error;

    /** 内容提示（如「输出可能被长度上限截断」；正常为空） */
    private String note;

    /** AI 花费（JSON，沿用日报的费用快照结构，可为空） */
    private String aiCost;

    private Instant createdAt;

    private Instant finishedAt;
}
