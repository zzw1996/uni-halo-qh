package pro.uhalo.uni.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.scheme.DigestSeenArticle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 跨天去重记录的读写。
 *
 * <p>关键语义：读取失败必须 fail-open（退化为单轮去重，不阻断生成）；
 * 写入冲突（同一天两轮生成选中同一篇）必须静默忽略——这两条任何一条反了，
 * 日报流水线就会因为「记笔记」这种次要事务而失败。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestSeenStoreTest {

    @Mock
    private ReactiveExtensionClient client;

    private DigestSeenStore store;

    @BeforeEach
    void setUp() {
        store = new DigestSeenStore(client);
    }

    @Test
    void recentKeysShouldCollectNonBlankDedupKeys() {
        when(client.listAll(eq(DigestSeenArticle.class), any(ListOptions.class), any()))
            .thenReturn(Flux.fromIterable(List.of(
                record("link:example.com/a"), record("link:example.com/b"), record(""))));

        StepVerifier.create(store.recentKeys())
            .assertNext(keys -> assertThat(keys)
                .containsExactlyInAnyOrder("link:example.com/a", "link:example.com/b"))
            .verifyComplete();
    }

    @Test
    void recentKeysShouldFailOpenOnError() {
        when(client.listAll(eq(DigestSeenArticle.class), any(ListOptions.class), any()))
            .thenReturn(Flux.error(new IllegalStateException("storage unavailable")));

        StepVerifier.create(store.recentKeys())
            .assertNext(keys -> assertThat(keys).isEmpty())
            .verifyComplete();
    }

    @Test
    void recordAllShouldIgnoreDuplicateNameConflicts() {
        var article = article("https://example.com/x", "标题");
        when(client.create(any(DigestSeenArticle.class)))
            .thenReturn(Mono.error(new IllegalStateException("already exists")));

        StepVerifier.create(store.recordAll(List.of(article), "digest-report-1"))
            .verifyComplete();
    }

    @Test
    void recordAllShouldSkipArticlesWithoutDedupKey() {
        var withLink = article("https://example.com/x", "标题");
        var blank = article("", "");

        when(client.create(any(DigestSeenArticle.class))).thenAnswer(invocation -> {
            var record = invocation.getArgument(0, DigestSeenArticle.class);
            assertThat(record.getDedupKey()).isEqualTo("link:example.com/x");
            assertThat(record.getSeenDate()).isNotBlank();
            return Mono.just(record);
        });

        StepVerifier.create(store.recordAll(List.of(withLink, blank), "digest-report-1"))
            .verifyComplete();
    }

    private static DigestSeenArticle record(String dedupKey) {
        var record = new DigestSeenArticle();
        record.setDedupKey(dedupKey);
        return record;
    }

    private static DigestArticleDTO article(String link, String title) {
        var article = new DigestArticleDTO();
        article.setLink(link);
        article.setTitle(title);
        article.setTitleZh(title);
        return article;
    }
}
