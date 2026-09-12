package pro.uhalo.uni.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * 线上字节级校验：用本地假 HTTP 服务录下插件真实发出的请求。
 *
 * <p>Halo 建稿接口报「post is null」时，最先要排除的就是「请求体到底发出去没有」。
 * 这个测试把请求钉在字节层面：方法、Content-Type、以及 body 里必须同时有 post 与 content。</p>
 */
@ExtendWith(MockitoExtension.class)
class WireProbeTest {

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Test
    void captureWireRequest() throws Exception {
        var recorded = new AtomicReference<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var info = new LinkedHashMap<String, Object>();
            info.put("method", exchange.getRequestMethod());
            var headers = new LinkedHashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
            info.put("headers", headers);
            info.put("body", body);
            recorded.set(info);
            var response = "{\"metadata\":{\"name\":\"post-1\"},\"spec\":{\"headSnapshot\":\"head-1\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            lenient().when(settingFetcher.get("basicConfig")).thenReturn(Mono.just(
                new ObjectMapper().readTree("{\"tokenConfig\":{\"personalToken\":\"pat_probe\"}}")));
            // 未配置站点地址 → 走本机回环（就是假服务监听的端口）
            lenient().when(settingFetcher.get("aiDigestConfig"))
                .thenReturn(Mono.just(new ObjectMapper().createObjectNode()));

            var client = new DigestHaloClient(settingFetcher, server.getAddress().getPort());
            var mapper = new ObjectMapper();
            var body = mapper.readTree("""
                {"post":{"apiVersion":"content.halo.run/v1alpha1","kind":"Post",
                 "metadata":{"name":"probe1"},"spec":{"title":"探针","owner":"lxfzzw",
                 "publish":false,"visible":"PUBLIC"}},
                 "content":{"raw":"# 探针","content":"","rawType":"markdown"}}
                """);
            client.post("/apis/api.console.halo.run/v1alpha1/posts", body, Duration.ofSeconds(10))
                .block();

            var info = recorded.get();
            assertThat(info).isNotNull();
            assertThat(info.get("method")).isEqualTo("POST");
            @SuppressWarnings("unchecked")
            var headers = (Map<String, List<String>>) info.get("headers");
            assertThat(headers.get("Content-type")).containsExactly("application/json");
            assertThat(headers.get("Authorization")).containsExactly("Bearer pat_probe");
            // 与后台浏览器一致的请求特征：部分中间层按它区分 XHR
            assertThat(headers.get("X-requested-with")).containsExactly("XMLHttpRequest");
            var sent = mapper.readTree((String) info.get("body"));
            assertThat(sent.has("post")).as("body 必须带 post（Halo 靠它建稿）").isTrue();
            assertThat(sent.has("content")).as("body 必须带 content").isTrue();
            assertThat(sent.path("post").path("apiVersion").asText())
                .isEqualTo("content.halo.run/v1alpha1");
            assertThat(sent.path("post").path("kind").asText()).isEqualTo("Post");
            assertThat(sent.path("content").path("rawType").asText()).isEqualTo("markdown");
        } finally {
            server.stop(0);
        }
    }
}
