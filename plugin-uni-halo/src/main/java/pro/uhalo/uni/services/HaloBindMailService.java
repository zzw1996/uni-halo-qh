package pro.uhalo.uni.services;

import reactor.core.publisher.Mono;

public interface HaloBindMailService {

    Mono<Void> sendBindCode(String haloUserName, String toEmail, String code);
}
