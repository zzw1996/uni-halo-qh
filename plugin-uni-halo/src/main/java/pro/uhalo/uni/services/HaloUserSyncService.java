package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.HaloBindResultDTO;
import pro.uhalo.uni.dto.SendHaloBindCodeResponse;
import reactor.core.publisher.Mono;

public interface HaloUserSyncService {

    Mono<SendHaloBindCodeResponse> sendHaloBindCode(String openid, String account);

    Mono<HaloBindResultDTO> bindHaloAccountByCode(String openid, String account, String code);

    Mono<HaloBindResultDTO> createHaloAccount(String openid, String displayName, String avatarUrl);

    Mono<Void> unbindOpenidAnnotation(String haloUserName, String openid);

    Mono<Void> updateHaloUserProfile(String haloUserName, String displayName, String avatarUrl);
}
