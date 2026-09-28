package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentStatusDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;

/**
 * AI 定制内容的权益（单次购买的有效期权益）：配置读取与状态查询。
 *
 * <p>发货（延长到期时间）在 {@code VirtualPayServiceImpl.grantAiContent} 里，
 * 因为它必须与订单的幂等流水绑在一起；本服务只负责「读」——
 * 接口鉴权、简报生成、偏好保存共用同一套判定，避免出现
 * 「页面显示还是有效的、简报却不给生成」这种自相矛盾的状态。</p>
 *
 * <p>与会员卡同一套口径（{@link AiContentConfigHelper#isActive}），
 * 包括「未登录返回未开通而不是报错」这条：权益状态是展示项。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentEntitlementService {

    private final PointsPersistence pointsPersistence;
    private final ReactiveSettingFetcher settingFetcher;

    /** 读取配置；读取失败按关闭处理（宁可少给权益，不可误拦用户） */
    public Mono<AiContentConfigDTO> loadConfig() {
        return settingFetcher.get(AiContentConfigHelper.groupName())
            .map(AiContentConfigHelper::parse)
            .defaultIfEmpty(AiContentConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取配置失败（按关闭处理）：{}", ex.getMessage());
                return Mono.just(AiContentConfigHelper.defaults());
            });
    }

    /**
     * 当前权益状态。
     *
     * <p>未登录返回「未开通」而不是报错：这是展示项，用户看到「未开通」比看到一个错误页合理得多。</p>
     */
    public Mono<AiContentStatusDTO> getStatus(String openid) {
        return loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return Mono.just(disabledStatus());
            }
            if (!StringUtils.hasText(openid)) {
                return Mono.just(statusOf(null));
            }
            return pointsPersistence.getOrCreateAccount(openid.trim())
                .map(account -> statusOf(account.getAiContentExpireAt()))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][AiContent] 读取权益状态失败，按未开通展示: openid={}, {}",
                        maskOpenid(openid), ex.getMessage());
                    return Mono.just(statusOf(null));
                });
        });
    }

    /**
     * 读取到期时间；未登录或读不到返回空。
     *
     * <p>写操作（保存偏好、生成简报）用它做短路：没有这条记录就不必再往下走。</p>
     */
    public Mono<Instant> loadExpireAt(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }
        return pointsPersistence.getOrCreateAccount(openid.trim())
            .map(account -> account.getAiContentExpireAt())
            .flatMap(expireAt -> expireAt == null ? Mono.empty() : Mono.just(expireAt))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取权益到期时间失败（按未开通处理）: openid={}, {}",
                    maskOpenid(openid), ex.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 是否当前有效（含永久）。
     *
     * <p><b>fail-closed</b>：读取配置或账本失败时一律按「无效」处理。
     * 这与内容拦截链路（fail-open 放行）刻意相反——那里放行的是一篇文章，
     * 这里放行的是「每天一次 AI 调用」的成本，误放的代价不对称。</p>
     */
    public Mono<Boolean> isActive(String openid) {
        return loadConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    return Mono.just(false);
                }
                return loadExpireAt(openid).map(AiContentConfigHelper::isActive)
                    .defaultIfEmpty(false);
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 权益判定失败，按未开通处理：{}", ex.getMessage());
                return Mono.just(false);
            });
    }

    private static AiContentStatusDTO disabledStatus() {
        return AiContentStatusDTO.builder()
            .enabled(false)
            .active(false)
            .permanent(false)
            .daysRemaining(0)
            .build();
    }

    private static AiContentStatusDTO statusOf(Instant expireAt) {
        var active = AiContentConfigHelper.isActive(expireAt);
        return AiContentStatusDTO.builder()
            .enabled(true)
            .active(active)
            .permanent(AiContentConfigHelper.isPermanent(expireAt))
            .expireAt(AiContentConfigHelper.formatExpireAt(expireAt))
            .expireAtEpoch(expireAt == null ? 0L : expireAt.getEpochSecond())
            .daysRemaining(AiContentConfigHelper.daysRemaining(expireAt))
            // 过期与从未购买要分开：前者该引导续费（配置还在），后者该引导开通
            .expired(expireAt != null && !active)
            .build();
    }

    /** openid 是用户标识，日志里只留前几位 */
    private static String maskOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            return "(未登录)";
        }
        var trimmed = openid.trim();
        return trimmed.length() <= 8 ? trimmed.charAt(0) + "***" : trimmed.substring(0, 6) + "***";
    }
}
