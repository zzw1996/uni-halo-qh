package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.HaloBindCodePayload;
import reactor.core.publisher.Mono;

public interface HaloBindCodeStore {

    Mono<Void> assertRateLimitAllowed(String openid, String account);

    Mono<Void> recordRateLimit(String openid, String account);

    Mono<Void> saveCode(String openid, String account, String code, HaloBindCodePayload payload);

    Mono<HaloBindCodePayload> verifyAndConsume(String openid, String account, String code);
}
