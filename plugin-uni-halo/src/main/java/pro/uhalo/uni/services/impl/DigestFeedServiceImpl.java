package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import pro.uhalo.uni.dto.DigestArticleDTO;
import pro.uhalo.uni.dto.DigestFeedResultDTO;
import pro.uhalo.uni.services.DigestFeedService;
import pro.uhalo.uni.utils.DigestTextUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * RSS/Atom 抓取与解析。
 *
 * <p>用 JDK 自带的 DOM 解析而不是正则：RSS 里 description、content:encoded 都可能带
 * 大段转义 HTML，正则很容易在嵌套标签上截断。DOM 同时天然处理 CDATA。</p>
 */
@Slf4j
@Service
public class DigestFeedServiceImpl implements DigestFeedService {

    private static final Duration FEED_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(15);
    private static final int FEED_CONCURRENCY = 10;
    private static final int IMAGE_CONCURRENCY = 10;
    private static final int MAX_REDIRECT_HOPS = 3;
    private static final int DESCRIPTION_LIMIT = 500;

    private static final String FEED_USER_AGENT = "AI-Daily-Digest/1.0 (RSS Reader)";
    private static final String FEED_ACCEPT =
        "application/rss+xml, application/atom+xml, application/xml, text/xml, */*";
    private static final String PAGE_USER_AGENT =
        "Mozilla/5.0 (compatible; AI-Daily-Digest/1.0; +https://github.com/)";
    private static final String PAGE_ACCEPT = "text/html,application/xhtml+xml";

    private static final List<Pattern> OG_IMAGE_PATTERNS = List.of(
        Pattern.compile("<meta[^>]+property=[\"']og:image:secure_url[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("<meta[^>]+name=[\"']twitter:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']twitter:image[\"']",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE)
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<FeedSource> FEEDS = loadFeeds();

    private final WebClient webClient = WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    @Override
    public Mono<DigestFeedResultDTO> fetchRecent(int hours) {
        var cutoff = Instant.now().minusSeconds(Math.max(1, hours) * 3600L);
        var startedMs = System.currentTimeMillis();
        log.info("[UniHalo][Digest] Step 1/5 开始抓取 RSS：{} 个源，并发 {}，时间窗 {}h",
            FEEDS.size(), FEED_CONCURRENCY, hours);
        return Flux.fromIterable(FEEDS)
            .flatMapSequential(this::fetchFeedSafely, FEED_CONCURRENCY)
            .collectList()
            .map(outcomes -> {
                var articles = new ArrayList<DigestArticleDTO>();
                var successFeeds = 0;
                var parsed = 0;
                for (var outcome : outcomes) {
                    if (outcome.articles().isEmpty()) {
                        continue;
                    }
                    successFeeds++;
                    parsed += outcome.articles().size();
                    for (var article : outcome.articles()) {
                        if (article.getPubDate() != null && article.getPubDate().isAfter(cutoff)) {
                            articles.add(article);
                        }
                    }
                }
                log.info("[UniHalo][Digest] Step 1/5 RSS 抓取完成：成功 {}/{} 源，解析 {} 篇，"
                        + "命中时间窗 {} 篇，用时 {}s",
                    successFeeds, FEEDS.size(), parsed, articles.size(),
                    Math.max(0, (System.currentTimeMillis() - startedMs) / 1000));
                return DigestFeedResultDTO.builder()
                    .totalFeeds(FEEDS.size())
                    .successFeeds(successFeeds)
                    .articles(articles)
                    .build();
            });
    }

    @Override
    public Mono<List<DigestArticleDTO>> resolveLeadImages(List<DigestArticleDTO> articles) {
        if (articles == null || articles.isEmpty()) {
            return Mono.just(List.of());
        }
        var startedMs = System.currentTimeMillis();
        return Flux.fromIterable(articles)
            .flatMap(article -> {
                if (DigestTextUtils.isUsableImageUrl(article.getImageUrl())
                    || !StringUtils.hasText(article.getLink())) {
                    return Mono.just(article);
                }
                return fetchPageImage(article.getLink())
                    .doOnNext(article::setImageUrl)
                    .thenReturn(article);
            }, IMAGE_CONCURRENCY)
            .collectList()
            .doOnNext(list -> {
                var found = list.stream()
                    .filter(article -> DigestTextUtils.isUsableImageUrl(article.getImageUrl()))
                    .count();
                log.info("[UniHalo][Digest] 头图解析完成：{}/{} 篇找到图片，用时 {}s",
                    found, list.size(), Math.max(0, (System.currentTimeMillis() - startedMs) / 1000));
            });
    }

    // ── 抓取 ──

    private Mono<FeedOutcome> fetchFeedSafely(FeedSource feed) {
        return getText(feed.xmlUrl(), FEED_USER_AGENT, FEED_ACCEPT, FEED_TIMEOUT)
            .map(xml -> {
                try {
                    return new FeedOutcome(feed, parseItems(xml, feed));
                } catch (Exception ex) {
                    log.warn("[UniHalo][Digest] 源解析失败 {}: {}", feed.name(), ex.getMessage());
                    return new FeedOutcome(feed, List.of());
                }
            })
            .defaultIfEmpty(new FeedOutcome(feed, List.of()))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 源抓取失败 {}: {}", feed.name(), ex.getMessage());
                return Mono.just(new FeedOutcome(feed, List.of()));
            });
    }

    private Mono<String> fetchPageImage(String pageUrl) {
        return getText(pageUrl, PAGE_USER_AGENT, PAGE_ACCEPT, PAGE_TIMEOUT)
            .map(html -> {
                for (var pattern : OG_IMAGE_PATTERNS) {
                    var matcher = pattern.matcher(html);
                    if (!matcher.find()) {
                        continue;
                    }
                    var candidate = DigestTextUtils.absolutizeUrl(matcher.group(1), pageUrl);
                    // 兜底：候选不能是文章页本身（站点把 og:image 写成了页面地址的情况）
                    if (DigestTextUtils.isUsableImageUrl(candidate) && !candidate.equals(pageUrl)) {
                        return candidate;
                    }
                }
                return "";
            })
            .defaultIfEmpty("")
            .onErrorResume(ex -> Mono.just(""));
    }

    /**
     * 带手动重定向跟随的文本 GET。
     *
     * <p>不走 Reactor Netty 的 followRedirect：默认连接器不跟随 3xx，而插件编译期只能拿到
     * Halo 提供的依赖，直接依赖 reactor-netty 配连接器会把编译环境绑死；手动处理更稳。</p>
     */
    private Mono<String> getText(String url, String userAgent, String accept, Duration timeout) {
        return getText(url, userAgent, accept, timeout, 0);
    }

    private Mono<String> getText(String url, String userAgent, String accept, Duration timeout, int hop) {
        if (!StringUtils.hasText(url)) {
            return Mono.empty();
        }
        return webClient.get()
            .uri(url)
            .header(HttpHeaders.USER_AGENT, userAgent)
            .header(HttpHeaders.ACCEPT, accept)
            .exchangeToMono(response -> {
                if (response.statusCode().is3xxRedirection()) {
                    var location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                    if (hop >= MAX_REDIRECT_HOPS || !StringUtils.hasText(location)) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    var next = DigestTextUtils.absolutizeUrl(location, url);
                    if (!StringUtils.hasText(next)) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    return response.releaseBody().then(Mono.defer(
                        () -> getText(next, userAgent, accept, timeout, hop + 1)));
                }
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.releaseBody().then(Mono.empty());
                }
                return response.bodyToMono(String.class);
            })
            .timeout(timeout);
    }

    // ── 解析 ──

    /** 解析一批条目；独立出包级可见性便于测试（XML 解析是整条流水线里最容易静默失败的一环） */
    List<DigestArticleDTO> parseItems(String xml, FeedSource feed) throws Exception {
        var document = parseXml(xml);
        // RSS 2.0 的 <item> 不带命名空间，Atom 的 <entry> 一定带命名空间；
        // 两种取法各试一遍，避免被命名空间感知与否影响。
        var nodes = document.getElementsByTagNameNS("*", "item");
        var atom = false;
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName("item");
        }
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagNameNS("*", "entry");
            atom = nodes.getLength() > 0;
        }
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName("entry");
            atom = true;
        }

        var articles = new ArrayList<DigestArticleDTO>();
        for (var i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            var article = atom ? parseAtomEntry(element, feed) : parseRssItem(element, feed);
            if (article != null) {
                articles.add(article);
            }
        }
        return articles;
    }

    private DigestArticleDTO parseRssItem(Element item, FeedSource feed) {
        var title = DigestTextUtils.stripHtml(childText(item, "title"));
        var link = firstNonBlank(childText(item, "link"), childText(item, "guid"));
        var pubDate = firstNonBlank(childText(item, "pubdate"), childText(item, "date"),
            childText(item, "published"), childText(item, "updated"));
        var rawBody = firstNonBlank(childText(item, "description"), childText(item, "encoded"),
            childText(item, "content"));
        return buildArticle(feed, title, link, pubDate, rawBody, item);
    }

    private DigestArticleDTO parseAtomEntry(Element entry, FeedSource feed) {
        var title = DigestTextUtils.stripHtml(childText(entry, "title"));
        var link = atomLink(entry);
        var pubDate = firstNonBlank(childText(entry, "published"), childText(entry, "updated"));
        var rawBody = firstNonBlank(childText(entry, "summary"), childText(entry, "content"));
        return buildArticle(feed, title, link, pubDate, rawBody, entry);
    }

    private DigestArticleDTO buildArticle(FeedSource feed, String title, String link,
        String pubDate, String rawBody, Element item) {
        if (!StringUtils.hasText(title) && !StringUtils.hasText(link)) {
            return null;
        }
        var article = new DigestArticleDTO();
        article.setTitle(title);
        article.setLink(link);
        article.setPubDate(parseDate(pubDate));
        article.setDescription(truncate(DigestTextUtils.stripHtml(rawBody), DESCRIPTION_LIMIT));
        article.setSourceName(feed.name());
        article.setSourceUrl(feed.htmlUrl());
        article.setImageUrl(extractItemImage(item, rawBody, link));
        return article;
    }

    /**
     * 一篇文章的首图：优先正文里的 <img>，其次 media:content / media:thumbnail，
     * 最后是 image 类型的 enclosure。
     */
    private String extractItemImage(Element item, String rawBody, String baseUrl) {
        var inline = DigestTextUtils.extractInlineImage(rawBody, baseUrl);
        if (StringUtils.hasText(inline)) {
            return inline;
        }
        for (var tag : List.of("content", "thumbnail")) {
            var candidate = DigestTextUtils.absolutizeUrl(childAttr(item, tag, "url"), baseUrl);
            if (DigestTextUtils.isUsableImageUrl(candidate)) {
                return candidate;
            }
        }
        // 播客类源的第一个 enclosure 往往是音频，要遍历所有 enclosure 找图片类型那一个
        var fallback = "";
        for (var child : childElements(item)) {
            if (!"enclosure".equals(localName(child))) {
                continue;
            }
            var enclosureUrl = child.getAttribute("url");
            if (!StringUtils.hasText(enclosureUrl)) {
                continue;
            }
            var type = child.getAttribute("type");
            var candidate = DigestTextUtils.absolutizeUrl(enclosureUrl, baseUrl);
            if (!DigestTextUtils.isUsableImageUrl(candidate)) {
                continue;
            }
            if (StringUtils.hasText(type) && type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return candidate;
            }
            if (fallback.isEmpty() && !StringUtils.hasText(type)) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private String atomLink(Element entry) {
        String fallback = "";
        for (var child : childElements(entry)) {
            if (!"link".equals(localName(child))) {
                continue;
            }
            var href = child.getAttribute("href");
            if (!StringUtils.hasText(href)) {
                continue;
            }
            var rel = child.getAttribute("rel");
            if (!StringUtils.hasText(rel) || "alternate".equalsIgnoreCase(rel)) {
                return href.trim();
            }
            if (fallback.isEmpty()) {
                fallback = href.trim();
            }
        }
        return fallback;
    }

    private Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // 关掉外部实体与 DTD 加载：RSS 源是不可信输入，避免 XXE 与内网探测
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String childText(Element parent, String wantedLocalName) {
        for (var child : childElements(parent)) {
            if (wantedLocalName.equals(localName(child))) {
                var text = child.getTextContent();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private static String childAttr(Element parent, String wantedLocalName, String attr) {
        for (var child : childElements(parent)) {
            if (wantedLocalName.equals(localName(child)) && child.hasAttribute(attr)) {
                var value = child.getAttribute(attr);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static List<Element> childElements(Element parent) {
        var elements = new ArrayList<Element>();
        var children = parent.getChildNodes();
        for (var i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /**
     * 取元素本地名。
     *
     * <p>关闭命名空间感知的文档里 {@code getLocalName()} 会返回 null，
     * 这时退回 nodeName 并剥掉前缀（如 {@code media:content} → {@code content}）。</p>
     */
    private static String localName(Node node) {
        var local = node.getLocalName();
        if (local != null) {
            return local.toLowerCase(Locale.ROOT);
        }
        var name = node.getNodeName();
        if (name == null) {
            return "";
        }
        var idx = name.indexOf(':');
        return (idx >= 0 ? name.substring(idx + 1) : name).toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 带时区的格式（RSS 的 RFC-822 与 Atom 的 ISO-8601 混用）。
     *
     * <p>刻意不用 {@link DateTimeFormatter#RFC_1123_DATE_TIME}：它会交叉校验「星期几」与「日期」
     * 是否一致，而 RSS 源给出的星期几经常是错的（甚至本身就是按发布日粗略填的），
     * 一不一致就整条解析失败、文章被静默丢掉。这里先剥掉星期前缀，再按日期时间解析。</p>
     */
    private static final List<DateTimeFormatter> ZONED_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss XXX", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME);

    /** 不带时区的格式，统一按 UTC 解释 */
    private static final List<DateTimeFormatter> LOCAL_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH));

    private static final Pattern WEEKDAY_PREFIX_PATTERN =
        Pattern.compile("^[A-Za-z]{3,9}\\s*,\\s*");

    /**
     * 日期解析：RSS 用 RFC-822、Atom 用 ISO-8601，逐个格式试，解析不出来返回 null。
     *
     * <p>返回 null 的文章不会进入时间窗（脏数据不该被当成「很新」）。</p>
     */
    static Instant parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var text = WEEKDAY_PREFIX_PATTERN.matcher(value.trim()).replaceFirst("");

        for (var formatter : ZONED_FORMATTERS) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant();
            } catch (Exception ignored) {
                // 换下一个格式
            }
            try {
                return OffsetDateTime.parse(text, formatter).toInstant();
            } catch (Exception ignored) {
                // 换下一个格式
            }
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            // 换下一个格式
        }
        for (var formatter : LOCAL_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // 换下一个格式
            }
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<FeedSource> loadFeeds() {
        try (var in = new ClassPathResource("digest/feeds.json").getInputStream()) {
            var root = OBJECT_MAPPER.readTree(in.readAllBytes());
            var feeds = new ArrayList<FeedSource>();
            for (var node : root.path("feeds")) {
                var name = node.path("name").asText("");
                var xmlUrl = node.path("xmlUrl").asText("");
                var htmlUrl = node.path("htmlUrl").asText("");
                if (StringUtils.hasText(name) && StringUtils.hasText(xmlUrl)) {
                    feeds.add(new FeedSource(name, xmlUrl, htmlUrl));
                }
            }
            log.info("[UniHalo][Digest] 已加载 {} 个 RSS 源", feeds.size());
            return List.copyOf(feeds);
        } catch (Exception ex) {
            log.error("[UniHalo][Digest] RSS 源清单加载失败: {}", ex.getMessage(), ex);
            return List.of();
        }
    }

    /** RSS 源描述；包级可见以便测试直接构造 */
    record FeedSource(String name, String xmlUrl, String htmlUrl) {
    }

    private record FeedOutcome(FeedSource feed, List<DigestArticleDTO> articles) {
    }
}
