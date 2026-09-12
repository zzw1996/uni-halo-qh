package pro.uhalo.uni.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.HaloPostPublishDTO;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 扩展客户端直写：完全不经过 HTTP 接口的发布路径。
 *
 * <p>这条路的必要性：某些 Halo 实例上「个人令牌鉴权」的 POST 请求体在到达处理器时字段会丢，
 * 导致 Console 建稿接口必然 500。插件跑在进程内，直接写扩展是最稳的方式。</p>
 */
@ExtendWith(MockitoExtension.class)
class DigestPostWriterTest {

    @Mock
    private ReactiveExtensionClient client;

    private DigestPostWriter writer;

    @BeforeEach
    void setUp() {
        // 用 2 秒的确认窗口，避免单测等满两分钟
        writer = new DigestPostWriter(client, new DigestMarkdownRenderer(),
            java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(200));
    }

    private static HaloPostPublishDTO dto() {
        return HaloPostPublishDTO.builder()
            .title("AI 日报 · 2026-09-11")
            .slug("ai-digest-2026-09-11")
            .markdown("# 标题\n\n正文一段")
            .owner("lxfzzw")
            .cover("https://example.com/a.jpg")
            .categoryNames(List.of("技术"))
            .tagNames(List.of())
            .build();
    }

    @Test
    void shouldWriteDraftThenSnapshotThenPublish() {
        var capturedPost = new AtomicReference<Post>();
        var capturedSnapshot = new AtomicReference<Snapshot>();
        var updatedPost = new AtomicReference<Post>();

        var draftPublish = new java.util.concurrent.atomic.AtomicBoolean();
        var draftCategories = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
        when(client.create(any(Post.class))).thenAnswer(invocation -> {
            Post draft = invocation.getArgument(0);
            capturedPost.set(draft);
            // 同一个实例后面会被回填修改，建稿那一刻的状态要当场记下来
            draftPublish.set(Boolean.TRUE.equals(draft.getSpec().getPublish()));
            draftCategories.set(draft.getSpec().getCategories());
            return Mono.just(draft);
        });
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> {
            capturedSnapshot.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
        // 回填快照引用时会读一次文章；轮询发布状态时再读
        lenient().when(client.fetch(eq(Post.class), anyString())).thenAnswer(invocation -> {
            Post post = capturedPost.get();
            var status = new Post.PostStatus();
            status.setPhase(Post.PostPhase.PUBLISHED.name());
            status.setPermalink("https://blog.example.com/archives/abc");
            post.setStatus(status);
            return Mono.just(post);
        });
        when(client.update(any(Post.class))).thenAnswer(invocation -> {
            updatedPost.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
        // 分类/标签：库里没有同名项 → 解析结果为空（宁可少分类也不能让文章打不开）
        lenient().when(client.listAll(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(writer.writeAndPublish(dto()))
            .assertNext(result -> {
                assertThat(result.getState()).isEqualTo("PUBLISHED");
                assertThat(result.getPermalink()).contains("/archives/");
            })
            .verifyComplete();

        // 建稿时是草稿；快照引用与发布标记是回填时才写上的
        assertThat(draftPublish.get()).isFalse();
        assertThat(draftCategories.get()).isEmpty();
        assertThat(capturedPost.get().getMetadata().getLabels())
            .containsEntry(Post.OWNER_LABEL, "lxfzzw")
            .containsEntry(Post.VISIBLE_LABEL, "PUBLIC");

        var published = updatedPost.get();
        assertThat(published.getSpec().getPublish()).isTrue();
        assertThat(published.getSpec().getBaseSnapshot())
            .isEqualTo(capturedSnapshot.get().getMetadata().getName());
        assertThat(published.getSpec().getHeadSnapshot())
            .isEqualTo(published.getSpec().getReleaseSnapshot());

        // 快照：raw 存 markdown、contentPatch 存渲染后的 HTML（Halo 不会自己渲染）
        var snapshot = capturedSnapshot.get();
        assertThat(snapshot.getSpec().getRawType()).isEqualTo("markdown");
        // raw 必须是原始 markdown（换行用 \n 表示，避免正文被 HTML 化）
        assertThat(snapshot.getSpec().getRawPatch()).isEqualTo("# 标题\n\n正文一段");
        assertThat(snapshot.getSpec().getContentPatch()).contains("<h1>标题</h1>");
        assertThat(snapshot.getSpec().getSubjectRef().getName())
            .isEqualTo(published.getMetadata().getName());
        // keep-raw 必须在注解里：Halo 的 isBaseSnapshot() 只认 annotations，写进 labels 会导致
        // 文章协调失败（发不出去、打不开、删不掉）
        assertThat(snapshot.getMetadata().getAnnotations())
            .containsEntry(run.halo.app.core.extension.content.Snapshot.KEEP_RAW_ANNO, "true");
    }

    @Test
    void shouldRollBackWhenReleaseNeverConfirms() {
        var created = new java.util.concurrent.atomic.AtomicReference<Post>();
        when(client.create(any(Post.class))).thenAnswer(invocation -> {
            Post draft = invocation.getArgument(0);
            created.set(draft);
            return Mono.just(draft);
        });
        when(client.create(any(Snapshot.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        // 回填能成功（能读到草稿），但协调器始终没把文章置为已发布
        lenient().when(client.fetch(eq(Post.class), anyString()))
            .thenAnswer(invocation -> Mono.just(created.get()));
        lenient().when(client.update(any(Post.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(client.listAll(any(), any(), any()))
            .thenReturn(reactor.core.publisher.Flux.empty());
        // 回滚时会删除文章与快照
        lenient().when(client.delete(any(Post.class))).thenReturn(Mono.empty());
        lenient().when(client.fetch(eq(Snapshot.class), anyString())).thenReturn(Mono.empty());

        // 确认不了发布时必须回滚，不能把一篇打不开的残文留在库里
        writer.writeAndPublish(dto()).as(StepVerifier::create)
            .assertNext(result -> {
                assertThat(result.getState()).isEqualTo("FAILED");
                assertThat(result.getMessage()).contains("回滚");
            })
            .expectComplete()
            .verify(java.time.Duration.ofSeconds(30));
    }
}
