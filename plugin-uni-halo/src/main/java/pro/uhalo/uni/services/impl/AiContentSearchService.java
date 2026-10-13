package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentItemDTO;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.services.DigestAssetUploader;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.utils.DigestAiHelper;
import pro.uhalo.uni.utils.DigestTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 「AI 定制内容」的关键词联网检索。
 *
 * <p><b>为什么必须有这一层</b>：素材池原本只有 AI 日报那批内置源（科技博客、HN 等），
 * 用户输入「邓紫棋」这类关键词在那里必然一条都命不中，AI 只能从无关素材里挑——
 * 于是「生成的内容跟关键词没有任何关联」。这里把用户输入的词拿去检索公开新闻
 * （Bing / Google 新闻的检索 RSS，无需密钥），检索结果作为素材池的第一顺位。</p>
 *
 * <p>图片走与日报同一条链路：先解析原文页的 og:image，再转存进 Halo 附件库——
 * 小程序只能加载白名单域名的图片，直链新闻站的外链图在真机上根本显示不出来。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentSearchService {

    /** 检索源模板：{@code {q}} 会被替换成 URL 编码后的关键词 */
    private static final String BING = "https://www.bing.com/news/search?q={q}&format=RSS&count=20";
    private static final String GOOGLE =
        "https://news.google.com/rss/search?q={q}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
    private static final String BAIDU =
        "https://news.baidu.com/ns?word={q}&tn=newsrss&sr=0&cl=2&rn=20&ct=0";

    /** 一次最多检索几个词：词太多会成倍放大抓取量，而素材够挑就行 */
    private static final int MAX_TERMS = 3;
    /** 每个词最多配几张头图（抓原文页是最慢的一步，要设上限） */
    private static final int MAX_COVERS = 6;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUB_TIME_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DigestFeedService feedService;
    private final DigestAssetUploader assetUploader;

    /**
     * 按关键词检索公开新闻，返回可直接作为简报素材的条目（已去重、按时间倒序）。
     *
     * <p>任何一步失败都返回已拿到的部分（抓不到就是空列表），由调用方决定兜底——
     * 检索不该成为「简报生成失败」的理由。</p>
     */
    public Mono<List<AiContentItemDTO>> search(List<String> terms, AiContentConfigDTO config,
        DigestConfigDTO digestConfig) {
        var wanted = normalizeTerms(terms);
        if (wanted.isEmpty() || !config.isKeywordSearchEnabled()) {
            return Mono.just(List.of());
        }
        var urls = buildUrls(wanted, config.getKeywordSearchProviders());
        if (urls.isEmpty()) {
            log.warn("[UniHalo][AiContent] 关键词检索已开启，但没有可用的检索源（检查「检索源」配置）");
            return Mono.just(List.of());
        }
        return feedService.fetchUrls(urls, config.getKeywordSearchHours())
            .map(result -> dedup(result.getArticles()))
            .map(articles -> articles.stream()
                .limit(Math.max(1, config.getKeywordSearchMaxItems()))
                .toList())
            .flatMap(articles -> withCovers(articles, config, digestConfig))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 关键词检索失败（按无结果处理）：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 检索用的地址清单：每个词 × 每个启用的检索源 */
    static List<String> buildUrls(List<String> terms, List<String> providers) {
        var wanted = normalizeTerms(terms);
        var urls = new ArrayList<String>();
        for (var term : wanted) {
            // 加引号再检索：不加会被拆成多个词、结果泛得没法看
            var encoded = URLEncoder.encode("\"" + term + "\"", StandardCharsets.UTF_8);
            for (var provider : providers) {
                var template = templateOf(provider);
                if (template != null) {
                    urls.add(template.replace("{q}", encoded));
                }
            }
        }
        return List.copyOf(urls);
    }

    private static String templateOf(String provider) {
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "bing" -> BING;
            case "google" -> GOOGLE;
            case "baidu" -> BAIDU;
            default -> null;
        };
    }

    private static List<String> normalizeTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        for (var term : terms) {
            var trimmed = term == null ? "" : term.trim();
            if (StringUtils.hasText(trimmed) && trimmed.length() <= 40) {
                seen.add(trimmed);
            }
            if (seen.size() >= MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    /** 去重：同一个事件会被多个检索源、多个词重复带回 */
    private static List<DigestArticleDTO> dedup(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        var seenLinks = new LinkedHashSet<String>();
        var seenTitles = new LinkedHashSet<String>();
        var result = new ArrayList<DigestArticleDTO>();
        articles.stream()
            .filter(article -> StringUtils.hasText(article.getTitle())
                || StringUtils.hasText(article.getLink()))
            .sorted(Comparator.comparing(DigestArticleDTO::getPubDate,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .forEach(article -> {
                var linkKey = DigestTextUtils.dedupKey(article.getLink(), article.getTitle());
                var titleKey = titleKeyOf(article.getTitle());
                if (seenLinks.contains(linkKey) || (StringUtils.hasText(titleKey)
                    && seenTitles.contains(titleKey))) {
                    return;
                }
                seenLinks.add(linkKey);
                if (StringUtils.hasText(titleKey)) {
                    seenTitles.add(titleKey);
                }
                result.add(article);
            });
        return List.copyOf(result);
    }

    /**
     * 给前几条补头图：先解析原文页的 og:image，再转存进 Halo 附件库。
     *
     * <p>只做前 {@value #MAX_COVERS} 条：抓原文页是最慢的一步，条数一多就把生成时间拖长了，
     * 而简报里带图的也就前几条。</p>
     */
    private Mono<List<AiContentItemDTO>> withCovers(List<DigestArticleDTO> articles,
        AiContentConfigDTO config, DigestConfigDTO digestConfig) {
        if (articles.isEmpty()) {
            return Mono.just(List.of());
        }
        var limited = articles.stream().limit(Math.max(1, config.getKeywordSearchMaxItems()))
            .toList();
        if (!config.isKeywordSearchCoverEnabled()) {
            return Mono.just(limited.stream().map(article -> toItem(article, "")).toList());
        }
        return feedService.resolveLeadImages(limited)
            .flatMap(resolved -> Flux.fromIterable(resolved)
                .index()
                .flatMapSequential(tuple -> {
                    var index = tuple.getT1().intValue();
                    var article = tuple.getT2();
                    if (index >= MAX_COVERS
                        || !DigestTextUtils.isUsableImageUrl(article.getImageUrl())) {
                        return Mono.just(toItem(article, ""));
                    }
                    var baseName = "aic-" + DigestTextUtils.slugify(article.getSourceName());
                    return assetUploader.uploadImage(article.getImageUrl(), baseName, digestConfig)
                        .map(permalink -> toItem(article, permalink))
                        .onErrorResume(ex -> {
                            log.warn("[UniHalo][AiContent] 检索结果配图上传失败（跳过该图）：{}",
                                ex.getMessage());
                            return Mono.just(toItem(article, ""));
                        });
                }, 4)
                .collectList());
    }

    private static AiContentItemDTO toItem(DigestArticleDTO article, String cover) {
        return AiContentItemDTO.builder()
            .title(article.getTitle() == null ? "" : article.getTitle().trim())
            .summary(DigestAiHelper.truncateForPrompt(article.getDescription(), 160))
            .link(article.getLink())
            .sourceName(article.getSourceName())
            .sourceUrl(article.getSourceUrl())
            .cover(cover)
            .publishedAt(formatPubDate(article.getPubDate()))
            .build();
    }

    /**
     * 标题指纹：只保留字母、数字与汉字，再小写。
     *
     * <p>同一件事在不同源上的标题往往只差几个标点或空格，光比链接挡不住重复。
     * 只做「去掉标点空白」，不做语义归一——宁可留两条，也别把不同的事判成同一条。</p>
     */
    private static String titleKeyOf(String title) {
        if (!StringUtils.hasText(title)) {
            return "";
        }
        var builder = new StringBuilder(title.length());
        title.codePoints().forEach(code -> {
            if (Character.isLetterOrDigit(code)) {
                builder.appendCodePoint(code);
            }
        });
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static String formatPubDate(Instant pubDate) {
        return pubDate == null ? "" : PUB_TIME_FMT.format(pubDate.atZone(ZONE));
    }
}
