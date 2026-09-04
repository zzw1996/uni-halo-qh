package pro.uhalo.uni.services;

import org.springframework.web.server.ServerWebExchange;
import pro.uhalo.uni.dto.CommentCreatedVO;
import pro.uhalo.uni.dto.CommentDeletedVO;
import pro.uhalo.uni.dto.ReplyCreatedVO;
import pro.uhalo.uni.scheme.WxUser;
import reactor.core.publisher.Mono;

public interface WxCommentProxyService {

    Mono<CommentCreatedVO> createComment(byte[] body, WxUser wxUser, String openid, ServerWebExchange exchange);

    Mono<ReplyCreatedVO> createReply(String commentName, byte[] body, WxUser wxUser, String openid,
                                     ServerWebExchange exchange);

    Mono<CommentDeletedVO> deleteComment(String commentName, String openid);
}
