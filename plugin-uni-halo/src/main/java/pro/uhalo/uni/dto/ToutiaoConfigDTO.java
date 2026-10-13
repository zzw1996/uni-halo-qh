package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 「今日头条号」配置：管理页生成稿件的默认口径。
 *
 * <p>解析入口见 {@code ToutiaoConfigHelper}，容错口径与其它 ConfigHelper 一致：
 * 任何字段缺失/类型不符都退回默认值。这个分组只服务控制台管理页（不对外下发），
 * 因此没有密钥类字段，也谈不上「误开泄露入口」——总开关留给站长按成本关闭。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToutiaoConfigDTO {

    /** 总开关：关闭后管理页的生成入口直接报错（历史记录仍可查看与复制） */
    private boolean enabled;

    /** 正文默认目标字数 */
    private int words;

    /** 标题候选默认个数 */
    private int titleCount;

    /** 写作风格清单（管理页下拉选项；留空用内置四种） */
    private List<String> styles;

    /** 默认写作风格（必须在上面的清单里；不在则用清单第一项） */
    private String defaultStyle;

    // ── 去 AI 味 ──

    /**
     * 口吻 / 视角（拼进提示词）。
     *
     * <p>这是「去 AI 味」最有效的单一手段：给模型一个具体的人设与第一人称视角，
     * 它就不会写出「随着…的发展」那种没人味的综述腔。留空 = 用内置口吻。</p>
     */
    private String voice;

    /** 站长另外禁用的词句（逗号/换行分隔，追加到内置套话禁用清单后面） */
    private String bannedPhrases;

    /**
     * 初稿「AI 味」偏重时是否自动重写一遍。
     *
     * <p>默认开启：命中 4 处以上套话就多花一次写稿调用，换取「像人写的」。
     * 想省额度的站长可以关掉——关掉后仍然会检查并在页面上提示命中几处。</p>
     */
    private boolean rewriteEnabled;

    // ── 配图（每篇至少 3 张） ──

    /** 配图张数（下限 3：头条多图文章的推荐表现更好） */
    private int imageCount;

    /**
     * 图片模型名（如 {@code dall-e-3} / {@code wanx2.1-t2i-turbo} / {@code flux.1-schnell}）。
     *
     * <p><b>留空 = 只用文案卡片</b>（服务端本地生成的图文卡片，零成本零配置）；
     * 填了就走 AI 生图，逐张按主题写提示词，失败的那张仍用卡片补齐。</p>
     */
    private String imageModel;

    /** 图片通道标识（「AI 日报 → 大模型通道」里某条通道的 key）；留空 = 用第一条可用通道 */
    private String imageChannelKey;

    /** 出图尺寸（按模型支持的档位填，如 1024x1024 / 1280x720） */
    private String imageSize;

    /** 画面风格补充（拼进每条生图提示词） */
    private String imageStylePrompt;
}
