package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.HaloBindCodePayload;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.BindCodeStorageRouter;
import pro.uhalo.uni.infra.HaloBindCodeKeys;
import pro.uhalo.uni.services.HaloBindCodeStore;
import reactor.core.publisher.Mono;

@Component
@Slf4j
@RequiredArgsConstructor
public class HaloBindCodeStoreImpl implements HaloBindCodeStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BindCodeStorageRouter storageRouter;

    @Override
    public Mono<Void> assertRateLimitAllowed(String openid, String account) {
        return storageRouter.withBackend(backend -> {
            backend.assertRateLimitAllowed(openid, account);
            return null;
        }).then();
    }

    @Override
    public Mono<Void> recordRateLimit(String openid, String account) {
        return storageRouter.withBackend(backend -> {
            backend.recordRateLimit(openid, account);
            return null;
        }).then();
    }

    @Override
    public Mono<Void> saveCode(String openid, String account, String code, HaloBindCodePayload payload) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.writeValueAsString(payload))
            .flatMap(json -> storageRouter.withBackend(backend -> {
                backend.saveCode(openid, account, code, json);
                log.info("bind code stored, key={}", HaloBindCodeKeys.buildCodeKey(openid, account));
                return null;
            }))
            .then()
            .onErrorMap(JsonProcessingException.class,
                ex -> new WxAuthException("验证码缓存失败"));
    }

    @Override
    public Mono<HaloBindCodePayload> verifyAndConsume(String openid, String account, String code) {
        var normalizedCode = code.trim();
        return storageRouter.withBackend(backend -> {
            String raw;
            try {
                raw = backend.getCode(openid, account);
            } catch (WxAuthException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new WxAuthException("验证码校验失败：" + ex.getMessage());
            }
            if (!StringUtils.hasText(raw)) {
                throw new WxAuthException("请先获取验证码");
            }
            var payload = parseAndValidateCode(raw, normalizedCode);
            backend.deleteCode(openid, account);
            log.info("bind code consumed, key={}", HaloBindCodeKeys.buildCodeKey(openid, account));
            return payload;
        });
    }

    private HaloBindCodePayload parseAndValidateCode(String raw, String code) {
        var separator = raw.indexOf('|');
        if (separator <= 0) {
            throw new WxAuthException("验证码已过期");
        }
        var cachedCode = raw.substring(0, separator);
        if (!cachedCode.equals(code)) {
            throw new WxAuthException("验证码错误");
        }
        var json = raw.substring(separator + 1);
        try {
            return OBJECT_MAPPER.readValue(json, HaloBindCodePayload.class);
        } catch (JsonProcessingException ex) {
            throw new WxAuthException("验证码已过期");
        }
    }

    static String buildCodeKey(String openid, String account) {
        return HaloBindCodeKeys.buildCodeKey(openid, account);
    }

    static String buildRateKey(String openid, String account) {
        return HaloBindCodeKeys.buildRateKey(openid, account);
    }

    static String normalizeAccountKey(String value) {
        return HaloBindCodeKeys.normalize(value);
    }
}
