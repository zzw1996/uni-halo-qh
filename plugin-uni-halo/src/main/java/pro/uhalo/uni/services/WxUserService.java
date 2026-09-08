package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.WxUserListResponse;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;

public interface WxUserService {

    Mono<WxUser> fetchByOpenid(String openid);

    Mono<WxUser> fetchByInviteCode(String inviteCode);

    Mono<Long> countByInviterOpenid(String inviterOpenid);

    Mono<WxUser> create(WxUser wxUser);

    Mono<WxUser> update(WxUser wxUser);

    Mono<WxUserListResponse> listUsers(String keyword, int page, int size);

    Mono<WxUserLookup> loadUserLookup();
}
