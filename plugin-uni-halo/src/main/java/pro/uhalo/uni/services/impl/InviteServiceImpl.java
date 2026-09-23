package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminInviteRecordItemDTO;
import pro.uhalo.uni.dto.AdminInviteRecordListResponse;
import pro.uhalo.uni.dto.AdminInviteSummaryItemDTO;
import pro.uhalo.uni.dto.DigestSubscriptionDTO;
import pro.uhalo.uni.dto.InviteInfoDTO;
import pro.uhalo.uni.dto.InviteLandingDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.services.DigestTaxonomyService;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.InviteService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.PointsRecordHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请裂变实现：邀请码 8 位随机（去易混字符），邀请关系写在被邀请人
 * WxUser.inviterOpenid，奖励积分通过 PointsRecord(action=invite) 入账，
 * earnKey 含被邀请人 openid，天然幂等。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteServiceImpl implements InviteService {

    private static final String CONFIG_GROUP = "inviteConfig";
    private static final String INVITE_ACTION = "invite";

    /** 被邀请人（新用户）那一侧的奖励，与被邀请人自己的「新人礼」是两笔独立奖励 */
    private static final String INVITEE_ACTION = "invitee_invite";
    private static final String INVITE_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int INVITE_CODE_LENGTH = 8;
    private static final int INVITE_CODE_MAX_ATTEMPTS = 5;
    private static final int INFO_RATE_LIMIT_PER_MINUTE = 15;
    private static final int ADMIN_MAX_SIZE = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WxUserService wxUserService;
    private final PointsPersistence persistence;
    private final ReactiveSettingFetcher settingFetcher;
    private final RateLimiter rateLimiter;
    private final DigestSubscriptionService subscriptionService;
    private final DigestTaxonomyService taxonomyService;

    @Override
    public Mono<InviteInfoDTO> getInviteInfo(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "invite-info", INFO_RATE_LIMIT_PER_MINUTE, "操作过于频繁，请稍后再试");
                return loadConfig()
                    .flatMap(config -> wxUserService.fetchByOpenid(id)
                        .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                        .flatMap(user -> ensureInviteCode(user)
                            .flatMap(ensured -> wxUserService.countByInviterOpenid(id)
                                .flatMap(count -> {
                                    var base = InviteInfoDTO.builder()
                                        .enabled(config.enabled())
                                        .rewardPoints(config.rewardPoints())
                                        .inviteCode(ensured.getInviteCode())
                                        .invitedCount(count)
                                        .posterGeneratedAt(PointsRecordHelper.formatInstant(user.getPosterGeneratedAt()))
                                        .posterImageUrl(user.getPosterImageUrl())
                                        .build();
                                    return resolveInviter(user)
                                        .map(inviter -> {
                                            base.setInviterNickname(StringUtils.hasText(inviter.getNickname())
                                                ? inviter.getNickname().trim()
                                                : inviter.getHaloUserName());
                                            base.setInviterAvatar(inviter.getAvatarUrl());
                                            return base;
                                        })
                                        .defaultIfEmpty(base);
                                }))));
            });
    }

    @Override
    public Mono<Boolean> markPosterGenerated(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "invite-poster-generate", 10, "操作过于频繁，请稍后再试");
                return wxUserService.fetchByOpenid(id)
                    .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                    .flatMap(user -> {
                        if (user.getPosterGeneratedAt() != null) {
                            return Mono.just(true);
                        }
                        user.setPosterGeneratedAt(java.time.Instant.now());
                        return wxUserService.update(user).map(updated -> true);
                    });
            });
    }

    @Override
    public Mono<Boolean> attachPosterImage(String openid, String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return Mono.just(false);
        }
        return requireOpenid(openid)
            .flatMap(id -> wxUserService.fetchByOpenid(id)
                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                .flatMap(user -> {
                    user.setPosterImageUrl(imageUrl.trim());
                    if (user.getPosterGeneratedAt() == null) {
                        user.setPosterGeneratedAt(java.time.Instant.now());
                    }
                    return wxUserService.update(user).map(updated -> true);
                }));
    }

    /** 查询邀请我的好友（被邀请关系），无邀请人时返回空 */
    private Mono<WxUser> resolveInviter(WxUser user) {
        var inviterOpenid = user.getInviterOpenid();
        if (!StringUtils.hasText(inviterOpenid)) {
            return Mono.empty();
        }
        return wxUserService.fetchByOpenid(inviterOpenid.trim());
    }

    @Override
    public Mono<Boolean> grantInviteReward(String inviteeOpenid, String inviteCode, boolean isNewUser) {
        // 只对本次新建注册（首次进入小程序）的用户发放邀请奖励
        if (!isNewUser) {
            return Mono.just(false);
        }
        if (!StringUtils.hasText(inviteeOpenid) || !StringUtils.hasText(inviteCode)) {
            return Mono.just(false);
        }
        var normalizedCode = inviteCode.trim().toUpperCase();
        if (!normalizedCode.matches("[A-Z0-9]{4,32}")) {
            return Mono.just(false);
        }
        return loadConfig()
            .flatMap(config -> {
                if (!config.enabled()) {
                    return Mono.just(false);
                }
                return wxUserService.fetchByInviteCode(normalizedCode)
                    .flatMap(inviter -> {
                        if (inviteeOpenid.equals(inviter.getOpenid())) {
                            return Mono.just(false);
                        }
                        return wxUserService.fetchByOpenid(inviteeOpenid)
                            .flatMap(invitee -> {
                                if (StringUtils.hasText(invitee.getInviterOpenid())) {
                                    return Mono.just(false);
                                }
                                invitee.setInviterOpenid(inviter.getOpenid());
                                // 绑定成功即算「邀请成立」：两侧奖励各自判断额度，
                                // 一侧配 0 不影响另一侧（也才谈得上「各得 N 积分」）
                                return wxUserService.update(invitee)
                                    .then(issueInviterReward(inviter, inviteeOpenid,
                                        config.rewardPoints())
                                        .flatMap(inviterRewarded -> issueInviteeReward(inviteeOpenid,
                                            inviter.getOpenid(), config.inviteeRewardPoints())
                                            .map(inviteeRewarded -> inviterRewarded
                                                || inviteeRewarded)));
                            })
                            .switchIfEmpty(Mono.just(false));
                    })
                    .switchIfEmpty(Mono.just(false));
            })
            .onErrorResume(ex -> {
                log.warn("grant invite reward failed, invitee={}: {}", inviteeOpenid, ex.getMessage());
                return Mono.just(false);
            });
    }

    /** 邀请人侧奖励；额度为 0 时直接跳过（不是错误） */
    private Mono<Boolean> issueInviterReward(WxUser inviter, String inviteeOpenid, int reward) {
        if (reward <= 0) {
            return Mono.just(false);
        }
        var inviterOpenid = inviter.getOpenid();
        var earnKey = PointsServiceImpl.buildEarnKey(inviterOpenid, INVITE_ACTION, inviteeOpenid);
        return persistence.findRecordByEarnKey(earnKey)
            .flatMap(existing -> Mono.just(false))
            .switchIfEmpty(Mono.defer(() -> persistence.getOrCreateAccount(inviterOpenid)
                .flatMap(account -> {
                    var newBalance = PointsServiceImpl.safeBalance(account) + reward;
                    return resolveWxUserId(inviterOpenid, account)
                        .flatMap(wxUserId -> {
                            account.setBalance(newBalance);
                            if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                                account.setWxUserId(wxUserId);
                            }
                            var record = PointsPersistence.newRecord(inviterOpenid, INVITE_ACTION, reward,
                                newBalance, "邀请注册奖励", inviteeOpenid, earnKey, wxUserId);
                            return persistence.saveAccount(account)
                                .then(persistence.appendRecord(record))
                                .thenReturn(true)
                                .onErrorResume(ex -> isDuplicateRecordError(ex)
                                    ? Mono.just(false)
                                    : Mono.error(ex));
                        });
                })));
    }

    private Mono<WxUser> ensureInviteCode(WxUser user) {
        if (StringUtils.hasText(user.getInviteCode())) {
            return Mono.just(user);
        }
        return generateUniqueCode()
            .flatMap(code -> {
                user.setInviteCode(code);
                return wxUserService.update(user);
            });
    }

    @Override
    public Mono<AdminInviteRecordListResponse> listInviteRecordsForAdmin(String keyword, int page, int size) {
        var normalizedPage = Math.max(page, 0);
        var normalizedSize = Math.min(Math.max(size, 1), ADMIN_MAX_SIZE);
        var normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;

        return Mono.zip(
                persistence.listAllRecords(),
                wxUserService.loadUserLookup(),
                wxUserService.listAllUsers())
            .map(tuple -> {
                var records = tuple.getT1();
                var lookup = tuple.getT2();
                var users = tuple.getT3();
                var inviteRecords = records.stream()
                    .filter(record -> INVITE_ACTION.equals(record.getAction()))
                    .filter(record -> matchesInviteKeyword(record, lookup, normalizedKeyword))
                    .sorted(Comparator.comparing(PointsRecord::getCreateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .toList();
                var total = inviteRecords.size();
                var from = Math.min(normalizedPage * normalizedSize, total);
                var to = Math.min(from + normalizedSize, total);
                var items = inviteRecords.subList(from, to).stream()
                    .map(record -> toAdminInviteItem(record, lookup))
                    .toList();
                return AdminInviteRecordListResponse.builder()
                    .items(items)
                    .summary(buildInviteSummary(inviteRecords, lookup, users, normalizedKeyword))
                    .total(total)
                    .hasNext(to < total)
                    .page(normalizedPage)
                    .size(normalizedSize)
                    .build();
            });
    }

    private AdminInviteRecordItemDTO toAdminInviteItem(PointsRecord record, WxUserLookup lookup) {
        var inviterOpenid = PointsRecordHelper.resolveOpenid(record);
        var inviteeOpenid = StringUtils.hasText(record.getRefName()) ? record.getRefName().trim() : null;
        var inviter = resolveNullable(lookup, inviterOpenid);
        var invitee = resolveNullable(lookup, inviteeOpenid);
        return AdminInviteRecordItemDTO.builder()
            .id(record.getMetadata() != null ? record.getMetadata().getName() : null)
            .inviterOpenid(inviterOpenid)
            .inviterNickname(PointsRecordHelper.resolveDisplayName(inviter, inviterOpenid))
            .inviterAvatar(inviter == null ? null : inviter.getAvatarUrl())
            .inviterHaloUserName(inviter == null ? null : inviter.getHaloUserName())
            .inviteeOpenid(inviteeOpenid)
            .inviteeNickname(PointsRecordHelper.resolveDisplayName(invitee, inviteeOpenid))
            .inviteeAvatar(invitee == null ? null : invitee.getAvatarUrl())
            .inviteeHaloUserName(invitee == null ? null : invitee.getHaloUserName())
            .points(record.getPoints() == null ? 0 : record.getPoints())
            .balance(record.getBalance() == null ? 0L : record.getBalance())
            .createTime(PointsRecordHelper.resolveCreateTime(record))
            .createTimeText(PointsRecordHelper.formatInstant(PointsRecordHelper.resolveCreateInstant(record)))
            .build();
    }

    /**
     * 按邀请人聚合：累计邀请人数 + 累计发放积分，按总积分倒序。
     * 生成过邀请海报的用户即使尚未邀请成功也保留在汇总中（posterGenerated=true）。
     */
    private List<AdminInviteSummaryItemDTO> buildInviteSummary(List<PointsRecord> records,
        WxUserLookup lookup, List<WxUser> users, String keyword) {
        var aggregates = new LinkedHashMap<String, Map<String, Object>>();
        for (var record : records) {
            var inviterOpenid = PointsRecordHelper.resolveOpenid(record);
            if (!StringUtils.hasText(inviterOpenid)) {
                continue;
            }
            var points = record.getPoints() == null ? 0 : record.getPoints();
            var agg = aggregates.computeIfAbsent(inviterOpenid, id -> new LinkedHashMap<>());
            agg.put("count", ((Number) agg.getOrDefault("count", 0L)).longValue() + 1);
            agg.put("points", ((Number) agg.getOrDefault("points", 0L)).longValue() + points);
            var createTime = PointsRecordHelper.resolveCreateInstant(record);
            if (createTime != null) {
                var last = (java.time.Instant) agg.get("lastTime");
                if (last == null || createTime.isAfter(last)) {
                    agg.put("lastTime", createTime);
                }
            }
        }
        // 生成过海报的用户并入汇总（可能尚无邀请成功记录）
        for (var user : users) {
            if (user.getPosterGeneratedAt() == null || !StringUtils.hasText(user.getOpenid())) {
                continue;
            }
            if (keyword != null && !matchesUserKeyword(user, keyword)) {
                continue;
            }
            aggregates.putIfAbsent(user.getOpenid(), new LinkedHashMap<>());
        }
        var summary = new ArrayList<AdminInviteSummaryItemDTO>();
        for (var entry : aggregates.entrySet()) {
            var inviterOpenid = entry.getKey();
            var agg = entry.getValue();
            var inviter = resolveNullable(lookup, inviterOpenid);
            var invitedCount = ((Number) agg.getOrDefault("count", 0L)).longValue();
            var totalPoints = ((Number) agg.getOrDefault("points", 0L)).longValue();
            AdminInviteSummaryItemDTO item = AdminInviteSummaryItemDTO.builder()
                .inviterOpenid(inviterOpenid)
                .inviterNickname(PointsRecordHelper.resolveDisplayName(inviter, inviterOpenid))
                .inviterAvatar(inviter == null ? null : inviter.getAvatarUrl())
                .inviterHaloUserName(inviter == null ? null : inviter.getHaloUserName())
                .invitedCount(invitedCount)
                .totalPoints(totalPoints)
                .lastInviteTime(PointsRecordHelper.formatInstant((java.time.Instant) agg.get("lastTime")))
                .build();
            if (inviter != null && inviter.getPosterGeneratedAt() != null) {
                item.setPosterGenerated(true);
                item.setPosterGeneratedAt(PointsRecordHelper.formatInstant(inviter.getPosterGeneratedAt()));
                item.setPosterImageUrl(inviter.getPosterImageUrl());
            }
            summary.add(item);
        }
        summary.sort(Comparator
            .comparingLong(AdminInviteSummaryItemDTO::getTotalPoints).reversed()
            .thenComparing(Comparator.comparingLong(AdminInviteSummaryItemDTO::getInvitedCount).reversed()
                .thenComparing(item -> item.getInviterOpenid(), Comparator.nullsLast(String::compareTo))));
        return summary;
    }

    private static boolean matchesUserKeyword(WxUser user, String keyword) {
        return containsIgnoreCase(user.getOpenid(), keyword)
            || containsIgnoreCase(user.getNickname(), keyword)
            || containsIgnoreCase(user.getHaloUserName(), keyword);
    }

    private static WxUser resolveNullable(WxUserLookup lookup, String openid) {
        return StringUtils.hasText(openid) ? lookup.resolve(openid, null) : null;
    }

    private static boolean matchesInviteKeyword(PointsRecord record, WxUserLookup lookup, String keyword) {
        if (keyword == null) {
            return true;
        }
        var inviterOpenid = PointsRecordHelper.resolveOpenid(record);
        var inviteeOpenid = StringUtils.hasText(record.getRefName()) ? record.getRefName().trim() : null;
        if (containsIgnoreCase(inviterOpenid, keyword) || containsIgnoreCase(inviteeOpenid, keyword)) {
            return true;
        }
        var inviter = resolveNullable(lookup, inviterOpenid);
        var invitee = resolveNullable(lookup, inviteeOpenid);
        return containsIgnoreCase(inviter == null ? null : inviter.getNickname(), keyword)
            || containsIgnoreCase(inviter == null ? null : inviter.getHaloUserName(), keyword)
            || containsIgnoreCase(invitee == null ? null : invitee.getNickname(), keyword)
            || containsIgnoreCase(invitee == null ? null : invitee.getHaloUserName(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private Mono<String> generateUniqueCode() {
        return attemptUniqueCode(0);
    }

    private Mono<String> attemptUniqueCode(int attempt) {
        if (attempt >= INVITE_CODE_MAX_ATTEMPTS) {
            return Mono.error(new WxAuthException("邀请码生成失败，请稍后重试"));
        }
        var code = randomCode();
        return wxUserService.fetchByInviteCode(code)
            .hasElement()
            .flatMap(taken -> taken ? attemptUniqueCode(attempt + 1) : Mono.just(code));
    }

    private static String randomCode() {
        var builder = new StringBuilder(INVITE_CODE_LENGTH);
        for (var i = 0; i < INVITE_CODE_LENGTH; i++) {
            builder.append(INVITE_CODE_CHARS.charAt(RANDOM.nextInt(INVITE_CODE_CHARS.length())));
        }
        return builder.toString();
    }

    /**
     * 邀请奖励配置。
     *
     * @param enabled             邀请奖励总开关
     * @param rewardPoints        邀请人每成功邀请 1 位新用户的积分
     * @param inviteeRewardPoints 被邀请人（新用户）同一次注册拿到的积分，0 表示不发
     */
    private record InviteConfig(boolean enabled, int rewardPoints, int inviteeRewardPoints) {
    }

    private Mono<InviteConfig> loadConfig() {
        return settingFetcher.get(CONFIG_GROUP)
            .map(node -> parseConfig(node))
            .defaultIfEmpty(new InviteConfig(true, 30, 30));
    }

    private static InviteConfig parseConfig(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new InviteConfig(true, 30, 30);
        }
        return new InviteConfig(
            node.path("enabled").asBoolean(true),
            Math.max(0, node.path("rewardPoints").asInt(30)),
            // 被邀请人奖励允许配 0（只给邀请人发），所以下限是 0 而不是 1
            Math.max(0, node.path("inviteeRewardPoints").asInt(30))
        );
    }

    /**
     * 被邀请人侧奖励（新用户同一次注册也能拿到积分）。
     *
     * <p>幂等键含邀请人 openid：同一个人即使被不同人邀请，也只会作为「被邀请人」领一次；
     * 走的是与新人礼同款的一次性通道，不受每日积分上限影响。</p>
     */
    private Mono<Boolean> issueInviteeReward(String inviteeOpenid, String inviterOpenid, int reward) {
        if (reward <= 0) {
            return Mono.just(false);
        }
        var earnKey = PointsServiceImpl.buildEarnKey(inviteeOpenid, INVITEE_ACTION, inviterOpenid);
        return persistence.findRecordByEarnKey(earnKey)
            .flatMap(existing -> Mono.just(false))
            .switchIfEmpty(Mono.defer(() -> persistence.getOrCreateAccount(inviteeOpenid)
                .flatMap(account -> {
                    var newBalance = PointsServiceImpl.safeBalance(account) + reward;
                    return resolveWxUserId(inviteeOpenid, account)
                        .flatMap(wxUserId -> {
                            account.setBalance(newBalance);
                            if (StringUtils.hasText(wxUserId) && !wxUserId.equals(account.getWxUserId())) {
                                account.setWxUserId(wxUserId);
                            }
                            var record = PointsPersistence.newRecord(inviteeOpenid, INVITEE_ACTION,
                                reward, newBalance, "受邀注册奖励", inviterOpenid, earnKey, wxUserId);
                            return persistence.saveAccount(account)
                                .then(persistence.appendRecord(record))
                                .thenReturn(true)
                                .onErrorResume(ex -> isDuplicateRecordError(ex)
                                    ? Mono.just(false)
                                    : Mono.error(ex));
                        });
                })))
            .onErrorResume(ex -> {
                // 奖励失败不该让邀请绑定失败
                log.warn("issue invitee reward failed, invitee={}: {}", inviteeOpenid,
                    ex.getMessage());
                return Mono.just(false);
            });
    }

    @Override
    public Mono<InviteLandingDTO> landing(String inviteCode, String viewerOpenid) {
        var code = normalizeInviteCode(inviteCode);
        if (code == null) {
            return Mono.just(invalidLanding("分享链接不完整，请让好友重新分享一次"));
        }
        var viewer = StringUtils.hasText(viewerOpenid) ? viewerOpenid.trim() : "";
        return wxUserService.fetchByInviteCode(code)
            .flatMap(sharer -> Mono.zip(
                    subscriptionService.load(sharer.getOpenid()),
                    // 未登录时 load("") 会返回空偏好，不需要单独分支
                    subscriptionService.load(viewer),
                    taxonomyService.categoryLabels())
                .map(tuple -> buildLanding(sharer, code, viewer, tuple.getT1(), tuple.getT2(),
                    tuple.getT3())))
            .switchIfEmpty(Mono.fromSupplier(
                () -> invalidLanding("这个分享链接已失效，请让好友重新分享一次")))
            .onErrorResume(ex -> {
                log.warn("invite landing failed code={}: {}", code, ex.getMessage());
                return Mono.just(invalidLanding("分享内容加载失败，请稍后重试"));
            });
    }

    @Override
    public Mono<DigestSubscriptionDTO> acceptRecommendation(String viewerOpenid, String inviteCode) {
        var code = normalizeInviteCode(inviteCode);
        if (code == null) {
            return Mono.error(new WxAuthException("分享链接不完整"));
        }
        if (!StringUtils.hasText(viewerOpenid)) {
            return Mono.error(new WxAuthException("请先登录"));
        }
        return wxUserService.fetchByInviteCode(code)
            .switchIfEmpty(Mono.error(new WxAuthException("这个分享链接已失效")))
            .flatMap(sharer -> subscriptionService.load(sharer.getOpenid())
                .flatMap(shared -> subscriptionService.acceptRecommendation(viewerOpenid.trim(),
                    sharer.getOpenid(), shared.getPostCategories())));
    }

    private static InviteLandingDTO invalidLanding(String message) {
        return InviteLandingDTO.builder()
            .valid(false)
            .message(message)
            .categories(List.of())
            .followedCategories(List.of())
            .build();
    }

    private static InviteLandingDTO buildLanding(WxUser sharer, String code, String viewer,
        DigestSubscriptionDTO sharerSubscription, DigestSubscriptionDTO viewerSubscription,
        Map<String, String> labels) {
        var items = orEmptyList(sharerSubscription.getPostCategories()).stream()
            .map(name -> InviteLandingDTO.CategoryItem.builder()
                .name(name)
                .label(labels.getOrDefault(name, name))
                .build())
            .toList();
        return InviteLandingDTO.builder()
            .valid(true)
            .inviteCode(code)
            .sharerName(nullToEmpty(sharer.getNickname()))
            .sharerAvatar(nullToEmpty(sharer.getAvatarUrl()))
            .categories(items)
            .loggedIn(StringUtils.hasText(viewer))
            .hasOwnSubscription(!orEmptyList(viewerSubscription.getPostCategories()).isEmpty())
            .followedCategories(orEmptyList(viewerSubscription.getEffectiveCategories()))
            .message(items.isEmpty() ? "这位读者还没有订阅分类，下面为你展示全站最新内容" : "")
            .build();
    }

    /** 邀请码归一化：去空、转大写、格式校验；不合法返回 null */
    private static String normalizeInviteCode(String inviteCode) {
        if (!StringUtils.hasText(inviteCode)) {
            return null;
        }
        var normalized = inviteCode.trim().toUpperCase();
        return normalized.matches("[A-Z0-9]{4,32}") ? normalized : null;
    }

    private static List<String> orEmptyList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public Mono<Boolean> applyInviteInheritance(String inviteeOpenid, String inviteCode) {
        if (!StringUtils.hasText(inviteeOpenid) || !StringUtils.hasText(inviteCode)) {
            return Mono.just(false);
        }
        var normalizedCode = inviteCode.trim().toUpperCase();
        if (!normalizedCode.matches("[A-Z0-9]{4,32}")) {
            return Mono.just(false);
        }
        return wxUserService.fetchByInviteCode(normalizedCode)
            .flatMap(inviter -> {
                if (inviteeOpenid.equals(inviter.getOpenid())) {
                    return Mono.just(false);
                }
                // 用邀请人「自己订阅的」分类作为被邀请人首页的起点；
                // 只继承一层，不做链式继承（否则一个人的订阅会被越传越杂）
                return subscriptionService.load(inviter.getOpenid())
                    .flatMap(inviterSubscription -> subscriptionService.applyInheritance(
                        inviteeOpenid, inviter.getOpenid(),
                        inviterSubscription.getPostCategories()));
            })
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("apply invite inheritance failed, invitee={}: {}", inviteeOpenid,
                    ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<String> resolveWxUserId(String openid, UserPoints account) {
        if (account != null && StringUtils.hasText(account.getWxUserId())) {
            return Mono.just(account.getWxUserId().trim());
        }
        return wxUserService.fetchByOpenid(openid)
            .map(WxUserIds::resolveId)
            .filter(StringUtils::hasText)
            .defaultIfEmpty("");
    }

    private void ensureRateLimit(String openid, String scene, int limit, String message) {
        if (!rateLimiter.tryAcquire(openid + ":" + scene, limit, Duration.ofMinutes(1))) {
            throw new WxAuthException(message);
        }
    }

    private static Mono<String> requireOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
        }
        return Mono.just(openid.trim());
    }

    private static boolean isDuplicateRecordError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }
}
