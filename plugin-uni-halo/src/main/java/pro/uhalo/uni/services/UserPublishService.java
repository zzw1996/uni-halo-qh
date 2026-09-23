package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.codec.multipart.FilePart;
import pro.uhalo.uni.dto.UserPublishArticleRequest;
import pro.uhalo.uni.dto.UserPublishMomentRequest;
import pro.uhalo.uni.dto.UserPublishResultDTO;
import pro.uhalo.uni.dto.UserPublishStatusDTO;
import reactor.core.publisher.Mono;

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
