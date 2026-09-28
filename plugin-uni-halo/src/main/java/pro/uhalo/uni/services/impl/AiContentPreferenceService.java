package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.AiContentPreferenceRequest;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.AiContentPreference;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
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
 * 付费「AI 定制内容」的用户偏好：读取、保存与校验。
 *
 * <p><b>这是匿名可写的接口</b>（与订阅一样），因此校验不是礼貌性的：数量与长度都必须硬限制，
 * 否则任何人都能提交一份巨大的数组把配置存储撑坏。上限全部来自站长配置
 * （{@link AiContentConfigDTO}），而不是硬编码常量——站长改完上限，接口与前端同时生效。</p>
 *
 * <p>策略与订阅一致：<b>超限即报错，不静默截断</b>。用户勾了 8 个分类却被悄悄砍到 5 个，
 * 他会以为全都生效了。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentPreferenceService {

    /** 单个偏好项的长度上限（关键词可能有长词，但 40 字足够） */
    private static final int MAX_ITEM_LENGTH = 40;

    /** Halo 重建索引期间会拒绝写入；短暂重试让「插件刚升级完」时也能存上（与订阅同一套） */
    private static final int INDEX_BUILD_RETRIES = 3;
    private static final Duration INDEX_BUILD_BACKOFF = Duration.ofMillis(400);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveExtensionClient client;

    /** 读取该用户的偏好；没有记录时返回「未启用且无偏好」 */
    public Mono<AiContentPreferenceDTO> load(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(empty());
        }
        return findOne(openid.trim())
            .map(AiContentPreferenceService::toDto)
            .defaultIfEmpty(empty());
    }

    /**
     * 保存偏好（全量替换）。
     *
     * <p>记录名由 openid 派生，因此同一用户永远只有一条：首次写入是 create，之后是 update。</p>
     *
     * @param catalog 站长的分类目录：用于拒绝清单外的分类 key
     */
    public Mono<AiContentPreferenceDTO> save(String openid, AiContentPreferenceRequest request,
        DigestCategoryCatalog catalog, AiContentConfigDTO config) {
        // 用 defer 包住：校验失败必须以 error 信号返回，而不是同步抛异常
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
            }
            var normalizedOpenid = openid.trim();
            var enabled = request == null || request.getEnabled() == null
                || Boolean.TRUE.equals(request.getEnabled());
            var categories = sanitizeCategories(request == null ? null : request.getCategories(),
                catalog, config.getMaxCategories());
            var sources = sanitize(request == null ? null : request.getSources(),
                config.getMaxSources(), "关注的来源");
            var keywords = sanitize(request == null ? null : request.getKeywords(),
                config.getMaxKeywords(), "关注的关键词");

            return findOne(normalizedOpenid)
                .flatMap(existing -> {
                    existing.setEnabled(enabled);
                    existing.setCategories(categories);
                    existing.setSources(sources);
                    existing.setKeywords(keywords);
                    existing.setUpdatedAt(Instant.now());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(
                    newPreference(normalizedOpenid, enabled, categories, sources, keywords))))
                // 写入前已先读一次（findOne 不受索引构建影响），所以重试只会重跑写
                .retryWhen(Retry.backoff(INDEX_BUILD_RETRIES, INDEX_BUILD_BACKOFF)
                    .filter(AiContentPreferenceService::isIndexBuildingError)
                    .doBeforeRetry(signal -> log.warn(
                        "[UniHalo][AiContent] Halo 正在构建索引，偏好保存稍后重试（第 {} 次）",
                        signal.totalRetries() + 1)))
                .map(AiContentPreferenceService::toDto);
        });
    }

    /** 记录最近一次生成简报的日期（生成侧写回，失败不影响简报本身） */
    public Mono<Void> touchIssueDate(String openid, String date) {
        if (!StringUtils.hasText(openid) || !StringUtils.hasText(date)) {
            return Mono.empty();
        }
        return findOne(openid.trim())
            .flatMap(existing -> {
                if (date.equals(existing.getLastIssueDate())) {
                    return Mono.just(existing);
                }
                existing.setLastIssueDate(date);
                return client.update(existing);
            })
            .then()
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 记录简报生成日期失败（不影响简报）：{}", ex.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 分类 key 必须来自站长的分类目录。
     *
     * <p>清单外的值不是「多勾了一个」，而是「这个分类在日报里根本不存在」——
     * 放进去只会让用户以为配好了、简报却是空的。</p>
     */
    private static List<String> sanitizeCategories(List<String> values, DigestCategoryCatalog catalog,
        int max) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var cleaned = sanitize(values, max, "关注的主题");
        var unknown = cleaned.stream().filter(key -> !directory.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new WxAuthException("主题「" + String.join("、", unknown)
                + "」不在站长配置的分类清单里，请从列表里选择");
        }
        return cleaned;
    }

    /** 清洗并校验偏好项：去空、去重、限长、限数（超限报错而不是截断） */
    private static List<String> sanitize(List<String> values, int max, String what) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (max <= 0) {
            throw new WxAuthException(what + "当前不可配置（站长已关闭该维度）");
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

    private Mono<AiContentPreference> findOne(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(AiContentPreference.class, listOptions, null).next();
    }

    private static AiContentPreference newPreference(String openid, boolean enabled,
        List<String> categories, List<String> sources, List<String> keywords) {
        var preference = new AiContentPreference();
        preference.setWxOpenid(openid);
        preference.setEnabled(enabled);
        preference.setCategories(categories);
        preference.setSources(sources);
        preference.setKeywords(keywords);
        var now = Instant.now();
        preference.setCreatedAt(now);
        preference.setUpdatedAt(now);
        var metadata = new Metadata();
        metadata.setName(resourceName(openid));
        metadata.setCreationTimestamp(now);
        preference.setMetadata(metadata);
        return preference;
    }

    private static AiContentPreferenceDTO toDto(AiContentPreference preference) {
        var dto = AiContentPreferenceDTO.builder()
            .enabled(Boolean.TRUE.equals(preference.getEnabled()))
            .categories(orEmpty(preference.getCategories()))
            .sources(orEmpty(preference.getSources()))
            .keywords(orEmpty(preference.getKeywords()))
            .lastIssueDate(preference.getLastIssueDate())
            .updatedAt(preference.getUpdatedAt() == null ? null
                : preference.getUpdatedAt().atZone(ZONE).format(TIME_FMT))
            .build();
        dto.setConfigured(!dto.getCategories().isEmpty() || !dto.getSources().isEmpty()
            || !dto.getKeywords().isEmpty());
        return dto;
    }

    private static AiContentPreferenceDTO empty() {
        return AiContentPreferenceDTO.builder()
            .enabled(false)
            .categories(List.of())
            .sources(List.of())
            .keywords(List.of())
            .configured(false)
            .build();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    /** 是否是「Halo 正在建索引，暂时不让写」这类错误（与订阅共用同一判定） */
    public static boolean isIndexBuildingError(Throwable error) {
        var message = error == null ? null : error.getMessage();
        return message != null && message.contains("Index is building");
    }

    /** 资源名规则（与 {@link #newPreference} 必须一致）；不做大小写归一，openid 区分大小写 */
    static String resourceName(String openid) {
        return "acp-" + sha256Hex(openid.trim());
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
