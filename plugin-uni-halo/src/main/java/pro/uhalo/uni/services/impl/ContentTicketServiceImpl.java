package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.ContentLockedDTO;
import pro.uhalo.uni.dto.ContentTicketConfigDTO;
import pro.uhalo.uni.dto.UnlockedArticleItemDTO;
import pro.uhalo.uni.dto.UnlockedArticleListResponse;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.ContentUnlockPersistence;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.ContentUnlock;
import pro.uhalo.uni.services.ContentTicketService;
import pro.uhalo.uni.services.MembershipService;
import pro.uhalo.uni.services.PrivilegeService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.ContentTicketConfigHelper;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 付费内容（阅读券）服务实现。
 *
 * <p>判定链路：文章详情请求 → 查文章 → 文章的 tags 中是否含「付费标记标签」
 * → 含则查当前用户是否已解锁 → 未解锁则拦截。</p>
 *
 * <p>标签解析难点：{@code post.spec.tags} 存的是 Tag 的 metadata.name（形如
 * {@code c3ca8b6c-...}），而站长在后台配置的是 slug（如 {@code paid}）。
 * 两者需要反查映射，且该映射在拦截热路径上每次都要用，因此做 60 秒 TTL 缓存，
 * 避免每篇文章详情都全表扫描一遍 Tag。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentTicketServiceImpl implements ContentTicketService {

    /** 标签 name → 是否付费 的缓存有效期；标签改动低频，60 秒足够 */
    private static final Duration TAG_CACHE_TTL = Duration.ofSeconds(60);

    private static final String UNLOCK_TYPE_TICKET = "ticket";

    /**
     * 消耗阅读券解锁文章的流水动作码。
     *
     * <p>⚠️ 必须与「积分兑换阅读券」的动作码区分开：兑换走
     * {@code exchangeActionOf("content_ticket")} → {@code exchange_content_ticket}（用户<b>获得</b>券），
     * 解锁走本常量 {@code unlock_content}（用户<b>消耗</b>券）。
     * 若两者共用同一动作码，积分明细里「获得」与「消耗」会混成一类，
     * 站长无法对账，用户也看不懂自己的券去哪了。</p>
     */
    private static final String UNLOCK_ACTION = "unlock_content";

    /** 日报文章的注解标记（与 DigestPostWriter 保持一致） */
    private static final String DIGEST_MARKER_ANNO = "uni.uhalo.pro/digest-generated";

    /** 日报文章的 label 标记（新版发布时写入，旧文章只有注解） */
    private static final String DIGEST_MARKER_LABEL = "uni.uhalo.pro/digest";

    /** 「已解锁文章」最多返回条数：一次拉太多既无意义也会拖慢回查文章标题 */
    private static final int MAX_UNLOCKED_ITEMS = 200;

    /** 回查文章标题的并发上限：保序的前提下并行几个，避免串行 N 次数据库往返 */
    private static final int POST_LOOKUP_CONCURRENCY = 6;

    private static final ZoneId UNLOCK_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter UNLOCK_TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 道具直购阅读券的动作码。
     *
     * <p>与 {@code PointsServiceImpl.PURCHASE_DASHBOARD_ACTION} 做法一致，开放给
     * {@code VirtualPayServiceImpl} 引用，使「现金购买阅读券」与「积分兑换阅读券」
     * 在积分明细里能区分展示，不把购买误标成兑换。</p>
     */
    static final String PURCHASE_ACTION_CONTENT_TICKET = "purchase_content_ticket";
    private static final int MAX_ARTICLE_NAME_LENGTH = 150;

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final ContentUnlockPersistence unlockPersistence;
    private final PointsPersistence pointsPersistence;
    private final WxUserService wxUserService;
    private final RateLimiter rateLimiter;
    private final MembershipService membershipService;
    /** 特权判定：站点所有者/管理员免券读全站付费内容（见 PrivilegeService） */
    private final PrivilegeService privilegeService;

    /** 付费标签的 metadata.name 集合（由 slug 反查得到），带 TTL 缓存 */
    private final AtomicReference<TagCache> paidTagNamesCache = new AtomicReference<>();

    @Override
    public Mono<ContentLockedDTO> getStatus(String openid, String articleName) {
        return resolveLockIfRequired(openid, articleName)
            // 放行场景（非付费文章）也要给前端一个明确响应，便于统一处理
            .switchIfEmpty(Mono.defer(() -> loadConfig().flatMap(config ->
                fetchPost(articleName).flatMap(post ->
                    lockedDtoMono(config, post, false, true, openid)))))
            // ⚠️ 必须再兜一层：文章不存在或内部查询失败时，fetchPost 已被 onErrorResume
            // 吞成 empty，上面的 flatMap 不会执行，整条链仍为 empty。
            // 若不兜底，接口会返回空响应体，前端拿不到统一的状态结构。
            .defaultIfEmpty(readableFallback(articleName, "文章不存在或已下架，已按可阅读处理"))
            .onErrorResume(ex -> {
                log.warn("查询文章付费状态失败: articleName={}, {}: {}",
                    articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.just(readableFallback(articleName, "状态查询失败，已按可阅读处理"));
            });
    }

    /**
     * 构造「按可阅读处理」的兜底状态。
     *
     * <p>失败方向统一为不锁定：宁可少收一次费，也不阻断正常阅读，
     * 更不能因为状态查询失败就让详情页显示错误态。</p>
     */
    private static ContentLockedDTO readableFallback(String articleName, String message) {
        return ContentLockedDTO.builder()
            .articleName(articleName)
            .locked(false)
            .unlocked(true)
            .tickets(0)
            .message(message)
            .build();
    }

    @Override
    public Mono<ContentLockedDTO> unlock(String openid, String articleName) {
        validateArticleName(articleName);
        var normalizedOpenid = normalizeOpenid(openid);
        return loadConfig().flatMap(config -> {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                return Mono.error(new WxAuthException("付费内容功能未开启"));
            }
            ensureRateLimit(normalizedOpenid, "content_unlock", 10, "解锁过于频繁，请稍后再试");
            return fetchPost(articleName)
                .switchIfEmpty(Mono.error(new WxAuthException("文章不存在或已下架")))
                .flatMap(post -> isPaidPost(config, post)
                    .flatMap(paid -> {
                        if (!paid) {
                            // 免费文章无需解锁，直接返回已解锁，避免用户白扣券
                            return lockedDtoMono(config, post, false, true, normalizedOpenid);
                        }
                        return unlockPaidPost(config, post, normalizedOpenid);
                    }));
        });
    }

    @Override
    public Mono<ContentLockedDTO> resolveLockIfRequired(String openid, String articleName) {
        if (!StringUtils.hasText(articleName)) {
            return Mono.empty();
        }
        return loadConfig().flatMap(config -> {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                // 功能未开启：一律放行，绝不拦截
                return Mono.<ContentLockedDTO>empty();
            }
            return fetchPost(articleName)
                .flatMap(post -> isPaidPost(config, post)
                    .flatMap(paid -> {
                        if (!paid) {
                            return Mono.<ContentLockedDTO>empty();
                        }
                        var safeOpenid = normalizeOpenidQuietly(openid);
                        // 会员期内免券：直接放行，不再查解锁记录与券余额（少两次数据库往返）。
                        // 必须返回 empty——拦截器的契约是「非空即写 403」，
                        // 会员看到自己的付费墙就说明这里写错了。
                        return isMemberFree(safeOpenid)
                            .flatMap(memberFree -> {
                                if (memberFree) {
                                    return Mono.<ContentLockedDTO>empty();
                                }
                                return isOwnerOf(config, post, safeOpenid)
                            .flatMap(owner -> {
                                if (owner) {
                                    // 博主本人看自己的付费文章：放行，不拦截、不扣券。
                                    // ⚠️ 必须返回 empty，不能返回 DTO：拦截器的契约是
                                    // 「非空即写 403」，返回 DTO 会把博主挡在自己的付费墙外。
                                    return Mono.<ContentLockedDTO>empty();
                                }
                                if (!StringUtils.hasText(safeOpenid)) {
                                    // 未登录：拦截并提示登录，券数按 0 返回
                                    return Mono.just(lockedDto(config, post, true, false, 0));
                                }
                                return hasUnlocked(safeOpenid, articleName)
                                    .flatMap(unlocked -> {
                                        if (Boolean.TRUE.equals(unlocked)) {
                                            // 已解锁：放行，让 Halo 原生接口返回完整正文。
                                            // ⚠️ 返回 DTO 会被拦截器当成需拦截而写 403，
                                            // 用户花了券依然打不开文章，属资损级缺陷。
                                            return Mono.<ContentLockedDTO>empty();
                                        }
                                        return lockedDtoMono(config, post, true, false, safeOpenid);
                                    });
                                });
                            });
                    }))
                // 文章查不到（可能已删除）时放行，交给 Halo 原生接口返回 404，
                // 避免把 Halo 的正常错误语义替换成我们的 403
                .switchIfEmpty(Mono.<ContentLockedDTO>empty())
                .onErrorResume(ex -> {
                    log.warn("付费内容拦截判定失败，按放行处理: articleName={}, {}: {}",
                        articleName, ex.getClass().getSimpleName(), ex.getMessage());
                    // 判定异常时放行：拦截失败最坏是多看一篇，
                    // 而误拦截会让所有付费文章（乃至全站）打不开
                    return Mono.empty();
                });
        });
    }

    @Override
    public Mono<Integer> getTicketBalance(String openid) {
        return ticketBalanceMono(openid);
    }

    /**
     * 已解锁文章列表。
     *
     * <p>文章标题/封面单独回查（解锁记录只存 articleName，不冗余快照）：
     * 冗余会让站长改标题后列表里还是旧标题。回查按并发上限 6 顺序保序执行——
     * 解锁记录条数受 {@code limit} 约束，不会打爆数据库。</p>
     */
    @Override
    public Mono<UnlockedArticleListResponse> listUnlocked(String openid, int limit) {
        var normalizedOpenid = normalizeOpenidQuietly(openid);
        if (!StringUtils.hasText(normalizedOpenid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        var normalizedLimit = Math.min(Math.max(limit, 1), MAX_UNLOCKED_ITEMS);
        return unlockPersistence.listByOpenid(normalizedOpenid)
            .flatMap(records -> {
                var recent = records.stream()
                    // unlockTime 是新增字段，历史记录理论上都有；缺失时按最早处理而不丢弃
                    .sorted(Comparator.comparing(ContentUnlock::getUnlockTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(normalizedLimit)
                    .toList();
                return Flux.fromIterable(recent)
                    .flatMapSequential(record -> fetchPost(record.getArticleName())
                        .map(post -> toUnlockedItem(record, post))
                        .defaultIfEmpty(toUnlockedItem(record, null)),
                        POST_LOOKUP_CONCURRENCY)
                    .collectList()
                    .flatMap(items -> ticketBalanceMono(normalizedOpenid)
                        .map(tickets -> UnlockedArticleListResponse.builder()
                            .items(items)
                            .total(records.size())
                            .tickets(tickets)
                            .build()));
            });
    }

    /** post 为 null 表示文章已被删除或读取失败，此时只保留标题占位并标记不可访问。 */
    private static UnlockedArticleItemDTO toUnlockedItem(ContentUnlock record, Post post) {
        var spec = post == null ? null : post.getSpec();
        var title = spec == null ? null : spec.getTitle();
        return UnlockedArticleItemDTO.builder()
            .articleName(record.getArticleName())
            .title(StringUtils.hasText(title) ? title : "文章已下架")
            .slug(spec == null ? null : spec.getSlug())
            .cover(spec == null ? null : spec.getCover())
            .unlockTime(record.getUnlockTime() == null ? null
                : record.getUnlockTime().atZone(UNLOCK_TIME_ZONE).format(UNLOCK_TIME_FMT))
            .ticketsCost(record.getTicketsCost())
            .available(post != null)
            .build();
    }

    // ==================== 解锁核心 ====================

    /**
     * 用券解锁付费文章。
     *
     * <p>顺序要求（关键，顺序反了会造成资损）：
     * <b>先写解锁记录（幂等闸门），成功后再扣券</b>。
     * 解锁记录的 metadata.name 由 openid+articleName 派生，唯一；
     * 并发解锁同一篇文章时只有一个 create 能成功，另一个冲突失败即视为「已解锁」直接返回，
     * 不会扣第二次券。</p>
     *
     * <p>若扣券阶段失败（余额不足 / 版本冲突），必须<b>删除刚写入的解锁记录</b>做补偿回滚，
     * 否则用户会在没扣券的情况下白拿一篇付费文章。
     * 该失败方向是「站长少收钱」而非「用户丢券」，对用户无害，可接受。</p>
     */
    private Mono<ContentLockedDTO> unlockPaidPost(ContentTicketConfigDTO config, Post post,
        String openid) {
        var articleName = post.getMetadata().getName();
        var cost = resolveCost(config);
        return isOwnerOf(config, post, openid)
            .flatMap(owner -> {
                if (owner) {
                    // 博主豁免：记录一条 0 消耗的解锁，保证后续拦截不再询问
                    return createUnlockRecord(openid, articleName, 0, config, post, true);
                }
                // 会员期内免券：写一条 0 消耗的解锁记录，保证后续拦截不再询问，
                // 且会员点「解锁」时绝不消耗券（否则是实打实的资损）
                return isMemberFree(openid)
                    .flatMap(memberFree -> memberFree
                        ? createUnlockRecord(openid, articleName, 0, config, post, true)
                        : unlockWithTicketOrExisting(config, post, openid, articleName, cost));
            });
    }

    /** 非会员路径：先查是否已解锁，未解锁则扣券。 */
    private Mono<ContentLockedDTO> unlockWithTicketOrExisting(ContentTicketConfigDTO config,
        Post post, String openid, String articleName, int cost) {
        var unlockKey = ContentUnlockPersistence.buildUnlockKey(openid, articleName);
        return unlockPersistence.findByUnlockKey(unlockKey)
            .flatMap(existing -> {
                // 已解锁过：幂等返回，绝不重复扣券
                log.info("付费文章已解锁，跳过扣券: openid={}, article={}", openid, articleName);
                return lockedDtoMono(config, post, true, true, openid);
            })
            .switchIfEmpty(Mono.defer(() -> unlockWithTicket(config, post, openid,
                articleName, cost)));
    }

    /**
     * 当前用户是否享有会员免券权益。
     *
     * <p>只此一处判定：会员配置开启 + 免券权益开启 + 到期时间仍在未来。
     * 任一不满足即按非会员处理——宁可多问用户一次券，也不误放行。</p>
     *
     * <p>判定失败同样按非会员处理：拦截链路里「多收一次券」的用户体验损失
     * 远小于「全站付费文章被误放行」的收入损失与排查成本。</p>
     */
    private Mono<Boolean> isMemberFree(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(false);
        }
        return membershipService.loadConfig()
            .flatMap(memberConfig -> {
                if (!memberConfig.isEnabled() || !memberConfig.isFreeContent()) {
                    return Mono.just(false);
                }
                return membershipService.loadMemberExpireAt(openid)
                    .map(MemberConfigHelper::isActive)
                    .defaultIfEmpty(false);
            })
            .onErrorResume(ex -> {
                log.warn("会员免券判定失败，按非会员处理: openid={}, {}", openid, ex.getMessage());
                return Mono.just(false);
            });
    }

    /** 扣券解锁：先占幂等位，再扣券，扣券失败则补偿回滚。 */
    private Mono<ContentLockedDTO> unlockWithTicket(ContentTicketConfigDTO config, Post post,
        String openid, String articleName, int cost) {
        var record = ContentUnlockPersistence.newUnlock(openid, articleName, cost,
            UNLOCK_TYPE_TICKET, null);
        return pointsPersistence.getOrCreateAccount(openid)
            .flatMap(account -> {
                if (safeTickets(account.getContentTickets()) < cost) {
                    return Mono.error(new WxAuthException(
                        "阅读券不足，需要 " + cost + " 张，当前 " + safeTickets(account.getContentTickets()) + " 张"));
                }
                // 先写解锁记录占用幂等位。
                // ⚠️ 必须用 map→Boolean 而不是 onErrorResume→empty + then：
                // Mono.empty().then(deduct) 在 create 冲突时同样会执行 then 分支，
                // 会导致并发解锁同一篇文章时重复扣券。
                return unlockPersistence.create(record)
                    .map(created -> Boolean.TRUE)
                    .onErrorResume(ex -> {
                        if (isDuplicateNameError(ex)) {
                            log.info("并发解锁命中幂等闸门，视为已解锁且不扣券: openid={}, article={}",
                                openid, articleName);
                            return Mono.just(Boolean.FALSE);
                        }
                        return Mono.error(ex);
                    })
                    .flatMap(acquired -> {
                        if (!acquired) {
                            // 未拿到幂等位：另一线程已在解锁，直接返回已解锁，绝不扣券
                            return ticketBalanceMono(openid)
                                .map(tickets -> lockedDto(config, post, true, true, tickets));
                        }
                        return deductTickets(openid, account, cost, articleName)
                            .flatMap(deducted -> {
                                if (deducted) {
                                    // 扣券成功，余额为扣减后的值，直接给出无需再查
                                    return Mono.just(lockedDto(config, post, true, true,
                                        Math.max(0, safeTickets(account.getContentTickets()))));
                                }
                                // 扣券未成功：回滚刚写入的解锁记录，避免白拿
                                return rollbackUnlock(record);
                            });
                    });
            });
    }

    /** 扣减阅读券并写积分流水，返回是否扣减成功。 */
    private Mono<Boolean> deductTickets(String openid,
        pro.uhalo.uni.scheme.UserPoints account, int cost, String articleName) {
        var current = safeTickets(account.getContentTickets());
        if (current < cost) {
            // 二次校验：account 可能是扣券前读取的旧值
            return Mono.just(false);
        }
        account.setContentTickets(current - cost);
        // earnKey 以「用户 + 解锁动作 + 文章」构成，保证同一用户对同一篇文章
        // 只会产生一条消耗流水；真正的「一人一篇只扣一次券」由 ContentUnlock
        // 的唯一资源名保证，此处流水幂等只是记账层面的兜底
        var earnKey = openid + ":" + UNLOCK_ACTION + ":" + articleName;
        var record = pointsPersistence.newRecord(openid, UNLOCK_ACTION,
            0, safeBalance(account), "解锁付费文章 · " + truncate(articleName, 60),
            articleName, earnKey, account.getWxUserId());
        // 券消耗记为负数：后台看板据所有流水的 tickets 求和算出「券发放 / 券消耗」比
        record.setTickets(-cost);
        return pointsPersistence.saveAccount(account)
            .then(pointsPersistence.appendRecord(record)
                .onErrorResume(ex -> {
                    if (isDuplicateNameError(ex)) {
                        // 流水已存在说明这笔解锁已记账，视为成功
                        log.info("解锁流水已存在，视为已记账: openid={}, article={}", openid, articleName);
                        return Mono.just(record);
                    }
                    return Mono.error(ex);
                }))
            .thenReturn(true)
            .onErrorResume(ex -> {
                log.error("扣减阅读券失败，将回滚解锁记录: openid={}, article={}, {}: {}",
                    openid, articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.just(false);
            });
    }

    /** 补偿回滚：删除已写入的解锁记录，并向用户报错（未解锁）。 */
    private Mono<ContentLockedDTO> rollbackUnlock(pro.uhalo.uni.scheme.ContentUnlock record) {
        return unlockPersistence.delete(record)
            .onErrorResume(ex -> {
                log.error("回滚解锁记录失败（用户可能白拿一篇付费文章，需人工核查）: key={}, {}: {}",
                    record.getUnlockKey(), ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.empty();
            })
            .then(Mono.error(new WxAuthException("解锁失败，请稍后重试或检查阅读券余额")));
    }

    private Mono<ContentLockedDTO> createUnlockRecord(String openid, String articleName,
        int cost, ContentTicketConfigDTO config, Post post, boolean ownerFree) {
        var unlockKey = ContentUnlockPersistence.buildUnlockKey(openid, articleName);
        return unlockPersistence.findByUnlockKey(unlockKey)
            .flatMap(existing -> lockedDtoMono(config, post, true, true, openid))
            .switchIfEmpty(Mono.defer(() -> {
                var record = ContentUnlockPersistence.newUnlock(openid, articleName, cost,
                    UNLOCK_TYPE_TICKET, null);
                return unlockPersistence.create(record)
                    .onErrorResume(ex -> isDuplicateNameError(ex)
                        ? Mono.empty() : Mono.error(ex))
                    .then(ownerFree
                        // 博主豁免不扣券，余额无意义，直接给 0 避免多查一次
                        ? Mono.just(lockedDto(config, post, true, true, 0))
                        : lockedDtoMono(config, post, true, true, openid));
            }));
    }

    /**
     * 响应式读取当前用户的阅读券余额。
     *
     * <p>openid 为空（未登录）时返回 0，不查库。读取失败按 0 返回：
     * 余额只用于展示与提示文案，读不到不应阻断解锁主流程
     * （真正的余额校验在 {@link #unlockWithTicket} 内用已取得的 account 完成）。</p>
     */
    private Mono<Integer> ticketBalanceMono(String openid) {
        var normalizedOpenid = normalizeOpenidQuietly(openid);
        if (!StringUtils.hasText(normalizedOpenid)) {
            return Mono.just(0);
        }
        return pointsPersistence.getOrCreateAccount(normalizedOpenid)
            .map(account -> safeTickets(account.getContentTickets()))
            .onErrorResume(ex -> {
                log.warn("读取阅读券余额失败，按 0 展示: openid={}, {}: {}",
                    normalizedOpenid, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.just(0);
            });
    }

    /** 构造锁定信息，其中券余额走响应式读取（用于需要展示真实余额的场景）。 */
    private Mono<ContentLockedDTO> lockedDtoMono(ContentTicketConfigDTO config, Post post,
        boolean locked, boolean unlocked, String openid) {
        return ticketBalanceMono(openid)
            .map(tickets -> lockedDto(config, post, locked, unlocked, tickets));
    }

    // ==================== 付费判定 ====================

    /**
     * 文章是否被标记为付费：其 tags 中是否含付费标记标签。
     *
     * <p>先看是不是 AI 日报生成的文章：日报是拉新与留存的入口，
     * 一旦被付费墙拦下，从分享进来的新用户点开第一篇就被要求用券解锁，
     * 转化直接归零。因此日报文章默认豁免（可用 freeForDigest 关闭）。
     * 这里判在前面还有个好处：日报文章连标签都不用查，省一次标签解析。</p>
     */
    /**
     * 供订阅墙等其他拦截链路复用的付费判定。
     *
     * <p>口径必须与锁定判定走同一段代码（见下方私有重载）：
     * 订阅墙靠它「放行付费文章」，口径漂移会造成两堵墙互相漏拦或双重拦截。</p>
     */
    @Override
    public Mono<Boolean> isPaidPost(Post post) {
        return loadConfig().flatMap(config -> isPaidPost(config, post));
    }

    private Mono<Boolean> isPaidPost(ContentTicketConfigDTO config, Post post) {
        if (Boolean.TRUE.equals(config.getFreeForDigest()) && isDigestGenerated(post)) {
            return Mono.just(false);
        }
        var tags = post.getSpec() == null ? null : post.getSpec().getTags();
        if (tags == null || tags.isEmpty()) {
            return Mono.just(false);
        }
        return resolvePaidTagNames(config.getPaidTagSlug())
            .map(paidNames -> tags.stream().anyMatch(paidNames::contains));
    }

    /**
     * 把付费标签 slug 解析成 Tag 的 metadata.name 集合（带 TTL 缓存）。
     *
     * <p>缓存失效或解析异常时回落到「空集合」，即判定为不付费 → 放行。
     * 这个失败方向是「少收费」而非「误拦截全站」，是安全侧。</p>
     */
    private Mono<Set<String>> resolvePaidTagNames(String paidTagSlug) {
        var slug = StringUtils.hasText(paidTagSlug)
            ? paidTagSlug.trim().toLowerCase() : "";
        if (slug.isEmpty()) {
            return Mono.just(Collections.<String>emptySet());
        }
        var cached = paidTagNamesCache.get();
        var now = System.currentTimeMillis();
        if (cached != null && slug.equals(cached.slug())
            && now - cached.loadedAt() < TAG_CACHE_TTL.toMillis()) {
            return Mono.just(cached.names());
        }
        return client.listAll(Tag.class, new ListOptions(), null)
            .filter(tag -> tag != null && tag.getSpec() != null
                && slug.equals(normalizeSlug(tag.getSpec().getSlug())))
            .map(tag -> tag.getMetadata().getName())
            .collectList()
            // 显式收敛为 Set<String>：否则 map 推断出 HashSet<String>，
            // 下方 onErrorResume 的 Collections.emptySet() 无法满足其类型下限
            .<Set<String>>map(names -> {
                var set = new HashSet<String>(names);
                paidTagNamesCache.set(new TagCache(slug, set, now));
                if (set.isEmpty()) {
                    log.warn("付费标记标签未找到（slug={}）：请确认 Halo 后台已创建该 slug 的标签，"
                        + "否则付费文章不会被拦截", slug);
                }
                return set;
            })
            .onErrorResume(ex -> {
                log.warn("解析付费标记标签失败，按不付费放行: slug={}, {}: {}",
                    slug, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.just(Collections.<String>emptySet());
            });
    }

    /** 当前用户是否为文章作者（博主豁免）。 */
    private Mono<Boolean> isOwnerOf(ContentTicketConfigDTO config, Post post, String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.just(false);
        }
        // 站点所有者/管理员：全站付费内容都可读。两件事都要求这样：
        // ① 站长本来就该能看自己站里的所有内容；② 上架前要能逐篇自测付费墙。
        // 这一层不受 freeForOwner 开关影响——那个开关管的是「文章作者免券」，是给投稿者的。
        return privilegeService.isPrivileged(openid)
            .flatMap(privileged -> {
                if (privileged) {
                    return Mono.just(true);
                }
                if (!Boolean.TRUE.equals(config.getFreeForOwner())) {
                    return Mono.just(false);
                }
                var owner = post.getSpec() == null ? null : post.getSpec().getOwner();
                if (!StringUtils.hasText(owner)) {
                    return Mono.just(false);
                }
                // 作者豁免：用「微信用户绑定的 Halo 用户名」与文章 owner 比对
                return authorOf(openid, owner);
            });
    }

    /** 文章作者判定（微信用户绑定的 Halo 用户名 == 文章 owner） */
    private Mono<Boolean> authorOf(String openid, String owner) {
        return wxUserService.fetchByOpenid(openid)
            .map(wxUser -> StringUtils.hasText(wxUser.getHaloUserName())
                && owner.equals(wxUser.getHaloUserName()))
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("博主身份判定失败，按非博主处理: openid={}, {}: {}",
                    openid, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<Boolean> hasUnlocked(String openid, String articleName) {
        var unlockKey = ContentUnlockPersistence.buildUnlockKey(openid, articleName);
        return unlockPersistence.findByUnlockKey(unlockKey)
            .map(existing -> Boolean.TRUE)
            .defaultIfEmpty(Boolean.FALSE)
            .onErrorResume(ex -> {
                log.warn("查询解锁记录失败，按未解锁处理: key={}, {}: {}",
                    unlockKey, ex.getClass().getSimpleName(), ex.getMessage());
                // 查询失败按未解锁：会多问用户一次券，但不会泄露内容
                return Mono.just(false);
            });
    }

    /**
     * 是否为 AI 日报生成的文章。
     *
     * <p>认定依据是发布时打上的注解（{@code uni.uhalo.pro/digest-generated}）与
     * label（{@code uni.uhalo.pro/digest}），两者任一命中即算——
     * 早期版本只写注解，新版本同时写 label，两个都认才不会漏掉存量文章。</p>
     */
    private static boolean isDigestGenerated(Post post) {
        var metadata = post.getMetadata();
        if (metadata == null) {
            return false;
        }
        var annotations = metadata.getAnnotations();
        if (annotations != null && "true".equalsIgnoreCase(annotations.get(DIGEST_MARKER_ANNO))) {
            return true;
        }
        var labels = metadata.getLabels();
        return labels != null && "true".equalsIgnoreCase(labels.get(DIGEST_MARKER_LABEL));
    }

    private Mono<Post> fetchPost(String articleName) {
        if (!StringUtils.hasText(articleName)) {
            return Mono.empty();
        }
        return client.fetch(Post.class, articleName.trim())
            .onErrorResume(ex -> {
                log.warn("查询文章失败: articleName={}, {}: {}",
                    articleName, ex.getClass().getSimpleName(), ex.getMessage());
                return Mono.empty();
            });
    }

    // ==================== 配置与工具 ====================

    private Mono<ContentTicketConfigDTO> loadConfig() {
        return settingFetcher.get(ContentTicketConfigHelper.groupName())
            .map(ContentTicketConfigHelper::parse)
            .defaultIfEmpty(ContentTicketConfigHelper.disabled())
            .onErrorResume(ex -> {
                log.warn("付费内容配置读取失败，按关闭处理: {}", ex.getMessage());
                return Mono.just(ContentTicketConfigHelper.disabled());
            });
    }

    private ContentLockedDTO lockedDto(ContentTicketConfigDTO config, Post post, boolean locked,
        boolean unlocked, int tickets) {
        var spec = post.getSpec();
        var status = post.getStatus();
        return ContentLockedDTO.builder()
            .articleName(post.getMetadata().getName())
            .title(spec == null ? null : spec.getTitle())
            // 摘要取自 post.status.excerpt（Halo 自动生成，列表页本就公开），
            // 绝不读取正文 Snapshot，保证锁定响应里不可能带出付费内容
            .excerpt(status == null ? null : truncate(status.getExcerpt(), 200))
            .cover(spec == null ? null : spec.getCover())
            .locked(locked)
            .unlocked(unlocked)
            .tickets(tickets)
            .costTickets(resolveCost(config))
            .message(unlocked ? "已解锁" : "本文需使用阅读券解锁")
            .build();
    }

    private static int resolveCost(ContentTicketConfigDTO config) {
        var cost = config.getCostTickets();
        return cost != null && cost > 0 ? cost : ContentTicketConfigHelper.DEFAULT_COST_TICKETS;
    }

    private static int safeTickets(Integer tickets) {
        return tickets == null ? 0 : Math.max(0, tickets);
    }

    private static long safeBalance(pro.uhalo.uni.scheme.UserPoints account) {
        return account == null || account.getBalance() == null ? 0L : account.getBalance();
    }

    private void ensureRateLimit(String openid, String scene, int limit, String message) {
        if (!rateLimiter.tryAcquire(openid + ":" + scene, limit, Duration.ofMinutes(1))) {
            throw new WxAuthException(message);
        }
    }

    private static void validateArticleName(String articleName) {
        if (!StringUtils.hasText(articleName)) {
            throw new WxAuthException("articleName 不能为空");
        }
        var normalized = articleName.trim();
        if (normalized.length() > MAX_ARTICLE_NAME_LENGTH) {
            throw new WxAuthException("articleName 参数过长");
        }
        for (var i = 0; i < normalized.length(); i++) {
            var ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                throw new WxAuthException("articleName 参数不合法");
            }
        }
    }

    private static String normalizeOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new WxAuthException("缺少 Wechat-Session-Id");
        }
        return openid.trim();
    }

    /** 拦截链路用：openid 缺失时返回 null 而非抛异常（未登录也要能拦截并提示登录） */
    private static String normalizeOpenidQuietly(String openid) {
        return StringUtils.hasText(openid) ? openid.trim() : null;
    }

    private static String normalizeSlug(String slug) {
        return StringUtils.hasText(slug) ? slug.trim().toLowerCase() : "";
    }

    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }

    private static String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** 付费标签 name 集合缓存条目 */
    private record TagCache(String slug, Set<String> names, long loadedAt) {
    }
}
