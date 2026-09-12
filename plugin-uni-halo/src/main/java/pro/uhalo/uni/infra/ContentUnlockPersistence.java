package pro.uhalo.uni.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.scheme.ContentUnlock;
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

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 付费文章解锁记录持久化。
 *
 * <p>幂等设计：{@code metadata.name = "cu-" + sha256(unlockKey)}，
 * 其中 {@code unlockKey = openid + ":" + articleName}。同一用户重复解锁同一篇文章时，
 * create 会因资源名冲突失败，调用方据此判定「已解锁过」而不重复扣券。</p>
 */
@Component
@RequiredArgsConstructor
public class ContentUnlockPersistence {

    private final ReactiveExtensionClient client;

    /** 按唯一键查询单条解锁记录 */
    public Mono<ContentUnlock> findByUnlockKey(String unlockKey) {
        if (!StringUtils.hasText(unlockKey)) {
            return Mono.empty();
        }
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("unlockKey", unlockKey)));
        return client.listAll(ContentUnlock.class, listOptions, null).next();
    }

    /** 某用户的全部解锁记录（「我的解锁」列表用） */
    public Mono<List<ContentUnlock>> listByOpenid(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(ContentUnlock.class, listOptions, null).collectList();
    }

    public Mono<ContentUnlock> create(ContentUnlock unlock) {
        return client.create(unlock);
    }

    /**
     * 删除解锁记录（补偿回滚用）。
     *
     * <p>解锁采用「先写幂等记录、后扣券」的顺序：若扣券失败（余额不足或版本冲突），
     * 必须把已写入的解锁记录删掉，否则用户会在没扣券的情况下白拿一篇付费文章。
     * 该失败模式对用户无害、对站长有收入损失，方向上是可接受的；
     * 反过来「先扣券后写记录」则可能让用户券被扣而文章没解锁。</p>
     */
    public Mono<ContentUnlock> delete(ContentUnlock unlock) {
        return client.delete(unlock);
    }

    /** 构造唯一键：openid + 文章名 */
    public static String buildUnlockKey(String openid, String articleName) {
        return openid.trim() + ":" + articleName.trim();
    }

    public static ContentUnlock newUnlock(String openid, String articleName, int costTickets,
        String unlockType, String wxUserId) {
        var key = buildUnlockKey(openid, articleName);
        var unlock = new ContentUnlock();
        unlock.setWxOpenid(openid.trim());
        unlock.setWxUserId(wxUserId);
        unlock.setArticleName(articleName.trim());
        unlock.setTicketsCost(costTickets);
        unlock.setUnlockType(unlockType);
        unlock.setUnlockTime(Instant.now());
        unlock.setUnlockKey(key);
        var metadata = new Metadata();
        // 资源名由唯一键派生，这是「一人一篇只扣一次券」的根本保证
        metadata.setName("cu-" + sha256Hex(key));
        metadata.setCreationTimestamp(Instant.now());
        unlock.setMetadata(metadata);
        return unlock;
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
