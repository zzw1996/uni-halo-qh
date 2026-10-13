package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentItemDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestFeedService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关键词联网检索。
 *
 * <p>钉住的是「用户输入『邓紫棋』时必须去检索公开新闻」这条链路：内置素材源是科技向的，
 * 娱乐/生活类关键词在那里一条都命不中，生成出来的内容自然与关键词无关（实测反馈过）。
 * 同时钉住两件容易写坏的事：检索结果要<b>去重</b>（同一事件会被多个源带回），
 * 任何一步失败都要<b>降级为空</b>（检索不该成为「简报生成失败」的理由）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiContentSearchServiceTest {

    @Mock
    private DigestFeedService feedService;

    @Mock
    private DigestAssetUploader assetUploader;

    @InjectMocks
    private AiContentSearchService service;

    private static AiContentConfigDTO config(boolean enabled) {
        return AiContentConfigDTO.builder()
            .keywordSearchEnabled(enabled)
            .keywordSearchProviders(List.of("bing", "google"))
            .keywordSearchHours(72)
            .keywordSearchMaxItems(10)
            .keywordSearchCoverEnabled(false)
            .build();
    }

    private static DigestConfigDTO digestConfig() {
        return DigestConfigDTO.builder().build();
    }

    private static DigestArticleDTO article(String title, String link, String source,
        String imageUrl) {
        return article(title, link, source, imageUrl, 2);
    }

    private static DigestArticleDTO article(String title, String link, String source,
        String imageUrl, int hoursAgo) {
        var dto = new DigestArticleDTO();
        dto.setTitle(title);
        dto.setLink(link);
        dto.setSourceName(source);
        dto.setDescription(title + " 的正文摘要");
        dto.setImageUrl(imageUrl);
        dto.setPubDate(Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
        return dto;
    }

    /** 一个词 × 两个源 = 两个检索地址，词要加引号并 URL 编码 */
    @Test
    void shouldBuildOneUrlPerTermAndProvider() {
        var urls = AiContentSearchService.buildUrls(List.of("邓紫棋"), List.of("bing", "google"));

        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).startsWith("https://www.bing.com/news/search?q=");
        assertThat(urls.get(1)).startsWith("https://news.google.com/rss/search?q=");
        // 引号被编码成 %22：不加引号检索会被拆词，结果泛得没法看
        assertThat(urls.get(0)).contains("%22");
        assertThat(urls.get(0)).doesNotContain("邓紫棋");
    }

    /** 源名不认识就丢掉（配置写错不该变成打到乱七八糟地址的请求）；留空用默认两个源 */
    @Test
    void shouldIgnoreUnknownProviders() {
        assertThat(AiContentSearchService.buildUrls(List.of("x"), List.of("bing", "weibo")))
            .hasSize(1);
    }

    @Test
    void disabledOrEmptyTermsShouldSkipFetching() {
        StepVerifier.create(service.search(List.of("邓紫棋"), config(false), digestConfig()))
            .assertNext(items -> assertThat(items).isEmpty())
            .verifyComplete();
        StepVerifier.create(service.search(List.of(), config(true), digestConfig()))
            .assertNext(items -> assertThat(items).isEmpty())
            .verifyComplete();

        verify(feedService, never()).fetchUrls(any(), anyInt());
    }

    /** 正常检索：去重、按时间倒序、映射成简报条目（标题/摘要/链接/来源/时间） */
    @Test
    void shouldDedupAndMapSearchResults() {
        when(feedService.fetchUrls(any(), anyInt())).thenReturn(Mono.just(
            DigestFeedResultDTO.builder()
                .totalFeeds(2)
                .successFeeds(2)
                .articles(List.of(
                    article("邓紫棋世界巡演官宣", "https://a.example/1", "网易娱乐", "", 2),
                    // 同一件事的重复报道（标题只差一个感叹号、链接带跟踪参数）：更旧，应被丢掉
                    article("邓紫棋世界巡演官宣！", "https://a.example/1?utm=1", "新浪娱乐", "", 5),
                    article("邓紫棋新专辑上线", "https://b.example/2", "腾讯娱乐", "", 3)))
                .build()));

        StepVerifier.create(service.search(List.of("邓紫棋"), config(true), digestConfig()))
            .assertNext(items -> {
                assertThat(items).hasSize(2);
                assertThat(items.get(0).getTitle()).isEqualTo("邓紫棋世界巡演官宣");
                assertThat(items.get(0).getSourceName()).isEqualTo("网易娱乐");
                assertThat(items.get(0).getLink()).isEqualTo("https://a.example/1");
                assertThat(items.get(0).getPublishedAt()).isNotBlank();
                assertThat(items.get(0).getSummary()).contains("正文摘要");
            })
            .verifyComplete();
    }

    /** 开启配图：把原文头图转存成站点图片（小程序只能加载白名单域名的图） */
    @Test
    void shouldUploadCoverWhenEnabled() {
        when(feedService.fetchUrls(any(), anyInt())).thenReturn(Mono.just(
            DigestFeedResultDTO.builder().articles(List.of(
                article("邓紫棋世界巡演官宣", "https://a.example/1", "网易娱乐",
                    "https://img.example/cover.jpg"))).build()));
        when(feedService.resolveLeadImages(any())).thenAnswer(invocation ->
            Mono.just(invocation.getArgument(0)));
        when(assetUploader.uploadImage(anyString(), anyString(), any()))
            .thenReturn(Mono.just("/upload/cover.jpg"));

        var withCover = AiContentConfigDTO.builder()
            .keywordSearchEnabled(true)
            .keywordSearchProviders(List.of("bing"))
            .keywordSearchHours(72)
            .keywordSearchMaxItems(10)
            .keywordSearchCoverEnabled(true)
            .build();

        StepVerifier.create(service.search(List.of("邓紫棋"), withCover, digestConfig()))
            .assertNext(items -> {
                assertThat(items).hasSize(1);
                assertThat(items.get(0).getCover()).isEqualTo("/upload/cover.jpg");
            })
            .verifyComplete();

        verify(assetUploader).uploadImage(anyString(), anyString(), any());
    }

    /** 检索失败（网络/解析）不该让简报生成失败：降级为空列表 */
    @Test
    void fetchFailureShouldDegradeToEmpty() {
        when(feedService.fetchUrls(any(), anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.search(List.of("邓紫棋"), config(true), digestConfig()))
            .assertNext(items -> assertThat(items).isEmpty())
            .verifyComplete();
    }

    /** 最多只拿三个词去检索：词太多会成倍放大抓取量 */
    @Test
    void shouldLimitTerms() {
        lenient().when(feedService.fetchUrls(any(), anyInt())).thenReturn(Mono.just(
            DigestFeedResultDTO.builder().articles(List.of()).build()));

        StepVerifier.create(service.search(List.of("a", "b", "c", "d", "e"), config(true),
                digestConfig()))
            .assertNext(items -> assertThat(items).isEmpty())
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(feedService).fetchUrls(captor.capture(), anyInt());
        // 3 个词 × 2 个源
        assertThat(captor.getValue()).hasSize(6);
    }
}
