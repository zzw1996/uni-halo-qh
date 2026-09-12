package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import pro.uhalo.uni.dto.HaloPostPublishResultDTO;
import pro.uhalo.uni.services.DigestHaloClient;
import pro.uhalo.uni.services.DigestMarkdownRenderer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 建稿前的作者校验口径。
 *
 * <p>线上踩过：把「用户查询 404」当成「作者不存在」并直接中断发布，结果超级管理员本人
 * 也被判成不存在、发布全被拦下。所以这里的约束是——**这个校验永远不能阻断发布**，
 * 只有「用户列表查得到但里面没有该用户名」才算可信结论。</p>
 */
@ExtendWith(MockitoExtension.class)
class HaloPostPublisherImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONSOLE_POSTS = "/apis/api.console.halo.run/v1alpha1/posts";
    private static final String CONTENT_POSTS = "/apis/api.content.halo.run/v1alpha1/posts";
    private static final String CONSOLE_USERS = "/apis/api.console.halo.run/v1alpha1/users";
    private static final String CORE_USER = "/apis/api.halo.run/v1alpha1/users";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Mock
    private DigestHaloClient haloClient;

    @Mock
    private pro.uhalo.uni.services.DigestPostWriter postWriter;

    private HaloPostPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        publisher = new HaloPostPublisherImpl(haloClient, new DigestMarkdownRenderer(),
            postWriter);
        // 自检里的「令牌 / 后台权限」会查一次后台文章列表；发布流程不查它，所以用 lenient
        lenient().when(haloClient.get(startsWith(CONSOLE_POSTS + "?"), any()))
            .thenReturn(json("{\"items\":[]}"));
        // 自检里的「请求体解析」会发一个故意写错的请求体；各测试可自行覆盖这个桩
        lenient().when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenReturn(
            Mono.error(WebClientResponseException.create(400, "Bad Request", null, null, null)));
    }

    private static HaloPostPublishDTO post(String owner) {
        return HaloPostPublishDTO.builder()
            .title("测试标题")
            .slug("test-slug")
            .markdown("# 正文")
            .owner(owner)
            .categoryNames(List.of())
            .tagNames(List.of())
            .build();
    }

    private static reactor.core.publisher.Mono<com.fasterxml.jackson.databind.JsonNode> json(String raw) {
        try {
            return Mono.just(MAPPER.readTree(raw));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }

    /** 建稿 → 触发发布 → 轮询确认 这一整套的桩；分类/标签用 lenient，避免空配置时被判为多余桩 */
    private void stubPublishPath() {
        lenient().when(haloClient.get(eq("/apis/api.content.halo.run/v1alpha1/categories"), any()))
            .thenReturn(json("{\"items\":[]}"));
        lenient().when(haloClient.get(eq("/apis/api.content.halo.run/v1alpha1/tags"), any()))
            .thenReturn(json("{\"items\":[]}"));
        lenient().when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenReturn(json("""
            {"metadata":{"name":"post-1"},"spec":{"headSnapshot":"head-1"}}
            """));
        lenient().when(haloClient.put(startsWith(CONSOLE_POSTS + "/post-1/publish"), any(), any()))
            .thenReturn(json("{}"));
        lenient().when(haloClient.get(eq(CONTENT_POSTS + "/post-1"), any())).thenReturn(json("""
            {"metadata":{"labels":{"content.halo.run/published":"true"}},
             "spec":{"headSnapshot":"head-1","releaseSnapshot":"head-1"},
             "status":{"permalink":"https://blog.example.com/archives/post-1"}}
            """));
    }

    @Test
    void publishShouldProceedEvenWhenOwnerIsNotInUserList() {
        // 用户列表里没有 admin：只告警，仍然建稿发布（不能误杀）
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        stubPublishPath();

        StepVerifier.create(publisher.publish(post("admin"), 60))
            .assertNext(result -> {
                assertThat(result.getState()).isEqualTo("PUBLISHED");
                assertThat(result.getPostName()).isEqualTo("post-1");
            })
            .verifyComplete();
    }

    @Test
    void publishShouldProceedWhenOwnerLookupFailsEntirely() {
        // 用户列表查不了、单查也 404：同样不能阻断发布
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(Mono.error(WebClientResponseException.create(403, "Forbidden", null, null,
                null)));
        when(haloClient.get(startsWith(CORE_USER + "/"), any()))
            .thenReturn(Mono.error(WebClientResponseException.create(404, "Not Found", null, null,
                null)));
        stubPublishPath();

        StepVerifier.create(publisher.publish(post("lxfzzw"), 60))
            .assertNext(result -> assertThat(result.getState()).isEqualTo("PUBLISHED"))
            .verifyComplete();
    }

    @Test
    void createShouldFallBackToMinimalBodyWhenFullBodyIsRejected() {
        // Halo 建稿 500 时先用完整字段、再用精简字段各试一次，并把两次的请求体差别留在日志里
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        stubPublishPath();

        var bodies = new java.util.ArrayList<String>();
        when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode body = invocation.getArgument(1);
            bodies.add(body.toString());
            if (bodies.size() == 1) {
                return Mono.error(WebClientResponseException.create(500, "Internal Server Error",
                    null, null, null));
            }
            return json("{\"metadata\":{\"name\":\"post-1\"},\"spec\":{\"headSnapshot\":\"head-1\"}}");
        });

        StepVerifier.create(publisher.publish(post("lxfzzw"), 60))
            .assertNext(result -> assertThat(result.getState()).isEqualTo("PUBLISHED"))
            .verifyComplete();

        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("htmlMetas").contains("excerpt");
        assertThat(bodies.get(1)).doesNotContain("htmlMetas").doesNotContain("excerpt");
    }

    @Test
    void createShouldFallBackToTwoStepWhenWrappedBodyIsRejected() {
        // 模拟线上现象：带 {post, content} 包裹的请求一律被拒（post 绑不进去），
        // 而「只发 post」的成功 —— 这正是两步法要覆盖的场景
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        stubPublishPath();
        when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode body = invocation.getArgument(1);
            if (body.has("content")) {
                return Mono.error(WebClientResponseException.create(500, "Internal Server Error",
                    null, null, null));
            }
            return json("{\"metadata\":{\"name\":\"post-1\"},\"spec\":{}}");
        });
        when(haloClient.put(eq(CONSOLE_POSTS + "/post-1/content"), any(), any()))
            .thenReturn(json("{\"metadata\":{\"name\":\"post-1\"},"
                + "\"spec\":{\"headSnapshot\":\"head-1\"}}"));

        StepVerifier.create(publisher.publish(post("lxfzzw"), 60))
            .assertNext(result -> assertThat(result.getState()).isEqualTo("PUBLISHED"))
            .verifyComplete();
    }

    @Test
    void createShouldReportEveryAttemptWhenAllFail() {
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        stubPublishPath();
        when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenReturn(
            Mono.error(WebClientResponseException.create(500, "Internal Server Error", null, null,
                null)));
        // HTTP 三条路都失败后会退回扩展客户端直写；两条链路的原因都要带上
        lenient().when(postWriter.writeAndPublish(any(HaloPostPublishDTO.class)))
            .thenReturn(Mono.error(new IllegalStateException("扩展客户端也失败")));

        StepVerifier.create(publisher.publish(post("lxfzzw"), 60))
            .expectErrorMatches(error -> error.getMessage().startsWith("创建文章失败")
                && error.getMessage().contains("完整字段 HTTP 500")
                && error.getMessage().contains("精简字段 HTTP 500")
                && error.getMessage().contains("两步法 HTTP 500")
                && error.getMessage().contains("扩展客户端"))
            .verify();
    }

    @Test
    void createShouldSendRenderedHtmlInContentField() {
        // Halo 不渲染 markdown：raw 存原文、content 必须是渲染后的 HTML，否则文章正文是空的
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        stubPublishPath();
        var captured = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        lenient().when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenAnswer(invocation -> {
            // 只留第一次（完整字段）的请求体：后面还有精简字段与两步法的两次尝试
            captured.compareAndSet(null, invocation.getArgument(1));
            return Mono.error(WebClientResponseException.create(500, "stop", null, null, null));
        });

        publisher.publish(post("lxfzzw"), 60).onErrorResume(ex -> Mono.empty()).block();

        var content = captured.get().path("content");
        assertThat(content.path("raw").asText()).isEqualTo("# 正文");
        assertThat(content.path("content").asText()).contains("<h1>正文</h1>");
        assertThat(content.path("rawType").asText()).isEqualTo("markdown");
    }

    @Test
    void preflightShouldDetectRequestBodyBeingDropped() {
        // 故意写错的请求体也返回 500（而不是类型错误的 400）→ 说明请求体根本没被 Halo 读到
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[{\"metadata\":{\"name\":\"lxfzzw\"}}]}"));
        lenient().when(haloClient.post(eq(CONSOLE_POSTS), any(), any())).thenReturn(
            Mono.error(WebClientResponseException.create(500, "Internal Server Error", null, null,
                null)));

        StepVerifier.create(publisher.preflight(config("lxfzzw")))
            .assertNext(items -> {
                var probe = items.stream().filter(item -> "请求体解析".equals(item.getItem()))
                    .findFirst().orElseThrow();
                assertThat(probe.isOk()).isFalse();
                assertThat(probe.getMessage()).contains("被改动或丢弃");
            })
            .verifyComplete();
    }

    @Test
    void preflightShouldReportExistingOwner() {
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any())).thenReturn(json("""
            {"items":[{"metadata":{"name":"lxfzzw"},"spec":{"displayName":"站长"}}]}
            """));

        StepVerifier.create(publisher.preflight(config("lxfzzw")))
            .assertNext(items -> {
                var owner = items.stream()
                    .filter(item -> "文章作者".equals(item.getItem()))
                    .findFirst().orElseThrow();
                assertThat(owner.isOk()).isTrue();
                assertThat(owner.getMessage()).contains("存在").contains("站长");
            })
            .verifyComplete();
    }

    @Test
    void preflightShouldListExistingUsersWhenOwnerIsMissing() {
        // 可信的「不存在」结论必须带上现有用户名，便于核对是不是拼错/写成了昵称
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any())).thenReturn(json("""
            {"items":[{"metadata":{"name":"lxfzzw"}},{"metadata":{"name":"admin"}}]}
            """));

        StepVerifier.create(publisher.preflight(config("lxfzzw2")))
            .assertNext(items -> {
                var owner = items.stream()
                    .filter(item -> "文章作者".equals(item.getItem()))
                    .findFirst().orElseThrow();
                assertThat(owner.isOk()).isFalse();
                assertThat(owner.getMessage()).contains("lxfzzw").contains("admin");
            })
            .verifyComplete();
    }

    @Test
    void preflightShouldNotBlameOwnerWhenLookupIsInconclusive() {
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(Mono.error(WebClientResponseException.create(403, "Forbidden", null, null,
                null)));
        when(haloClient.get(startsWith(CORE_USER + "/"), any()))
            .thenReturn(Mono.error(WebClientResponseException.create(404, "Not Found", null, null,
                null)));

        StepVerifier.create(publisher.preflight(config("lxfzzw")))
            .assertNext(items -> {
                var owner = items.stream()
                    .filter(item -> "文章作者".equals(item.getItem()))
                    .findFirst().orElseThrow();
                // 查不了就说查不了，不能凭一次失败的查询判定用户名有问题
                assertThat(owner.isOk()).isTrue();
                assertThat(owner.getMessage()).contains("未能校验");
            })
            .verifyComplete();
    }

    @Test
    void preflightShouldNotClaimOwnerMissingWhenUserListIsEmpty() {
        // 列表能请求通但返回空（权限/接口层面的异常）：不能据此断言作者不存在
        when(haloClient.get(startsWith(CONSOLE_USERS + "?"), any()))
            .thenReturn(json("{\"items\":[]}"));

        StepVerifier.create(publisher.preflight(config("lxfzzw")))
            .assertNext(items -> {
                var owner = items.stream()
                    .filter(item -> "文章作者".equals(item.getItem()))
                    .findFirst().orElseThrow();
                assertThat(owner.isOk()).isTrue();
                assertThat(owner.getMessage()).contains("未能校验");
            })
            .verifyComplete();
    }

    private static pro.uhalo.uni.dto.DigestConfigDTO config(String owner) {
        return pro.uhalo.uni.dto.DigestConfigDTO.builder()
            .postOwner(owner)
            .build();
    }
}
