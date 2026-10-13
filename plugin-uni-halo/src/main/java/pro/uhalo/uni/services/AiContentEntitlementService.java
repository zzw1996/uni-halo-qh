package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentRedeemResultDTO;
import pro.uhalo.uni.dto.AiContentStatusDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.impl.AiContentGenerationLogService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AI 定制内容的权益（单次购买的有效期权益）：配置读取、状态查询与积分解锁。
 *
 * <p>权益来源有三条，判定收口在 {@link #isActive}：① 购买「AI 定制内容」道具
 * （现金直购，延长 {@code aiContentExpireAt}）；② 积分兑换（同一到期字段上叠加天数）；
 * ③ 会员有效期内免条件使用（站长可用 {@code memberFree} 关掉）。</p>
 *
 * <p>发货（延长到期时间）的现金通道在 {@code VirtualPayServiceImpl.grantAiContent} 里，
 * 因为它必须与订单的幂等流水绑在一起；本服务负责「读」与「积分通道」——
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

    private static final String REDEEM_ACTION = "ai_content_redeem";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EXPIRE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PointsPersistence pointsPersistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final MembershipService membershipService;
    private final RateLimiter rateLimiter;
    /** 特权判定：站点所有者/管理员豁免付费门槛（便于上架前自测，见 PrivilegeService） */
    private final PrivilegeService privilegeService;
    /** 生成记录：既做审计，也是「今天已生成几次」的计数依据 */
    private final AiContentGenerationLogService logService;

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
            var normalized = openid.trim();
            // 站点所有者/管理员：直接按「已开通」展示。他们本来就有全部权限，
            // 上架前自测不该先给自己买一份。
            return privilegeService.isPrivileged(normalized)
                .flatMap(privileged -> privileged
                    ? Mono.just(privilegedStatus())
                    : loadStatusOf(normalized, config)
                        .flatMap(status -> withGenerationQuota(status, normalized, config)));
        });
    }

    /** 常规用户的权益状态：道具有效期 → 会员兜底（memberFree 关掉时只看道具） */
    private Mono<AiContentStatusDTO> loadStatusOf(String openid, AiContentConfigDTO config) {
        return pointsPersistence.getOrCreateAccount(openid)
            .map(account -> statusOf(account.getAiContentExpireAt()))
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取权益状态失败，按未开通展示: openid={}, {}",
                    maskOpenid(openid), ex.getMessage());
                return Mono.just(statusOf(null));
            })
            .flatMap(status -> {
                if (status.isActive() || !config.isMemberFree()) {
                    return Mono.just(status);
                }
                return memberActive(openid).map(member -> {
                    if (member) {
                        status.setActive(true);
                        status.setViaMember(true);
                    }
                    return status;
                });
            });
    }

    /** 特权用户的状态：已开通、无到期时间、生成次数不限 */
    private AiContentStatusDTO privilegedStatus() {
        var status = statusOf(null);
        status.setActive(true);
        status.setViaPrivilege(true);
        status.setRemainingGenerations(-1);
        return status;
    }

    /**
     * 给权益带上「还能生成几次」与「今天已生成几次」。
     *
     * <p>用户端据此显示剩余次数与「今日还可生成 N 次」，并在用完时给出「再买/再兑换」的引导；
     * 后台用量页也读同一份口径。</p>
     */
    private Mono<AiContentStatusDTO> withGenerationQuota(AiContentStatusDTO status, String openid,
        AiContentConfigDTO config) {
        if (!status.isActive() || status.isViaPrivilege()) {
            return Mono.just(status);
        }
        return Mono.zip(loadCredits(openid), logService.countToday(openid))
            .map(tuple -> {
                status.setRemainingGenerations(tuple.getT1());
                status.setTodayGenerations(tuple.getT2());
                status.setDailyGenerationLimit(config.getDailyGenerationLimit());
                status.setCreditsPerDay(config.getCreditsPerDay());
                return status;
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取生成额度失败（按不限次展示）：{}", ex.getMessage());
                return Mono.just(status);
            });
    }
    /** 剩余生成次数（null/无记录视为 0） */
    public Mono<Integer> loadCredits(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(0);
        }
        return pointsPersistence.getOrCreateAccount(openid.trim())
            .map(account -> creditsOf(account))
            .defaultIfEmpty(0)
            .onErrorResume(ex -> Mono.just(0));
    }

    /**
     * 扣一次生成额度（先读后写，同一用户不会并发点两次——前端有 generating 闸门，
     * 服务端还有每日上限兜底）。返回扣减后的剩余次数。
     */
    public Mono<Integer> consumeCredit(String openid) {
        var normalized = StringUtils.hasText(openid) ? openid.trim() : "";
        if (normalized.isEmpty()) {
            return Mono.just(0);
        }
        return pointsPersistence.getOrCreateAccount(normalized).flatMap(account -> {
            var left = Math.max(0, creditsOf(account) - 1);
            account.setAiContentCredits(left);
            return pointsPersistence.saveAccount(account).map(saved -> left);
        });
    }

    /**
     * 发放生成次数（购买 / 积分兑换时调用）：在现有次数上累加，不覆盖。
     *
     * <p>不覆盖是关键——用户续费、兑换、再购买会多次进来，覆盖等于把之前没用的次数吞掉。</p>
     */
    public Mono<Integer> grantCredits(String openid, int days, AiContentConfigDTO config) {
        var add = AiContentConfigHelper.creditsForDays(days, config);
        if (!StringUtils.hasText(openid) || add <= 0) {
            return Mono.just(0);
        }
        var normalized = openid.trim();
        return pointsPersistence.getOrCreateAccount(normalized).flatMap(account -> {
            var total = creditsOf(account) + add;
            account.setAiContentCredits(total);
            return pointsPersistence.saveAccount(account)
                .doOnNext(saved -> log.info("[UniHalo][AiContent] 生成次数已发放：openid={} "
                        + "天数={} 本次 +{}，累计剩余 {}",
                    maskOpenid(normalized), days > 0 ? days : "永久", add, total))
                .map(saved -> total);
        });
    }

    /** 账户上的剩余次数（null 视为 0） */
    public static int creditsOf(UserPoints account) {
        if (account == null || account.getAiContentCredits() == null) {
            return 0;
        }
        return Math.max(0, account.getAiContentCredits());
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
     * 当前积分余额；未登录返回空（调用方按「不显示余额」处理）。
     *
     * <p>只读账户余额、不建账户：这是展示用的一次查询，不该因为用户点开页面就给他建一条积分账户。</p>
     */
    public Mono<Long> loadPointsBalance(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.empty();
        }
        return pointsPersistence.getOrCreateAccount(openid.trim())
            .map(account -> account.getBalance() == null ? 0L : account.getBalance())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取积分余额失败（界面不显示余额）: openid={}, {}",
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
     *
     * <p>会员有效期内免条件使用（对齐会员免券口径）；memberFree 关掉后只看已购道具。</p>
     */
    public Mono<Boolean> isActive(String openid) {
        return loadConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    return Mono.just(false);
                }
                if (!StringUtils.hasText(openid)) {
                    return Mono.just(false);
                }
                var normalized = openid.trim();
                // 站点所有者/管理员：一律视为已开通（与 getStatus 同一口径）
                return privilegeService.isPrivileged(normalized)
                    .flatMap(privileged -> privileged ? Mono.just(true)
                        : loadExpireAt(normalized).map(AiContentConfigHelper::isActive))
                    .defaultIfEmpty(false)
                    .flatMap(active -> {
                        if (active || !config.isMemberFree()) {
                            return Mono.just(active);
                        }
                        return memberActive(normalized);
                    });
            })
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 权益判定失败，按未开通处理：{}", ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<Boolean> memberActive(String openid) {
        return membershipService.loadMemberExpireAt(openid)
            .map(MemberConfigHelper::isActive)
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 会员有效期判定失败，按非会员处理: openid={}, {}",
                    maskOpenid(openid), ex.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * 积分兑换权益：扣积分 → 在现有到期时间上叠加 {@code pointsDays} 天。
     *
     * <p>与现金道具共用同一个 {@code aiContentExpireAt} 字段：两条获取通道最终是
     * 同一种权益，前端只需要一个「到期时间」。流水动作码 {@code ai_content_redeem}
     * 与道具直购（{@code purchase_ai_content}）分开，站长能在积分明细里分清钱付的哪条通道。</p>
     *
     * <p>允许多次兑换（每次都延长），不做「每天限兑一次」——用户主动点的按钮，
     * 每次都有明确的确认弹窗，限次只会制造「为什么点不了」的困惑。</p>
     */
    public Mono<AiContentRedeemResultDTO> redeemWithPoints(String openid) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("请先完成微信登录"));
            }
            var normalized = openid.trim();
            return loadConfig().flatMap(config -> {
                if (!config.isEnabled()) {
                    return Mono.error(new WxAuthException("「AI 定制内容」暂未开放"));
                }
                var price = config.getPointsPrice();
                var days = config.getPointsDays();
                if (price <= 0 || days <= 0) {
                    return Mono.error(new WxAuthException("积分兑换通道未开启"));
                }
                if (!rateLimiter.tryAcquire(normalized + ":ai-redeem", 5, Duration.ofMinutes(1))) {
                    return Mono.error(new WxAuthException("操作过于频繁，请稍后再试"));
                }
                return pointsPersistence.getOrCreateAccount(normalized).flatMap(account -> {
                    var balance = account.getBalance() == null ? 0L : account.getBalance();
                    if (balance < price) {
                        return Mono.error(new WxAuthException(
                            "积分不足：需要 " + price + " 积分，当前 " + balance
                                + " 积分，还差 " + (price - balance) + " 积分"));
                    }
                    var newBalance = balance - price;
                    // 流水键带毫秒时间戳：允许一天内多次兑换（每次都是一笔独立消费）
                    var earnKey = "ai-redeem:" + normalized + ":" + System.currentTimeMillis();
                    var record = pointsPersistence.newRecord(normalized, REDEEM_ACTION, -price,
                        newBalance, "积分解锁 AI 定制内容 " + days + " 天", "AI 定制内容",
                        earnKey, account.getWxUserId());
                    return pointsPersistence.appendRecord(record)
                        .then(pointsPersistence.getOrCreateAccount(normalized))
                        .flatMap(latest -> {
                            latest.setBalance(newBalance);
                            latest.setAiContentExpireAt(
                                AiContentConfigHelper.extend(latest.getAiContentExpireAt(), days));
                            // 兑换同时发放生成次数：与现金道具同一条口径（天数 × 每天次数）
                            latest.setAiContentCredits(creditsOf(latest)
                                + AiContentConfigHelper.creditsForDays(days, config));
                            return pointsPersistence.saveAccount(latest)
                                .map(saved -> AiContentRedeemResultDTO.builder()
                                    .balance(newBalance)
                                    .days(days)
                                    .creditsAdded(AiContentConfigHelper.creditsForDays(days, config))
                                    .remainingGenerations(creditsOf(saved))
                                    .expireAt(formatExpireAt(saved.getAiContentExpireAt()))
                                    .daysRemaining(
                                        AiContentConfigHelper.daysRemaining(saved.getAiContentExpireAt()))
                                    .permanent(AiContentConfigHelper.isPermanent(
                                        saved.getAiContentExpireAt()))
                                    .message("已用 " + price + " 积分兑换 " + days + " 天，"
                                        + "获得 " + AiContentConfigHelper.creditsForDays(days, config)
                                        + " 次生成机会")
                                    .build());
                        });
                });
            });
        });
    }

    private static String formatExpireAt(Instant expireAt) {
        if (expireAt == null) {
            return null;
        }
        return AiContentConfigHelper.isPermanent(expireAt) ? "永久"
            : expireAt.atZone(ZONE).format(EXPIRE_FMT);
    }

    private static AiContentStatusDTO disabledStatus() {
        return AiContentStatusDTO.builder()
            .enabled(false)
            .active(false)
            .permanent(false)
            .daysRemaining(0)
            .viaMember(false)
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
            .viaMember(false)
            .build();
    }

    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (var b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
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
