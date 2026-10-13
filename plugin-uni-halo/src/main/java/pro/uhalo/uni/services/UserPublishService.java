package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.codec.multipart.FilePart;
import pro.uhalo.uni.dto.UserPublishArticleDTO;
import pro.uhalo.uni.dto.UserPublishArticleDetailDTO;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.dto.UserPublishMomentRequest;
import pro.uhalo.uni.dto.UserPublishResultDTO;
import pro.uhalo.uni.dto.UserPublishStatusDTO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 用户投稿（阅读赚积分 → 花积分发文章 / 发瞬间）。
 *
 * <p>为什么发布必须在服务端做：小程序端原本拿 personalToken 直连 Halo 发布，
 * 而该 token 是随匿名配置一起下发的，客户端能做任何事。若沿用前端发布，
 * 「积分门槛」只是界面上的装饰品，任何用户都能绕过。因此扣分与发布都在本服务内完成，
 * 前端只拿结论。</p>
 */
public interface UserPublishService {

    /** 投稿状态：开关、门槛、价格、余额、角色策略结论、今日已发数 */
    Mono<UserPublishStatusDTO> getStatus(String openid);

    Mono<UserPublishResultDTO> publishArticle(String openid, UserPublishArticleRequest request);

    /**
     * 「我的投稿」列表。
     *
     * <p>在 Halo 侧按 {@code spec.owner} 过滤，所以这里天然只看得到自己的文章；
     * 含尚未发布的待审草稿——提交完待审的作者不该看到空列表。</p>
     */
    Mono<List<UserPublishArticleDTO>> listMyArticles(String openid);

    /**
     * 删除（回收）投稿文章。
     *
     * <p>普通用户只能删自己的：归属判定直接复用「按 owner 拉自己的列表」，
     * 列表里没有这篇就不是他的；博主/管理员可代删任意一篇。
     * 走 Halo 的 {@code /recycle} 而不是物理删除，误删还能在后台回收站恢复。</p>
     */
    Mono<Void> deleteArticle(String openid, String postName);

    /**
     * 读一篇自己的投稿（编辑回填用）：带 Markdown 正文原文。
     *
     * <p>归属判定与删除同一口径：自己的文章，或博主/管理员代查。</p>
     */
    Mono<UserPublishArticleDetailDTO> getArticle(String openid, String postName);

    /**
     * 保存对已有投稿的修改（标题 / 封面 / 分类 / 标签 / 正文 / 是否允许评论）。
     *
     * <p><b>不再扣积分</b>：投稿时那次已经扣过，修改同一篇内容不该二次收费；
     * 内容安全检测仍然照做（非特权用户）——否则「先发合规内容、过审后再改成违规内容」
     * 就是一条绕过 UGC 检测的现成通道。</p>
     *
     * <p>改动是否立刻在站点上生效，取决于角色策略：直发角色（含博主/管理员）保存后自动重新发布；
     * 待审投稿只更新草稿，前台仍是旧内容，等站长发布时才生效。</p>
     */
    Mono<UserPublishResultDTO> updateArticle(String openid, String postName,
        UserPublishArticleRequest request);

    /**
     * 立即发布 / 重新发布一篇投稿（把尚未生效的修改推上线）。
     *
     * <p>只有直发权限的角色能用：待审投稿若能自行发布，「审核」就形同虚设。</p>
     */
    Mono<UserPublishResultDTO> publishArticleNow(String openid, String postName);

    /**
     * 撤回发布：文章回到草稿态，站点上不再可见（内容保留，可再次发布）。
     *
     * <p>与发布同一道门槛——能推上线的人才能撤回来，否则「撤回」会变成一条死路
     * （撤回后自己再也发不出去，只能求站长）。</p>
     */
    Mono<Void> unpublishArticle(String openid, String postName);

    Mono<UserPublishResultDTO> publishMoment(String openid, UserPublishMomentRequest request);

    /** 瞬间配图上传：拿到可用于提交的图片地址 */
    Mono<String> uploadMomentImage(String openid, FilePart filePart);

    /**
     * 删除瞬间（博主/管理员，或瞬间作者本人）。
     *
     * <p>原先由小程序拿个人令牌直连删除，令牌不再下发后改由服务端代办，
     * 顺带把「谁能删」的判定也收到服务端。</p>
     */
    Mono<Void> deleteMoment(String openid, String momentName);

    /**
     * 提交投票（非匿名投票需要身份，原来由客户端带令牌提交）。
     *
     * @param voteName 投票标识
     * @param body     客户端原始请求体（透传）
     */
    Mono<JsonNode> submitVote(String openid, String voteName, JsonNode body);
}
