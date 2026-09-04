package pro.uhalo.uni.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.WxAuthService;
import pro.uhalo.uni.utils.WxRequestParser;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@ApiVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME)
@RequestMapping("/uni-halo")
@RestController
@Slf4j
public class UniHaloController {

    private final WxAuthService wxAuthService;

    public UniHaloController(WxAuthService wxAuthService) {
        this.wxAuthService = wxAuthService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody(required = false) byte[] body) {
        var raw = body != null ? new String(body, StandardCharsets.UTF_8) : "";
        return WxRequestParser.parseLoginRequest(raw)
            .flatMap(wxAuthService::wxLogin)
            .map(dto -> ResponseEntity.ok((Object) dto))
            .onErrorResume(WxAuthException.class, e ->
                Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getReason()))))
            .onErrorResume(Exception.class, e -> {
                log.warn("uni-halo login failed: {}", e.getMessage());
                return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "登录失败，请稍后重试")));
            });
    }
}
