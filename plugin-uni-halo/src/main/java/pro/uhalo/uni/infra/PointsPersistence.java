package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.WxUserIds;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static run.halo.app.extension.index.query.QueryFactory.equal;
import static run.halo.app.extension.index.query.QueryFactory.in;

/**
 * 积分账户与流水持久化：以微信 openid（Wechat-Session-Id）为唯一业务键，
 * 数据写入 Halo Extension 存储（底层数据库），清小程序缓存后重新登录可恢复。
 */
@Component
@RequiredArgsConstructor
public class PointsPersistence {

    private final ReactiveExtensionClient client;
    private final WxUserService wxUserService;

    public Mono<UserPoints> getOrCreateAccount(String openid) {
        return findAccountByOpenid(openid)
            .flatMap(this::linkWxUserIfPresent)
            .switchIfEmpty(Mono.defer(() -> createAccount(openid)
                .flatMap(this::linkWxUserIfPresent)
                .onErrorResume(ex -> findAccountByOpenid(openid)
                    .flatMap(this::linkWxUserIfPresent))));
    }

    public Mono<UserPoints> findAccountByOpenid(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(UserPoints.class, listOptions, null).next();
    }

    /**
     * 按 openid 集合批量查询账户（用于装扮全站可见时的评论/瞬间列表注入）。
     * 返回 Map<openid, UserPoints>，缺失的 openid 不包含。
     */
    public Mono<java.util.Map<String, UserPoints>> findAccountsByOpenidsIn(java.util.Set<String> openids) {
        if (openids == null || openids.isEmpty()) {
            return Mono.just(java.util.Map.of());
        }
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
            in("wxOpenid", openids.toArray(new String[0]))));
        return client.listAll(UserPoints.class, listOptions, null)
            .collectMap(UserPoints::getWxOpenid, account -> account);
    }

    public Mono<PointsRecord> findRecordByEarnKey(String earnKey) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("earnKey", earnKey)));
        return client.listAll(PointsRecord.class, listOptions, null).next();
    }

    public Mono<List<PointsRecord>> listRecordsByOpenid(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(PointsRecord.class, listOptions, null).collectList();
    }

    public Mono<List<PointsRecord>> listAllRecords() {
        return client.listAll(PointsRecord.class, new ListOptions(), null).collectList();
    }

    public Mono<List<UserPoints>> listAllAccounts() {
        return client.listAll(UserPoints.class, new ListOptions(), null).collectList();
    }

    public Mono<UserPoints> saveAccount(UserPoints account) {
        return client.update(account);
    }

    public Mono<PointsRecord> appendRecord(PointsRecord record) {
        return client.create(record);
    }

    public static String accountResourceName(String openid) {
        return "up-" + sha256Hex(openid);
    }

    public static PointsRecord newRecord(String openid, String action, int points, long balance,
        String description, String refName, String earnKey, String wxUserId) {
        var record = new PointsRecord();
        record.setWxOpenid(openid);
        record.setWxUserId(wxUserId);
        record.setAction(action);
        record.setPoints(points);
        record.setBalance(balance);
        record.setDescription(description);
        record.setRefName(refName);
        record.setEarnKey(earnKey);
        record.setCreateTime(Instant.now());
        var metadata = new Metadata();
        metadata.setName("pr-" + sha256Hex(earnKey));
        metadata.setCreationTimestamp(Instant.now());
        record.setMetadata(metadata);
        return record;
    }

    private Mono<UserPoints> createAccount(String openid) {
        return wxUserService.fetchByOpenid(openid)
            .map(WxUserIds::resolveId)
            .defaultIfEmpty("")
            .flatMap(wxUserId -> {
                var account = new UserPoints();
                account.setWxOpenid(openid);
                if (StringUtils.hasText(wxUserId)) {
                    account.setWxUserId(wxUserId);
                }
                account.setBalance(0L);
                account.setConsecutiveDays(0);
                var metadata = new Metadata();
                metadata.setName(accountResourceName(openid));
                metadata.setCreationTimestamp(Instant.now());
                account.setMetadata(metadata);
                return client.create(account);
            });
    }

    private Mono<UserPoints> linkWxUserIfPresent(UserPoints account) {
        if (!StringUtils.hasText(account.getWxOpenid())) {
            return Mono.just(account);
        }
        return wxUserService.fetchByOpenid(account.getWxOpenid())
            .flatMap(wxUser -> {
                var resolvedId = WxUserIds.resolveId(wxUser);
                if (!StringUtils.hasText(resolvedId)
                    || Objects.equals(resolvedId, account.getWxUserId())) {
                    return Mono.just(account);
                }
                account.setWxUserId(resolvedId);
                return client.update(account);
            })
            .defaultIfEmpty(account);
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
