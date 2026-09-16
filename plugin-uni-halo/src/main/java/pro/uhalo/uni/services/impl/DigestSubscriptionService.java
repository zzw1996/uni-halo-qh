package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.DigestSubscriptionRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.DigestSubscription;
import pro.uhalo.uni.utils.DigestPreferenceMatcher;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * AI 日报订阅偏好：读取、保存与校验。
 *
 * <p><b>这是匿名可写的接口</b>，因此校验不是礼貌性的：数量与长度都必须硬限制，
 * 否则任何人都能提交一份巨大的数组把配置存储撑坏。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestSubscriptionService {

    /** 各类偏好的数量上限（匿名接口，必须封顶） */
    private static final int MAX_CATEGORIES = 10;
    private static final int MAX_KEYWORDS = 20;
    private static final int MAX_SOURCES = 20;

    /** 站点分类上限：首页要按这几路并发拉取，太多既无必要也会拖慢首页 */
    private static final int MAX_POST_CATEGORIES = 5;

    /** 单个偏好项的长度上限 */
    private static final int MAX_ITEM_LENGTH = 40;

    /**
     * Halo 重建某类扩展的索引期间，会拒绝该类的一切写入（见 Halo 的
     * {@code ReactiveExtensionClientImpl#checkClientWritable}）。
     *
     * <p>正常情况下这个状态几秒内就结束，所以值得短暂重试——让用户在「插件刚升级完」
     * 这种时刻点订阅也能存上。真正卡死的情况（Halo 的 {@code createIndexerFor}
     * 没有 try/finally，构建中途失败会把「正在建索引」永久留在那个类型上）重试救不回来，
     * 那时接口会把原因如实告诉用户，让人去重启服务。</p>
     */
    private static final int INDEX_BUILD_RETRIES = 3;
    private static final Duration INDEX_BUILD_BACKOFF = Duration.ofMillis(400);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveExtensionClient client;

    /** 是否是「Halo 正在建索引，暂时不让写」这类错误 */
    public static boolean isIndexBuildingError(Throwable error) {
        var message = error == null ? null : error.getMessage();
        return message != null && message.contains("Index is building");
    }

    /** 读取该用户的订阅偏好；没有记录时返回「未订阅且无偏好」。 */
    public Mono<DigestSubscriptionDTO> load(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(empty());
        }
        return findOne(openid.trim())
            .map(DigestSubscriptionService::toDto)
            .defaultIfEmpty(empty());
    }

    /**
     * 保存订阅偏好。
     *
     * <p>记录名由 openid 派生，因此同一用户永远只有一条：首次写入是 create，
     * 之后是 update，不需要用户自己关心「订阅过没有」。</p>
     */
    public Mono<DigestSubscriptionDTO> save(String openid, DigestSubscriptionRequest request) {
        // 用 defer 包住：校验失败必须以 error 信号返回，而不是同步抛异常。
        // 返回 Mono 的方法同步 throw 会让调用方在响应式链外使用时直接炸掉
        // （而且测试也没法用 StepVerifier 断言「返回了什么错误」）。
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
            }
            var normalizedOpenid = openid.trim();
            var enabled = request == null || request.getEnabled() == null
                || Boolean.TRUE.equals(request.getEnabled());
            var categories = sanitize(request == null ? null : request.getCategories(),
                MAX_CATEGORIES, "关注的分类");
            var keywords = sanitize(request == null ? null : request.getKeywords(),
                MAX_KEYWORDS, "关注的关键词");
            var sources = sanitize(request == null ? null : request.getSources(),
                MAX_SOURCES, "关注的来源");
            var postCategories = sanitize(request == null ? null : request.getPostCategories(),
                MAX_POST_CATEGORIES, "关注的分类（首页）");

            return findOne(normalizedOpenid)
                .flatMap(existing -> {
                    existing.setEnabled(enabled);
                    existing.setCategories(categories);
                    existing.setKeywords(keywords);
                    existing.setSources(sources);
                    existing.setPostCategories(postCategories);
                    existing.setUpdatedAt(Instant.now());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(newSubscription(
                    normalizedOpenid, enabled, categories, keywords, sources, postCategories))))
                // 写入前会先读一次（上面 findOne），读取不受索引构建影响，所以重试只会重跑写
                .retryWhen(Retry.backoff(INDEX_BUILD_RETRIES, INDEX_BUILD_BACKOFF)
                    .filter(DigestSubscriptionService::isIndexBuildingError)
                    .doBeforeRetry(signal -> log.warn(
                        "[UniHalo][Digest] Halo 正在构建索引，订阅保存稍后重试（第 {} 次）",
                        signal.totalRetries() + 1)))
                .map(DigestSubscriptionService::toDto);
        });
    }

    /**
     * 清洗并校验偏好项。
     *
     * <p>策略是「超限即报错」而不是静默截断：用户勾了 25 个关键词却被悄悄砍到 20 个，
     * 他会以为前 20 个生效了、后 5 个也生效了，实际效果与预期不符且无从察觉。</p>
     */
    private static List<String> sanitize(List<String> values, int max, String what) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        var cleaned = new LinkedHashSet<String>();
        for (var value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            var trimmed = value.trim();
            if (trimmed.length() > MAX_ITEM_LENGTH) {
                throw new WxAuthException(what + "「" + trimmed.substring(0, 10) + "…」过长，"
                    + "请控制在 " + MAX_ITEM_LENGTH + " 个字符以内");
            }
            cleaned.add(trimmed);
        }
        if (cleaned.size() > max) {
            throw new WxAuthException(what + "最多选 " + max + " 项，当前 " + cleaned.size() + " 项");
        }
        return List.copyOf(cleaned);
    }

    private Mono<DigestSubscription> findOne(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(DigestSubscription.class, listOptions, null).next();
    }

    private static DigestSubscription newSubscription(String openid, boolean enabled,
        List<String> categories, List<String> keywords, List<String> sources,
        List<String> postCategories) {
        var subscription = new DigestSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(enabled);
        subscription.setCategories(categories);
        subscription.setKeywords(keywords);
        subscription.setSources(sources);
        subscription.setPostCategories(postCategories);
        var now = Instant.now();
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        var metadata = new Metadata();
        // 资源名由 openid 派生：同一用户重复提交天然幂等
        metadata.setName("ds-" + sha256Hex(openid));
        metadata.setCreationTimestamp(now);
        subscription.setMetadata(metadata);
        return subscription;
    }

    private static DigestSubscriptionDTO toDto(DigestSubscription subscription) {
        // 旧订阅只存了 AI 分类（categories）。自动打标上线后，AI 分类 key 与自动创建出来的
        // 站点分类名是同一串（都来自 DigextAiHelper 的固定枚举），所以直接把老值当作
        // postCategories 用即可，无需数据迁移脚本——用户不会发现自己的订阅"失效"了。
        var postCategories = orEmpty(subscription.getPostCategories());
        if (postCategories.isEmpty()) {
            postCategories = orEmpty(subscription.getCategories());
        }
        var dto = DigestSubscriptionDTO.builder()
            .enabled(Boolean.TRUE.equals(subscription.getEnabled()))
            .categories(orEmpty(subscription.getCategories()))
            .keywords(orEmpty(subscription.getKeywords()))
            .sources(orEmpty(subscription.getSources()))
            .postCategories(postCategories)
            .updatedAt(subscription.getUpdatedAt() == null ? null
                : subscription.getUpdatedAt().atZone(ZONE).format(TIME_FMT))
            .build();
        dto.setFiltered(DigestPreferenceMatcher.hasPreferences(dto));
        return dto;
    }

    private static DigestSubscriptionDTO empty() {
        return DigestSubscriptionDTO.builder()
            .enabled(false)
            .categories(List.of())
            .keywords(List.of())
            .sources(List.of())
            .postCategories(List.of())
            .filtered(false)
            .build();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * 资源名规则（与 {@link #newSubscription} 必须一致）。
     *
     * <p>注意不做大小写归一：微信 openid 区分大小写，归一后不同用户可能落到同一条记录上。</p>
     */
    static String resourceName(String openid) {
        return "ds-" + sha256Hex(openid.trim());
    }
}
