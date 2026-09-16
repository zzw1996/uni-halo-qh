package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.DigestSubscriptionRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.DigestSubscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 日报订阅的读写与校验测试。
 *
 * <p>这是<b>匿名可写</b>的接口，因此校验不是礼貌性的：数量与长度必须硬限制，
 * 否则任何人都能提交一份巨大数组把配置存储撑坏。同时「超限即报错、不静默截断」也要钉住——
 * 用户勾了 25 个关键词却被悄悄砍到 20 个，他会以为 25 个都生效了。</p>
 */
@ExtendWith(MockitoExtension.class)
class DigestSubscriptionServiceTest {

    private static final String OPENID = "openid-abc";

    @Mock
    private ReactiveExtensionClient client;

    @InjectMocks
    private DigestSubscriptionService service;

    private static DigestSubscriptionRequest request(Boolean enabled, List<String> categories,
        List<String> keywords, List<String> sources) {
        var req = new DigestSubscriptionRequest();
        req.setEnabled(enabled);
        req.setCategories(categories);
        req.setKeywords(keywords);
        req.setSources(sources);
        return req;
    }

    private void stubNoExisting() {
        when(client.listAll(eq(DigestSubscription.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.create(any(DigestSubscription.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void shouldCreateSubscriptionOnFirstSave() {
        stubNoExisting();

        StepVerifier.create(service.save(OPENID, request(true,
                List.of("ai-ml"), List.of("llm"), List.of("simonwillison.net"))))
            .assertNext(dto -> {
                assertThat(dto.isEnabled()).isTrue();
                assertThat(dto.getCategories()).containsExactly("ai-ml");
                assertThat(dto.getKeywords()).containsExactly("llm");
                assertThat(dto.isFiltered()).as("设了条件就要标记为已筛选").isTrue();
                assertThat(dto.getUpdatedAt()).isNotBlank();
            })
            .verifyComplete();
    }

    /**
     * Halo 正在重建该类型索引时写入会被拒绝，这是临时状态——要重试，不能直接报错。
     *
     * <p>插件刚升级完的那几分钟正好落在这个窗口里，站长点「订阅」十有八九会撞上。</p>
     */
    @Test
    void shouldRetryWhenIndexIsBuilding() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(client.listAll(eq(DigestSubscription.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.create(any(DigestSubscription.class))).thenAnswer(inv -> {
            if (attempts.incrementAndGet() < 3) {
                return Mono.error(new IllegalStateException("Index is building for "
                    + "groupVersionKind uni.uhalo.pro/v1alpha1/DigestSubscription"));
            }
            return Mono.just(inv.getArgument(0));
        });

        StepVerifier.create(service.save(OPENID, request(true, List.of("category-hsmIN"),
                List.of(), List.of())))
            .assertNext(dto -> assertThat(dto.getPostCategories()).containsExactly("category-hsmIN"))
            .verifyComplete();

        assertThat(attempts.get()).as("前两次被拒后应当重试成功").isEqualTo(3);
    }

    /** 其它写入错误不重试：重试没意义，只会让用户多等；原样抛出即可。 */
    @Test
    void shouldNotRetryOtherWriteErrors() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(client.listAll(eq(DigestSubscription.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.create(any(DigestSubscription.class))).thenAnswer(inv -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("表空间已满"));
        });

        StepVerifier.create(service.save(OPENID, request(true, List.of("ai-ml"),
                List.of(), List.of())))
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("表空间已满"))
            .verify();

        assertThat(attempts.get()).as("非索引类错误只尝试一次").isEqualTo(1);
    }

    /** 已订阅的用户再次保存走 update，不能创建出第二条记录。 */
    @Test
    void shouldUpdateExistingSubscription() {
        var existing = new DigestSubscription();
        existing.setWxOpenid(OPENID);
        existing.setEnabled(true);
        when(client.listAll(eq(DigestSubscription.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(existing));
        when(client.update(any(DigestSubscription.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.save(OPENID, request(false,
                List.of("security"), List.of(), List.of())))
            .assertNext(dto -> {
                assertThat(dto.isEnabled()).isFalse();
                assertThat(dto.getCategories()).containsExactly("security");
            })
            .verifyComplete();
    }

    /** 未订阅/未登录时返回空偏好，而不是报错——未登录用户也要能看到日报页。 */
    @Test
    void shouldReturnEmptyWhenAnonymousOrMissing() {
        StepVerifier.create(service.load(null))
            .assertNext(dto -> {
                assertThat(dto.isEnabled()).isFalse();
                assertThat(dto.isFiltered()).isFalse();
                assertThat(dto.getCategories()).isEmpty();
            })
            .verifyComplete();
    }

    /** 选项去重：用户重复勾同一个词不该存两份。 */
    @Test
    void shouldDeduplicatePreferences() {
        stubNoExisting();

        StepVerifier.create(service.save(OPENID, request(true,
                List.of("ai-ml", "ai-ml", " security "), List.of(), List.of())))
            .assertNext(dto -> assertThat(dto.getCategories())
                .containsExactly("ai-ml", "security"))
            .verifyComplete();
    }

    /** 空字符串与纯空白项要丢掉，不能存进去变成「选了但没选」的幽灵条件。 */
    @Test
    void shouldDropBlankItems() {
        stubNoExisting();

        StepVerifier.create(service.save(OPENID, request(true,
                List.of("", "   "), List.of(""), List.of())))
            .assertNext(dto -> {
                assertThat(dto.getCategories()).isEmpty();
                assertThat(dto.isFiltered()).isFalse();
            })
            .verifyComplete();
    }

    /**
     * 站点分类上限 5 个。
     *
     * <p>这个上限比其它维度严得多，因为它决定首页要并发几路去拉文章列表——
     * 上限太松会直接拖慢首页。</p>
     */
    @Test
    void shouldRejectTooManyPostCategories() {
        var tooMany = new ArrayList<String>();
        for (var i = 0; i < 6; i++) {
            tooMany.add("cat" + i);
        }
        var req = request(true, List.of(), List.of(), List.of());
        req.setPostCategories(tooMany);

        StepVerifier.create(service.save(OPENID, req))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(ex.getMessage()).contains("最多选 5 项");
            })
            .verify();
    }

    /** 站点分类要能存下来并原样读回（首页与分类页都靠它）。 */
    @Test
    void shouldPersistPostCategories() {
        stubNoExisting();
        var req = request(true, List.of("ai-ml"), List.of(), List.of());
        req.setPostCategories(List.of("tech", "life"));

        StepVerifier.create(service.save(OPENID, req))
            .assertNext(dto -> assertThat(dto.getPostCategories())
                .containsExactly("tech", "life"))
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(DigestSubscription.class);
        org.mockito.Mockito.verify(client).create(captor.capture());
        assertThat(captor.getValue().getPostCategories()).containsExactly("tech", "life");
    }

    /** 数量超限必须报错而不是静默截断。 */
    @Test
    void shouldRejectTooManyKeywords() {
        var tooMany = new ArrayList<String>();
        for (var i = 0; i < 21; i++) {
            tooMany.add("kw" + i);
        }

        StepVerifier.create(service.save(OPENID, request(true, List.of(), tooMany, List.of())))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(ex.getMessage()).contains("最多选 20 项");
                assertThat(ex.getMessage()).contains("当前 21 项");
            })
            .verify();
    }

    @Test
    void shouldRejectTooManyCategories() {
        var tooMany = new ArrayList<String>();
        for (var i = 0; i < 11; i++) {
            tooMany.add("cat" + i);
        }

        StepVerifier.create(service.save(OPENID, request(true, tooMany, List.of(), List.of())))
            .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("最多选 10 项"))
            .verify();
    }

    /** 单项过长同样报错：匿名写接口不能接受任意长度的字符串。 */
    @Test
    void shouldRejectOverlongItem() {
        var longItem = "x".repeat(41);

        StepVerifier.create(service.save(OPENID, request(true, List.of(), List.of(longItem), List.of())))
            .expectErrorSatisfies(ex -> {
                assertThat(ex).isInstanceOf(WxAuthException.class);
                assertThat(ex.getMessage()).contains("过长");
            })
            .verify();
    }

    /** 恰好到上限应当允许（边界不能误伤）。 */
    @Test
    void shouldAcceptItemsAtTheLimit() {
        stubNoExisting();
        var atLimit = new ArrayList<String>();
        for (var i = 0; i < 20; i++) {
            atLimit.add("kw" + i);
        }
        var maxLength = "y".repeat(40);

        StepVerifier.create(service.save(OPENID, request(true, List.of(), atLimit,
                List.of(maxLength))))
            .assertNext(dto -> {
                assertThat(dto.getKeywords()).hasSize(20);
                assertThat(dto.getSources()).containsExactly(maxLength);
            })
            .verifyComplete();
    }

    /** 传 null body 时按「订阅且无偏好」处理，不能 NPE。 */
    @Test
    void shouldTolerateNullRequest() {
        stubNoExisting();

        StepVerifier.create(service.save(OPENID, null))
            .assertNext(dto -> {
                assertThat(dto.isEnabled()).isTrue();
                assertThat(dto.isFiltered()).isFalse();
            })
            .verifyComplete();
    }

    /** 资源名规则：同一 openid 恒定，且区分大小写（微信 openid 区分大小写）。 */
    @Test
    void resourceNameShouldBeStableAndCaseSensitive() {
        assertThat(DigestSubscriptionService.resourceName("AbC"))
            .isEqualTo(DigestSubscriptionService.resourceName(" AbC "));
        assertThat(DigestSubscriptionService.resourceName("AbC"))
            .as("openid 区分大小写，归一后不同用户会落到同一条记录")
            .isNotEqualTo(DigestSubscriptionService.resourceName("abc"));
    }

    /** 读取已有记录时要把 stored 值原样回传（编辑场景要能看到当前选择）。 */
    @Test
    void shouldReturnStoredPreferences() {
        var existing = new DigestSubscription();
        existing.setWxOpenid(OPENID);
        existing.setEnabled(true);
        existing.setCategories(List.of("ai-ml", "security"));
        existing.setKeywords(List.of("llm"));
        lenient().when(client.listAll(eq(DigestSubscription.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(existing));

        StepVerifier.create(service.load(OPENID))
            .assertNext(dto -> {
                assertThat(dto.getCategories()).containsExactly("ai-ml", "security");
                assertThat(dto.getKeywords()).containsExactly("llm");
                assertThat(dto.getSources()).isEmpty();
                assertThat(dto.isFiltered()).isTrue();
            })
            .verifyComplete();
    }
}
