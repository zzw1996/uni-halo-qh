package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.utils.PushConfigHelper;
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
import java.util.HexFormat;
import java.util.List;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 订阅消息额度与开关的记账（一个用户一条记录，资源名由 openid 派生）。
 *
 * <p><b>额度只能自己记</b>：微信侧不提供「该用户还剩几条订阅额度」的查询。一次性订阅的规则是
 * 「一次授权对应一条下发机会」，授权结果只有小程序前端的 {@code wx.requestSubscribeMessage}
 * 返回值知道——所以流程必须是「前端授权成功 → 上报 +1 → 发送成功扣 1 / 被拒清零」。</p>
 *
 * <p>额度的所有变更都在这里，且都是「读-改-写」：这类数据不会并发竞争（同一个人不会同时
 * 有两次授权回调），因此不需要额外的锁；真正的幂等由发送侧的 {@code PushRecord} 保证。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushSubscriptionService {

    /** 单个模板的额度上限：防止前端被反复调用把额度刷到很大（按天最多各一条，20 条足够） */
    static final int MAX_QUOTA = 20;

    /**
     * Halo 重建索引期间会拒绝写入，短暂重试。
     *
     * <p>比订阅/偏好那两处给得更宽（5 次 × 1 秒）：{@code PushSubscription} 是随本次升级
     * <b>新增</b>的扩展类型，它的索引要在插件启动时现建；实例忙的时候建索引可能不止一秒，
     * 重试太短会表现为「用户点了同意、前端报 500」。</p>
     */
    private static final int INDEX_BUILD_RETRIES = 5;
    private static final Duration INDEX_BUILD_BACKOFF = Duration.ofSeconds(1);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveExtensionClient client;

    /** 读取该用户的推送设置；没有记录时返回一份默认（未启用、额度 0），不落库 */
    public Mono<PushSubscription> load(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(empty(""));
        }
        return findOne(openid.trim()).defaultIfEmpty(empty(openid.trim()));
    }

    /** 全部记录（调度侧遍历用） */
    public Mono<List<PushSubscription>> listAll() {
        return client.listAll(PushSubscription.class, new ListOptions(), null).collectList();
    }

    /** 用户总开关 */
    public Mono<PushSubscription> setEnabled(String openid, boolean enabled) {
        return mutate(openid, current -> {
            current.setEnabled(enabled);
            return current;
        });
    }

    /**
     * 授权成功后记账：把给定模板的额度各 +1（一次授权 = 一条下发机会）。
     *
     * <p>只累加不覆盖——用户多次授权就是在攒额度，这正是「准长期订阅」的做法。</p>
     */
    public Mono<PushSubscription> grant(String openid, List<String> kinds) {
        return mutate(openid, current -> {
            for (var kind : kinds) {
                if (PushConfigHelper.KIND_AI_DIGEST.equals(kind)) {
                    current.setAiDigestQuota(Math.min(MAX_QUOTA,
                        orZero(current.getAiDigestQuota()) + 1));
                } else if (PushConfigHelper.KIND_NEW_POST.equals(kind)) {
                    current.setNewPostQuota(Math.min(MAX_QUOTA,
                        orZero(current.getNewPostQuota()) + 1));
                }
            }
            return current;
        }).doOnNext(saved -> log.info("[UniHalo][Push] 额度已累加：openid={} kinds={} "
                + "（新文章 {} / 简报 {}）", mask(openid), kinds, saved.getNewPostQuota(),
            saved.getAiDigestQuota()));
    }

    /** 发送成功：扣减 1（不会扣成负数） */
    public Mono<PushSubscription> consume(String openid, String kind) {
        return mutate(openid, current -> {
            if (PushConfigHelper.KIND_AI_DIGEST.equals(kind)) {
                current.setAiDigestQuota(Math.max(0, orZero(current.getAiDigestQuota()) - 1));
            } else {
                current.setNewPostQuota(Math.max(0, orZero(current.getNewPostQuota()) - 1));
            }
            current.setLastPushAt(Instant.now());
            return current;
        });
    }

    /**
     * 微信返回 43101（用户拒收或没有额度）：把该模板额度清零。
     *
     * <p>不清零的话我们会一直以为还有额度、每次都去撞一次微信，日志里全是 43101。</p>
     */
    public Mono<PushSubscription> clearQuota(String openid, String kind) {
        return mutate(openid, current -> {
            if (PushConfigHelper.KIND_AI_DIGEST.equals(kind)) {
                current.setAiDigestQuota(0);
            } else {
                current.setNewPostQuota(0);
            }
            return current;
        }).doOnNext(saved -> log.info("[UniHalo][Push] 用户无可用额度，已清零：openid={} kind={}",
            mask(openid), kind));
    }

    /**
     * 额度是否可用（用户总开关打开 + 该模板额度 > 0）。
     *
     * <p>用于<b>自动推送</b>（定时简报、新文章提醒）：用户关掉了推送就不要再自动打扰他。</p>
     */
    public static boolean hasQuota(PushSubscription subscription, String kind) {
        if (subscription == null || Boolean.FALSE.equals(subscription.getEnabled())) {
            return false;
        }
        return quotaOf(subscription, kind) > 0;
    }

    /**
     * 该模板剩余额度（不看用户总开关）。
     *
     * <p>为什么要分开：总开关管的是<b>自动推送</b>，而用户在页面里点「立即推送」是一次明确的手动
     * 请求——只看有没有额度。早前手动推送也走 {@link #hasQuota}，于是没打开「每天定时推送」的用户
     * 会看到「下面明明写着还有 3 条额度，点推送却说额度不足」这种自相矛盾的提示。</p>
     */
    public static int quotaOf(PushSubscription subscription, String kind) {
        if (subscription == null) {
            return 0;
        }
        var quota = PushConfigHelper.KIND_AI_DIGEST.equals(kind)
            ? subscription.getAiDigestQuota() : subscription.getNewPostQuota();
        return orZero(quota);
    }

    private Mono<PushSubscription> mutate(String openid,
        java.util.function.UnaryOperator<PushSubscription> changer) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new IllegalStateException("缺少 openid"));
            }
            var normalized = openid.trim();
            // 分支依据必须是「有没有查到记录」，而不是 metadata.name 有没有值——
            // 新建的记录同样必须带 name（资源名由 openid 派生），按 name 判断会让首次写入
            // 也走 update，而那条记录根本不存在，Halo 直接报错（表现为用户点「同意」时 500）。
            return findOne(normalized)
                .flatMap(existing -> {
                    var changed = changer.apply(existing);
                    changed.setUpdatedAt(Instant.now());
                    return client.update(changed);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var created = changer.apply(newSubscription(normalized));
                    created.setUpdatedAt(Instant.now());
                    return client.create(created);
                }))
                .retryWhen(Retry.backoff(INDEX_BUILD_RETRIES, INDEX_BUILD_BACKOFF)
                    .filter(PushSubscriptionService::isIndexBuildingError)
                    .doBeforeRetry(signal -> log.warn(
                        "[UniHalo][Push] Halo 正在构建索引，稍后重试（第 {} 次）",
                        signal.totalRetries() + 1)));
        });
    }

    private Mono<PushSubscription> findOne(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(PushSubscription.class, listOptions, null).next();
    }

    private static PushSubscription newSubscription(String openid) {
        var subscription = new PushSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(true);
        subscription.setNewPostQuota(0);
        subscription.setAiDigestQuota(0);
        var now = Instant.now();
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        var metadata = new Metadata();
        metadata.setName(resourceName(openid));
        metadata.setCreationTimestamp(now);
        subscription.setMetadata(metadata);
        return subscription;
    }

    private static PushSubscription empty(String openid) {
        var subscription = new PushSubscription();
        subscription.setWxOpenid(openid);
        subscription.setEnabled(true);
        subscription.setNewPostQuota(0);
        subscription.setAiDigestQuota(0);
        return subscription;
    }

    /** 是否是「Halo 正在建索引，暂时不让写」这类错误（接口层据此回 503 可操作提示） */
    public static boolean isIndexBuildingError(Throwable error) {
        var message = error == null ? null : error.getMessage();
        return message != null && message.contains("Index is building");
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 一览（接口与后台展示用）：把额度格式化成「新文章 1 / 简报 0」 */
    public static String describeQuota(PushSubscription subscription) {
        return "新文章 " + orZero(subscription.getNewPostQuota()) + " / 简报 "
            + orZero(subscription.getAiDigestQuota());
    }

    public static String formatTime(Instant instant) {
        return instant == null ? "" : instant.atZone(ZONE).format(TIME_FMT);
    }

    /** 资源名规则（与 {@link #newSubscription} 必须一致）；openid 区分大小写，不做归一 */
    static String resourceName(String openid) {
        return "ps-" + sha256Hex(openid.trim());
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未知)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }
}
