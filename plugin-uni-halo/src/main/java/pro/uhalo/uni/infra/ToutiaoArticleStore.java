package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.scheme.ToutiaoArticle;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 今日头条号稿件持久化。
 *
 * <p>资源名即时间戳 + 关键词散列：每次生成都是一条新记录（没有幂等键——
 * 同一个关键词本来就允许反复生成不同的稿子），列表按创建时间倒序。</p>
 */
@Component
@RequiredArgsConstructor
public class ToutiaoArticleStore {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ReactiveExtensionClient client;

    public Mono<ToutiaoArticle> fetch(String name) {
        return client.fetch(ToutiaoArticle.class, name);
    }

    public Mono<ToutiaoArticle> create(ToutiaoArticle article) {
        return client.create(article);
    }

    public Mono<ToutiaoArticle> update(ToutiaoArticle article) {
        return client.update(article);
    }

    public Mono<ToutiaoArticle> delete(ToutiaoArticle article) {
        return client.delete(article);
    }

    /** 最近若干条（后台历史列表） */
    public Mono<List<ToutiaoArticle>> listRecent(int limit) {
        return client.listAll(ToutiaoArticle.class, new ListOptions(), null)
            .collectList()
            .map(list -> list.stream()
                .sorted(byCreatedDesc())
                .limit(Math.max(1, limit))
                .toList());
    }

    /** 新建一条「生成中」的记录（调用方随后填结果） */
    public static ToutiaoArticle newArticle(String keyword, String style, int words,
        int titleCount) {
        var article = new ToutiaoArticle();
        article.setKeyword(keyword);
        article.setStyle(style);
        article.setWords(words);
        article.setTitleCount(titleCount);
        article.setState("GENERATING");
        var now = Instant.now();
        article.setCreatedAt(now);
        var metadata = new Metadata();
        metadata.setName(articleName(keyword));
        metadata.setCreationTimestamp(now);
        article.setMetadata(metadata);
        return article;
    }

    /** 资源名：tt-<时间戳>-<散列>（可读、可排序、不重名） */
    public static String articleName(String keyword) {
        var stamp = ZonedDateTime.now(ZONE).format(STAMP);
        var suffix = Integer.toHexString((String.valueOf(keyword) + ":" + System.nanoTime())
            .hashCode());
        return ("tt-" + stamp + "-" + suffix).toLowerCase(Locale.ROOT);
    }

    private static Comparator<ToutiaoArticle> byCreatedDesc() {
        return Comparator.comparing(ToutiaoArticle::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
