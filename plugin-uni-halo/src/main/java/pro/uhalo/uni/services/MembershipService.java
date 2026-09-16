package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.MemberConfigDTO;
import pro.uhalo.uni.dto.MembershipStatusDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.utils.MemberConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员（单次购买的有效期权益）：配置读取与状态查询。
 *
 * <p>发货（延长到期时间）在 {@code VirtualPayServiceImpl.grantMembership} 里，
 * 因为它必须与订单的幂等流水绑在一起；本服务只负责「读」——
 * 内容拦截链路与会员状态接口共用同一套判定，避免出现
 * 「页面显示还是会员、文章却已经被锁」这种自相矛盾的状态。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipService {

    private final PointsPersistence pointsPersistence;
    private final ReactiveSettingFetcher settingFetcher;

    /** 读取会员配置；读取失败按关闭处理（宁可少给权益，不可误拦用户）。 */
    public Mono<MemberConfigDTO> loadConfig() {
        return settingFetcher.get(MemberConfigHelper.groupName())
            .map(MemberConfigHelper::parse)
            .defaultIfEmpty(MemberConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("读取会员配置失败（按关闭处理）：{}", ex.getMessage());
                return Mono.just(MemberConfigHelper.defaults());
            });
    }

    /**
     * 当前会员状态。
     *
     * <p>未登录返回「未开通」而不是报错：会员状态是展示项，
     * 未登录用户看到「未开通会员」比看到一个错误页合理得多。</p>
     */
    public Mono<MembershipStatusDTO> getStatus(String openid) {
        return loadConfig().flatMap(config -> {
            if (!Boolean.TRUE.equals(config.isEnabled())) {
                return Mono.just(disabledStatus(config));
            }
            if (!StringUtils.hasText(openid)) {
                return Mono.just(statusOf(config, null));
            }
            return pointsPersistence.getOrCreateAccount(openid.trim())
                .map(account -> statusOf(config, account))
                .onErrorResume(ex -> {
                    log.warn("读取会员状态失败，按未开通展示: openid={}, {}", openid, ex.getMessage());
                    return Mono.just(statusOf(config, null));
                });
        });
    }

    /**
     * 读取账户的会员到期时间；未登录或读不到返回 null。
     *
     * <p>内容拦截链路用它做免券短路：会员期内直接放行，不查解锁记录、不查券余额，
     * 少两次数据库往返。</p>
     */
    public Mono<Instant> loadMemberExpireAt(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }
        return pointsPersistence.getOrCreateAccount(openid.trim())
            .map(UserPoints::getMemberExpireAt)
            .flatMap(expireAt -> expireAt == null ? Mono.empty() : Mono.just(expireAt))
            .onErrorResume(ex -> {
                log.warn("读取会员到期时间失败（按非会员处理）: openid={}, {}",
                    openid, ex.getMessage());
                return Mono.empty();
            });
    }

    private static MembershipStatusDTO disabledStatus(MemberConfigDTO config) {
        return MembershipStatusDTO.builder()
            .enabled(false)
            .active(false)
            .benefits(List.of())
            .build();
    }

    private static MembershipStatusDTO statusOf(MemberConfigDTO config, UserPoints account) {
        var expireAt = account == null ? null : account.getMemberExpireAt();
        var active = MemberConfigHelper.isActive(expireAt);
        return MembershipStatusDTO.builder()
            .enabled(true)
            .active(active)
            .freeContent(config.isFreeContent())
            .expireAt(MemberConfigHelper.formatExpireAt(expireAt))
            .expireAtEpoch(expireAt == null ? 0L : expireAt.getEpochSecond())
            .daysRemaining(MemberConfigHelper.daysRemaining(expireAt))
            .bonusTickets(config.getBonusTickets())
            .benefits(benefitsOf(config))
            .build();
    }

    /**
     * 权益文案：由配置拼出来，避免前端按字段猜。
     *
     * <p>只列真正生效的权益——把没开启的权益也写上去，用户开通后会认为被骗。</p>
     */
    private static List<String> benefitsOf(MemberConfigDTO config) {
        var benefits = new ArrayList<String>();
        if (config.isFreeContent()) {
            benefits.add("会员期内全站付费文章免券阅读");
        }
        if (config.getBonusTickets() > 0) {
            benefits.add("开通即赠 " + config.getBonusTickets() + " 张阅读券");
        }
        return List.copyOf(benefits);
    }
}
