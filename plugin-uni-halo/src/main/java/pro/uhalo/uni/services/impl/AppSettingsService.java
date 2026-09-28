package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AppSettingsViewDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.scheme.UserAppSettings;
import pro.uhalo.uni.utils.AppSettingsCatalog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 用户级「应用设置」的服务端副本：读取与按字段合并保存。
 *
 * <p>为什么需要它：底栏显示项、外观模式、首页布局这些设置此前只存在本机，
 * 而「丢设置」的路径并不冷门——换设备、清小程序缓存，以及
 * <b>每次版本更新时的清缓存</b>（前端 {@code utils/update.js}）都会回到默认。</p>
 *
 * <p><b>按字段合并，不整包覆盖</b>：请求只带本次改动的键，只覆盖这些键。
 * 两台设备各改各的（一台改底栏、一台改卡片样式）因此不会互相抹掉，
 * 也就不需要「谁的时间戳更新」这类冲突判定。</p>
 *
 * <p>写入键值必须过 {@link AppSettingsCatalog} 白名单；存的是服务端自己生成的 JSON，
 * 客户端原文永远不落库。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppSettingsService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** Halo 重建索引期间会拒绝写入；短暂重试让「插件刚升级完」时也能存上（与订阅、偏好同一套） */
    private static final int WRITE_RETRIES = 3;
    private static final Duration WRITE_BACKOFF = Duration.ofMillis(400);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactiveExtensionClient client;

    /** 读取该用户的服务端副本；没有记录时返回空视图（区分「没同步过」与「同步过但没改过」） */
    public Mono<AppSettingsViewDTO> load(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(AppSettingsViewDTO.empty());
        }
        return findOne(openid.trim())
            .map(saved -> toView(saved, List.of()))
            .defaultIfEmpty(AppSettingsViewDTO.empty());
    }

    /**
     * 按字段合并保存：只覆盖请求里出现的键，其余保留。
     *
     * @param incoming 扁平点号键 → 标量；不在白名单内的键会被丢弃并在响应里回报
     */
    public Mono<AppSettingsViewDTO> merge(String openid, Map<String, Object> incoming) {
        // 用 defer 包住：校验失败必须以 error 信号返回，而不是同步抛异常
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
            }
            if (incoming != null && incoming.size() > AppSettingsCatalog.MAX_REQUEST_KEYS) {
                return Mono.error(new WxAuthException("一次提交的设置项过多（最多 "
                    + AppSettingsCatalog.MAX_REQUEST_KEYS + " 项）"));
            }
            var normalizedOpenid = openid.trim();
            var sanitized = AppSettingsCatalog.sanitize(incoming);

            return findOne(normalizedOpenid)
                .flatMap(existing -> {
                    // 存量里可能留着白名单已收敛掉的旧键：以清洗后的结果作为合并基准，
                    // 顺手把存储清干净（否则它们会一直躺在那里被读回去）
                    var merged = AppSettingsCatalog.sanitize(parsePayload(existing.getPayload()))
                        .accepted();
                    merged.putAll(sanitized.accepted());
                    existing.setPayload(writePayload(merged));
                    existing.setRevision(nextRevision(existing.getRevision()));
                    existing.setUpdatedAt(Instant.now());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(
                    newRecord(normalizedOpenid, sanitized.accepted()))))
                // 先读后写，所以重试只会重跑「读 → 合并 → 写」，不会把并发期间的改动覆盖掉
                .retryWhen(Retry.backoff(WRITE_RETRIES, WRITE_BACKOFF)
                    .filter(AppSettingsService::isRetryableWriteError)
                    .doBeforeRetry(signal -> log.warn(
                        "[UniHalo][AppSettings] 写入被拒绝，稍后重试（第 {} 次）：{}",
                        signal.totalRetries() + 1, signal.failure().getMessage())))
                .map(saved -> toView(saved, sanitized.rejected()));
        });
    }

    private Mono<UserAppSettings> findOne(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(UserAppSettings.class, listOptions, null).next();
    }

    private static UserAppSettings newRecord(String openid, Map<String, Object> settings) {
        var record = new UserAppSettings();
        record.setWxOpenid(openid);
        record.setPayload(writePayload(settings));
        record.setRevision(1L);
        var now = Instant.now();
        record.setUpdatedAt(now);
        var metadata = new Metadata();
        metadata.setName(resourceName(openid));
        metadata.setCreationTimestamp(now);
        record.setMetadata(metadata);
        return record;
    }

    private static AppSettingsViewDTO toView(UserAppSettings record, List<String> rejected) {
        return new AppSettingsViewDTO(
            // 读的时候也过一遍白名单：存储是历史数据，不能保证它一定符合当前清单
            AppSettingsCatalog.sanitize(parsePayload(record.getPayload())).accepted(),
            record.getRevision() == null ? 0L : record.getRevision(),
            record.getUpdatedAt() == null ? null
                : record.getUpdatedAt().atZone(ZONE).format(TIME_FMT),
            true,
            rejected == null ? List.of() : List.copyOf(rejected));
    }

    /** 解析存储里的 JSON；坏数据（人工改过存储）按「没有设置」处理，不让它把接口打挂 */
    private static Map<String, Object> parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return Map.of();
        }
        try {
            var parsed = OBJECT_MAPPER.readValue(payload, MAP_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception ex) {
            log.warn("[UniHalo][AppSettings] 设置副本不是合法 JSON，按空处理：{}", ex.getMessage());
            return Map.of();
        }
    }

    private static String writePayload(Map<String, Object> settings) {
        try {
            // 拷贝一份，避免序列化时被并发修改
            return OBJECT_MAPPER.writeValueAsString(new LinkedHashMap<>(settings));
        } catch (Exception ex) {
            throw new IllegalStateException("应用设置序列化失败：" + ex.getMessage(), ex);
        }
    }

    private static long nextRevision(Long current) {
        return current == null || current < 0 ? 1L : current + 1;
    }

    /**
     * 是否值得重试。
     *
     * <p>两类：Halo 正在重建索引（插件刚升级完）、以及乐观锁冲突
     * （两台设备同时保存，Halo 的报错信息里带 {@code concurrently}）。
     * 后者在「多端同时改设置」时是真会发生的，重试时会重新读一遍再合并。</p>
     */
    private static boolean isRetryableWriteError(Throwable error) {
        var chain = error;
        while (chain != null) {
            var message = chain.getMessage();
            if (message != null) {
                var lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("index is building") || lower.contains("concurrently")) {
                    return true;
                }
            }
            chain = chain.getCause() == chain ? null : chain.getCause();
        }
        return false;
    }

    /** 是否是「Halo 正在建索引」类错误（接口层据此返回 503 与可读提示） */
    public static boolean isIndexBuildingError(Throwable error) {
        var message = error == null ? null : error.getMessage();
        return message != null && message.contains("Index is building");
    }

    /** 资源名规则（与 {@link #newRecord} 必须一致）；不做大小写归一，openid 区分大小写 */
    static String resourceName(String openid) {
        return "uas-" + sha256Hex(openid.trim());
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
