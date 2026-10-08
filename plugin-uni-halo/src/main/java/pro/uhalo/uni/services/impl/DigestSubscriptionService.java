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

    /** 从邀请人继承的分类上限（自己订阅的额度与它无关，继承永远是「额外看到」） */
    private static final int MAX_INHERITED_CATEGORIES = 3;

    /**
     * 生效集合的绝对上限：额度可配，但总得有个天花板。
     *
     * <p>首页要按生效分类并发拉内容，一个人订光全站分类会把首页拖成几十路请求；
     * 正常配置下（免费 5 + 可买 10、会员 30）永远碰不到这个值。</p>
     */
    private static final int ABS_LIMIT = 100;

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

    /** 订阅上限（免费额度 + 已购额度 / 会员上限）的唯一来源 */
    private final SubscriptionQuotaService quotaService;

    /** 是否是「Halo 正在建索引，暂时不让写」这类错误 */
    public static boolean isIndexBuildingError(Throwable error) {
        var message = error == null ? null : error.getMessage();
        return message != null && message.contains("Index is building");
    }

    /** 读取该用户的订阅偏好；没有记录时返回「未订阅且无偏好」。 */
    public Mono<DigestSubscriptionDTO> load(String openid) {
        if (!StringUtils.hasText(openid)) {
            // 游客也要知道额度规则：首页要靠它决定「零订阅时展示什么」
            return attachLimits(empty(), null);
        }
        return quotaService.limitOf(openid.trim()).flatMap(maxOwn -> findOne(openid.trim())
            .map(subscription -> toDto(subscription, maxOwn))
            .defaultIfEmpty(empty())
            .flatMap(dto -> attachLimits(dto, openid.trim())));
    }

    /** 把额度视图挂到 DTO 上（上限、免费额度、已购、价格、被暂停的分类） */
    private Mono<DigestSubscriptionDTO> attachLimits(DigestSubscriptionDTO dto, String openid) {
        return reactor.core.publisher.Mono
            .defer(() -> quotaService.limitsOf(openid, dto.getPostCategories()))
            .map(limits -> {
                dto.setLimits(limits);
                return dto;
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取订阅额度失败，按无额度信息下发：{}", ex.getMessage());
                return Mono.just(dto);
            });
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
            return quotaService.limitOf(normalizedOpenid).flatMap(maxOwn -> {
            var postCategories = sanitize(request == null ? null : request.getPostCategories(),
                maxOwn, "关注的分类（首页）");

            return findOne(normalizedOpenid)
                .flatMap(existing -> {
                    existing.setEnabled(enabled);
                    existing.setCategories(categories);
                    existing.setKeywords(keywords);
                    existing.setSources(sources);
                    existing.setPostCategories(postCategories);
                    // 自己一个都不订了 = 明确表示「首页不要个性化」：连从邀请人继承来的推荐
                    // 也一并清掉。否则会出现自相矛盾的状态——发现页显示「还没订阅分类」，
                    // 首页却还在按（继承来的）分类筛选，用户会认为「取消订阅根本没生效」。
                    // 「只继承、没自己订」的新用户不受影响：那是另一条分支（own 空、inherited 有值），
                    // 首页照旧按推荐展示，并给出「这些内容来自邀请人推荐」的提示。
                    if (postCategories.isEmpty()) {
                        existing.setInheritedCategories(List.of());
                        existing.setInheritedFrom(null);
                    }
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
                .map(subscription -> toDto(subscription, maxOwn));
            });
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

    /**
     * 从邀请人继承订阅分类（登录结算邀请关系时调用）。
     *
     * <p><b>只对「还没有自己订阅、也没继承过」的人生效</b>：新用户天然满足，于是他的首页
     * 从第一眼起就是分享者订阅的那批内容；已经有自己订阅的老用户完全不动——他打开别人的
     * 分享只会看到一个可点的推荐入口，首页该是什么还是什么。</p>
     *
     * <p>这里允许为新用户<b>预建</b>订阅记录：新用户本就没有记录，若只在已有记录上做继承，
     * 这个功能对新人反而永远不生效。</p>
     *
     * @return 是否真的写入了继承项
     */
    public Mono<Boolean> applyInheritance(String openid, String inviterOpenid,
        List<String> categories) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid) || !StringUtils.hasText(inviterOpenid)) {
                return Mono.just(false);
            }
            var wanted = cap(categories, MAX_INHERITED_CATEGORIES);
            if (wanted.isEmpty()) {
                return Mono.just(false);
            }
            var normalized = openid.trim();
            var inviter = inviterOpenid.trim();
            return findOne(normalized)
                .flatMap(existing -> {
                    if (!ownCategoriesOf(existing).isEmpty()
                        || !orEmpty(existing.getInheritedCategories()).isEmpty()) {
                        // 已有自己的订阅 / 已经继承过：不覆盖、不叠加
                        log.info("[UniHalo][Digest] 该用户已有订阅或已继承过，跳过继承 openid={}",
                            mask(normalized));
                        return Mono.just(false);
                    }
                    existing.setInheritedCategories(wanted);
                    existing.setInheritedFrom(inviter);
                    existing.setEnabled(true);
                    existing.setUpdatedAt(Instant.now());
                    return client.update(existing).map(saved -> true);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(newInheritedSubscription(
                    normalized, inviter, wanted)).map(created -> true)))
                .doOnNext(applied -> {
                    if (applied) {
                        log.info("[UniHalo][Digest] 已按邀请人的订阅写入继承分类 openid={} 继承={}",
                            mask(normalized), wanted);
                    }
                })
                .onErrorResume(ex -> {
                    // 继承是锦上添花，绝不能因为它让登录失败
                    log.warn("[UniHalo][Digest] 写入继承分类失败（忽略）openid={}：{}",
                        mask(normalized), ex.getMessage());
                    return Mono.just(false);
                });
        });
    }

    /**
     * 老用户点「也关注这些」：把别人的推荐并进他自己的订阅。
     *
     * <p>并入口径与首页一致——先占自己的 5 个名额，剩下的进继承位（最多 3 个），
     * 已有的一律不重复加。所以「也关注这些」最多把他从 5 个变成 8 个，不会无限膨胀。</p>
     */
    public Mono<DigestSubscriptionDTO> acceptRecommendation(String openid, String inviterOpenid,
        List<String> categories) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("请先登录"));
            }
            var normalized = openid.trim();
            var inviter = inviterOpenid == null ? "" : inviterOpenid.trim();
            return quotaService.limitOf(normalized).flatMap(maxOwn -> {
            var wanted = cap(categories, maxOwn + MAX_INHERITED_CATEGORIES);
            return findOne(normalized)
                .flatMap(existing -> {
                    var own = new LinkedHashSet<>(ownCategoriesOf(existing));
                    var inherited = new LinkedHashSet<>(
                        orEmpty(existing.getInheritedCategories()));
                    for (var name : wanted) {
                        if (own.contains(name) || inherited.contains(name)) {
                            continue;
                        }
                        if (own.size() < maxOwn) {
                            own.add(name);
                        } else if (inherited.size() < MAX_INHERITED_CATEGORIES) {
                            inherited.add(name);
                        }
                    }
                    existing.setPostCategories(List.copyOf(own));
                    existing.setInheritedCategories(List.copyOf(inherited));
                    if (StringUtils.hasText(inviter)) {
                        existing.setInheritedFrom(inviter);
                    }
                    existing.setEnabled(true);
                    existing.setCategories(List.of());
                    existing.setUpdatedAt(Instant.now());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(newInheritedSubscription(
                    normalized, inviter, cap(wanted, MAX_INHERITED_CATEGORIES)))))
                .map(subscription -> toDto(subscription, maxOwn));
            });
        });
    }

    /** 预建一条「只有继承项」的订阅记录（新用户、尚无记录的老用户都走这里） */
    private static DigestSubscription newInheritedSubscription(String openid, String inviterOpenid,
        List<String> inherited) {
        var subscription = newSubscription(openid, true, List.of(), List.of(), List.of(),
            List.of());
        subscription.setInheritedCategories(inherited);
        subscription.setInheritedFrom(inviterOpenid);
        return subscription;
    }

    /** 去空、去重、截断到 limit（不抛异常：这里的数据都来自服务端自己算出的清单） */
    private static List<String> cap(List<String> values, int limit) {
        var cleaned = new LinkedHashSet<String>();
        for (var value : orEmpty(values)) {
            if (cleaned.size() >= limit) {
                break;
            }
            if (StringUtils.hasText(value)) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }

    /** 日志用的 openid 脱敏 */
    private static String mask(String openid) {
        return openid.length() <= 6 ? "***" : openid.substring(0, 6) + "***";
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

    private static DigestSubscriptionDTO toDto(DigestSubscription subscription, int maxOwn) {
        var postCategories = ownCategoriesOf(subscription);
        var inherited = orEmpty(subscription.getInheritedCategories());
        var dto = DigestSubscriptionDTO.builder()
            .enabled(Boolean.TRUE.equals(subscription.getEnabled()))
            .categories(orEmpty(subscription.getCategories()))
            .keywords(orEmpty(subscription.getKeywords()))
            .sources(orEmpty(subscription.getSources()))
            .postCategories(postCategories)
            .inheritedCategories(inherited)
            .effectiveCategories(effectiveOf(postCategories, inherited, maxOwn))
            .updatedAt(subscription.getUpdatedAt() == null ? null
                : subscription.getUpdatedAt().atZone(ZONE).format(TIME_FMT))
            .build();
        dto.setFiltered(DigestPreferenceMatcher.hasPreferences(dto));
        return dto;
    }

    /**
     * 用户**自己**订阅的站点分类。
     *
     * <p>旧订阅只存了 AI 分类（categories）。自动打标上线后 AI 分类 key 与自动创建出来的
     * 站点分类名是同一串，所以直接把老值当作 postCategories 用即可，无需迁移脚本——
     * 用户不会发现自己的订阅"失效"了。</p>
     */
    /**
     * 用户**自己**订阅的站点分类（含老数据回落）。
     *
     * <p>包内可见：推送调度要按「自己 + 继承」的同一套口径判断目标用户，
     * 在这里重写一遍规则迟早会与首页筛选不一致。</p>
     */
    static List<String> ownCategoriesOf(DigestSubscription subscription) {
        var own = orEmpty(subscription.getPostCategories());
        if (own.isEmpty()) {
            own = orEmpty(subscription.getCategories());
        }
        return own;
    }

    /**
     * 首页实际生效的分类：自己订阅的在前（最多 {@code maxOwn} 个），继承来的在后（最多 3 个）。
     *
     * <p>「自己优先」是刻意的：用户亲手选的分类不该被别人的推荐挤掉；名额有余量时
     * 才把继承项补进来，所以继承永远是「额外看到」，不是「替代」。</p>
     *
     * <p>{@code maxOwn} 就是用户的订阅额度（免费 + 已购，会员期内是会员上限）：
     * 超出额度的订阅<b>不算失效</b>，只是不进这个集合——记录与顺序都还在，
     * 续费会员或解锁额度后自动恢复。首页、消息推送共用这一个口径。</p>
     */
    static List<String> effectiveOf(List<String> own, List<String> inherited, int maxOwn) {
        var ownLimit = Math.min(Math.max(maxOwn, 1), ABS_LIMIT);
        var effectiveLimit = ownLimit + MAX_INHERITED_CATEGORIES;
        var merged = new LinkedHashSet<String>();
        for (var name : orEmpty(own)) {
            if (merged.size() >= ownLimit) {
                break;
            }
            if (StringUtils.hasText(name)) {
                merged.add(name.trim());
            }
        }
        // 继承项自身也限 3 个：写侧已经限过，这里再限一次是防御历史脏数据——
        // 否则「自己只订了 1 个」的用户会把继承项全部摊开，与「自己 5 + 继承 3」的口径不一致
        var inheritedUsed = 0;
        for (var name : orEmpty(inherited)) {
            if (inheritedUsed >= MAX_INHERITED_CATEGORIES
                || merged.size() >= effectiveLimit) {
                break;
            }
            if (StringUtils.hasText(name) && merged.add(name.trim())) {
                inheritedUsed++;
            }
        }
        return List.copyOf(merged);
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
