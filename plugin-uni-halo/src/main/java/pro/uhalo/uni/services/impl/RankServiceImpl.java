package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.LevelConfigDTO;
import pro.uhalo.uni.dto.RankArticleDTO;
import pro.uhalo.uni.dto.RankConfigDTO;
import pro.uhalo.uni.dto.UserActivityFeedItemDTO;
import pro.uhalo.uni.dto.UserRankItemDTO;
import pro.uhalo.uni.dto.UserRankResponse;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.PointsService;
import pro.uhalo.uni.services.RankService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.OwnedOutfitParser;
import pro.uhalo.uni.utils.RankConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 榜单：文章榜基于 Halo 内容计数器（Counter，访问量/点赞量）排序；
 * 用户榜基于积分流水（PointsRecord）与邀请关系（WxUser.inviterOpenid）聚合；
 * 互动动态来自用户行为记录（UserActivity）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RankServiceImpl implements RankService {

    private static final String POST_COUNTER_PREFIX = "posts.content.halo.run/";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String USER_RANK_TYPE_POINTS = "points";
    private static final String USER_RANK_TYPE_ACTIVE = "active";
    private static final String USER_RANK_TYPE_INVITE = "invite";
    private static final String USER_RANK_RANGE_WEEK = "week";
    private static final String USER_RANK_RANGE_ALL = "all";

    private static final int USER_RANK_MAX_LIMIT = 50;
    private static final int USER_RANK_ACTIVE_DAYS = 7;
    private static final String DEFAULT_NICKNAME = "微信用户";

    private static final String ACTIVITY_TYPE_COMMENT = "comment";
    private static final String ACTIVITY_TYPE_UPVOTE = "upvote";
    private static final String ACTIVITY_TYPE_VOTE = "vote";
    private static final String ACTIVITY_TYPE_REACTION = "reaction";
    /** 可上墙的动作：浏览行为量大且私密，不上墙 */
    private static final Set<String> WALL_ACTIVITY_TYPES = Set.of(ACTIVITY_TYPE_COMMENT,
        ACTIVITY_TYPE_UPVOTE, ACTIVITY_TYPE_VOTE, ACTIVITY_TYPE_REACTION);

    /** 周榜结算检查间隔与首次延迟：结算本身按周幂等，轮询只负责在周一之后尽快触发 */
    private static final long WEEKLY_SETTLE_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long WEEKLY_SETTLE_INITIAL_DELAY_MS = 3 * 60 * 1000L;

    /**
     * 用户榜全量结果缓存：一次请求需要全量扫描积分流水，站内用户量小，
     * 60 秒缓存足以避免每次进榜单页都全量聚合。
     */
    private static final Duration USER_RANK_CACHE_TTL = Duration.ofSeconds(60);

    private final ReactiveExtensionClient client;
    private final PointsPersistence pointsPersistence;
    private final WxUserService wxUserService;
    private final ReactiveSettingFetcher settingFetcher;
    private final PointsService pointsService;

    /** key = type|range|showScore，value = 已排好序并回填名次的全量榜单 */
    private final Map<String, CachedBoard> userRankCache = new ConcurrentHashMap<>();

    /**
     * 本 JVM 内已结算的周。真正的幂等保障是积分流水 earnKey 的唯一约束
     * （{@code openid:rank_reward:yyyy-Wxx}），此字段只用于避免重复计算与重复日志。
     */
    private volatile String lastSettledWeek = "";

    @Override
    public Mono<List<RankArticleDTO>> topArticles(int limit) {
        var normalizedLimit = Math.min(Math.max(limit, 1), 20);
        return client.listAll(Counter.class, new ListOptions(), null)
            .filter(counter -> counter != null && counter.getMetadata() != null
                && StringUtils.hasText(counter.getMetadata().getName())
                && counter.getMetadata().getName().startsWith(POST_COUNTER_PREFIX))
            .sort(Comparator.comparing(Counter::getVisit,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .take(normalizedLimit)
            .concatMap(this::toRankArticle)
            .filter(article -> article != null && StringUtils.hasText(article.getName()))
            .collectList();
    }

    @Override
    public Mono<UserRankResponse> topUsers(String type, String range, int limit, String openid) {
        var boardType = normalizeUserRankType(type);
        var boardRange = normalizeUserRankRange(range, boardType);
        var normalizedLimit = Math.min(Math.max(limit, 1), USER_RANK_MAX_LIMIT);
        return loadRankConfig().flatMap(config -> {
            // 站长关闭榜单、或关闭邀请榜时直接返回空榜，避免前端配置过期时仍能取到数据
            if (!config.isEnabled()
                || (USER_RANK_TYPE_INVITE.equals(boardType) && !config.isEnableInviteBoard())) {
                return Mono.just(emptyUserRank(boardType, boardRange));
            }
            return loadUserBoard(config, boardType, boardRange)
                .map(users -> UserRankResponse.builder()
                    .type(boardType)
                    .range(boardRange)
                    .items(users.stream().limit(normalizedLimit).map(RankedUser::item).toList())
                    .total(users.size())
                    .me(resolveMe(users, openid, config))
                    .build());
        });
    }

    @Override
    public Mono<List<UserActivityFeedItemDTO>> recentActivities(int limit) {
        return loadRankConfig().flatMap(config -> {
            if (!config.isEnabled() || !config.isWallEnabled()) {
                return Mono.just(List.of());
            }
            var normalizedLimit = Math.min(Math.max(limit, 1), RankConfigHelper.MAX_WALL_LIMIT);
            return Mono.zip(
                    client.listAll(UserActivity.class, new ListOptions(), null).collectList(),
                    wxUserService.listAllUsers().defaultIfEmpty(List.of()),
                    pointsPersistence.listAllAccounts().defaultIfEmpty(List.of()))
                .map(tuple -> buildActivityFeed(config, tuple.getT1(), tuple.getT2(), tuple.getT3(),
                    normalizedLimit));
        });
    }

    /**
     * 周榜奖励结算：每周一之后首次执行时，结算上一自然周（周一 00:00 ~ 周日 24:00，Asia/Shanghai）
     * 的活跃榜并发放积分。同一周同一用户只发一次（earnKey 幂等）。
     */
    @Override
    @Scheduled(fixedDelay = WEEKLY_SETTLE_INTERVAL_MS, initialDelay = WEEKLY_SETTLE_INITIAL_DELAY_MS)
    public void settleWeeklyReward() {
        settleWeeklyRewardOnce()
            .subscribe(summary -> log.info("周榜奖励结算完成：{}", summary),
                ex -> log.warn("周榜奖励结算异常：{}", ex.getMessage(), ex));
    }

    /**
     * 单次结算尝试（定时任务的实体，独立出来便于测试）：
     * 未开启周榜奖励、本周已结算、或上周无人上榜时以 {@code Mono.empty()} 结束。
     */
    Mono<String> settleWeeklyRewardOnce() {
        return loadRankConfig()
            .filter(config -> config.isEnabled() && config.isWeeklyRewardEnabled())
            .flatMap(config -> {
                var week = resolvePreviousWeek();
                if (week.weekKey().equals(lastSettledWeek)) {
                    return Mono.empty();
                }
                return settleWeek(config, week);
            });
    }

    private Mono<String> settleWeek(RankConfigDTO config, WeekWindow week) {
        return Mono.zip(
                wxUserService.listAllUsers().defaultIfEmpty(List.of()),
                pointsPersistence.listAllRecords().defaultIfEmpty(List.of()),
                pointsPersistence.listAllAccounts().defaultIfEmpty(List.of()),
                loadLevelConfig())
            .flatMap(tuple -> {
                var ranked = buildUserBoard(USER_RANK_TYPE_ACTIVE, week.from(), week.to(),
                    tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), config.isShowScore());
                var winners = ranked.stream().limit(config.getWeeklyRewardTopN()).toList();
                if (winners.isEmpty()) {
                    lastSettledWeek = week.weekKey();
                    return Mono.just("week=" + week.weekKey() + "，上周无人上榜，未发放");
                }
                return Flux.fromIterable(winners)
                    .concatMap(winner -> {
                        var rank = winner.item().getRank();
                        var points = RankConfigHelper.rewardPointsForRank(config, rank);
                        if (points <= 0) {
                            return Mono.empty();
                        }
                        return pointsService.grantRankReward(winner.openid(), points, week.weekKey(),
                                "上周活跃榜第 " + rank + " 名奖励")
                            .doOnNext(response -> log.info("周榜奖励：week={} rank={} 发放={}",
                                week.weekKey(), rank, response.getEarned()))
                            // 单个名次发放失败不阻断其余名次，下次轮询会按幂等键补发
                            .onErrorResume(ex -> {
                                log.warn("周榜奖励发放失败：week={} rank={} {}", week.weekKey(), rank,
                                    ex.getMessage());
                                return Mono.empty();
                            });
                    })
                    .count()
                    .map(count -> "week=" + week.weekKey() + "，发放名次=" + count)
                    .doOnNext(summary -> lastSettledWeek = week.weekKey());
            });
    }

    private Mono<RankArticleDTO> toRankArticle(Counter counter) {
        var postName = counter.getMetadata().getName().substring(POST_COUNTER_PREFIX.length());
        return client.fetch(Post.class, postName)
            .map(post -> RankArticleDTO.builder()
                .name(post.getMetadata().getName())
                .title(post.getSpec() == null ? "" : post.getSpec().getTitle())
                .cover(post.getSpec() == null ? null : post.getSpec().getCover())
                .visit(counter.getVisit() == null ? 0 : counter.getVisit())
                .upvote(counter.getUpvote() == null ? 0 : counter.getUpvote())
                .build())
            .onErrorResume(ex -> {
                log.warn("热榜加载文章失败：{}", postName, ex);
                return Mono.empty();
            });
    }

    private Mono<RankConfigDTO> loadRankConfig() {
        return settingFetcher.get(RankConfigHelper.groupName())
            .map(RankConfigHelper::parse)
            .defaultIfEmpty(RankConfigHelper.defaults());
    }

    private Mono<LevelConfigDTO> loadLevelConfig() {
        return settingFetcher.get(LevelConfigHelper.groupName())
            .map(LevelConfigHelper::parse)
            .defaultIfEmpty(LevelConfigHelper.defaults());
    }

    private Mono<List<RankedUser>> loadUserBoard(RankConfigDTO config, String type, String range) {
        var cacheKey = type + "|" + range + "|" + config.isShowScore();
        var cached = userRankCache.get(cacheKey);
        if (cached != null && cached.expireAt().isAfter(Instant.now())) {
            return Mono.just(cached.users());
        }
        return aggregateUserBoard(type, range, config)
            .doOnNext(users -> userRankCache.put(cacheKey, new CachedBoard(
                Instant.now().plus(USER_RANK_CACHE_TTL), users)));
    }

    private Mono<List<RankedUser>> aggregateUserBoard(String type, String range, RankConfigDTO config) {
        var windowStart = USER_RANK_RANGE_WEEK.equals(range)
            ? Instant.now().minus(USER_RANK_ACTIVE_DAYS, ChronoUnit.DAYS)
            : null;
        return Mono.zip(
                wxUserService.listAllUsers().defaultIfEmpty(List.of()),
                pointsPersistence.listAllRecords().defaultIfEmpty(List.of()),
                pointsPersistence.listAllAccounts().defaultIfEmpty(List.of()),
                loadLevelConfig())
            .map(tuple -> buildUserBoard(type, windowStart, null, tuple.getT1(), tuple.getT2(),
                tuple.getT3(), tuple.getT4(), config.isShowScore()));
    }

    /**
     * 聚合榜单：积分榜按累计获得积分，活跃榜按窗口内获得积分，邀请榜按邀请人数。
     *
     * <p>只统计数值大于 0 的用户，避免零分账号刷屏；名次按数值倒序回填。</p>
     *
     * @param windowStart 活跃榜统计窗口起点，null 表示不启用窗口（积分榜/邀请榜）
     * @param windowEnd   窗口终点（含起点、不含终点），null 表示到今天为止
     */
    private List<RankedUser> buildUserBoard(String type, Instant windowStart, Instant windowEnd,
        List<WxUser> users, List<PointsRecord> records, List<UserPoints> accounts,
        LevelConfigDTO levelConfig, boolean showScore) {
        var totalByOpenid = new HashMap<String, Long>();
        var windowByOpenid = new HashMap<String, Long>();
        for (var record : records) {
            if (record == null) {
                continue;
            }
            var openid = trim(record.getWxOpenid());
            var points = record.getPoints() == null ? 0 : Math.max(0, record.getPoints());
            if (openid.isEmpty() || points == 0) {
                continue;
            }
            totalByOpenid.merge(openid, (long) points, Long::sum);
            if (windowStart != null) {
                var recordTime = resolveRecordTime(record);
                if (recordTime != null && recordTime.isAfter(windowStart)
                    && (windowEnd == null || recordTime.isBefore(windowEnd))) {
                    windowByOpenid.merge(openid, (long) points, Long::sum);
                }
            }
        }
        var inviteByOpenid = new HashMap<String, Long>();
        for (var user : users) {
            if (user == null) {
                continue;
            }
            var inviter = trim(user.getInviterOpenid());
            if (!inviter.isEmpty()) {
                inviteByOpenid.merge(inviter, 1L, Long::sum);
            }
        }
        var accountByOpenid = indexAccounts(accounts);

        var entries = new ArrayList<RankedUser>();
        for (var user : users) {
            if (user == null) {
                continue;
            }
            var openid = trim(user.getOpenid());
            if (openid.isEmpty()) {
                continue;
            }
            var value = switch (type) {
                case USER_RANK_TYPE_INVITE -> inviteByOpenid.getOrDefault(openid, 0L);
                case USER_RANK_TYPE_ACTIVE -> windowByOpenid.getOrDefault(openid, 0L);
                default -> totalByOpenid.getOrDefault(openid, 0L);
            };
            if (value <= 0) {
                continue;
            }
            var account = accountByOpenid.get(openid);
            var leveled = LevelConfigHelper.resolveLevel(levelConfig,
                totalByOpenid.getOrDefault(openid, 0L));
            entries.add(new RankedUser(openid, value, UserRankItemDTO.builder()
                .nickname(resolveNickname(user))
                .avatar(user.getAvatarUrl())
                .avatarFrame(validAvatarFrame(account))
                .nickFrame(validNickFrame(account))
                .level(leveled.level())
                .levelTitle(leveled.title())
                .value(showScore ? value : 0L)
                .build()));
        }

        entries.sort(Comparator.comparingLong(RankedUser::value).reversed()
            .thenComparing(entry -> resolveNickname(entry.item().getNickname())));
        for (var index = 0; index < entries.size(); index++) {
            entries.get(index).item().setRank(index + 1);
        }
        return List.copyOf(entries);
    }

    private List<UserActivityFeedItemDTO> buildActivityFeed(RankConfigDTO config,
        List<UserActivity> activities, List<WxUser> users, List<UserPoints> accounts, int limit) {
        var userByOpenid = new HashMap<String, WxUser>();
        for (var user : users) {
            if (user == null) {
                continue;
            }
            var openid = trim(user.getOpenid());
            if (!openid.isEmpty()) {
                userByOpenid.put(openid, user);
            }
        }
        var accountByOpenid = indexAccounts(accounts);
        // 同一用户同一动作只保留最近一条，避免一个人的连续操作刷满整面墙
        var seen = new HashSet<String>();
        var items = new ArrayList<UserActivityFeedItemDTO>();
        activities.stream()
            .filter(activity -> activity != null && StringUtils.hasText(activity.getWxOpenid()))
            .filter(activity -> isWallActivity(activity, config))
            .sorted(Comparator.comparing(RankServiceImpl::activityTime,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .forEach(activity -> {
                if (items.size() >= limit) {
                    return;
                }
                var openid = trim(activity.getWxOpenid());
                var user = userByOpenid.get(openid);
                if (user == null || !seen.add(openid + "|" + trim(activity.getType()))) {
                    return;
                }
                var account = accountByOpenid.get(openid);
                var time = activityTime(activity);
                items.add(UserActivityFeedItemDTO.builder()
                    .nickname(resolveNickname(user))
                    .avatar(user.getAvatarUrl())
                    .avatarFrame(validAvatarFrame(account))
                    .nickFrame(validNickFrame(account))
                    .type(trim(activity.getType()).toLowerCase())
                    .actionText(actionTextOf(activity.getType()))
                    .contentType(StringUtils.hasText(activity.getContentType())
                        ? activity.getContentType()
                        : "post")
                    .targetName(activity.getArticleName())
                    .title(activity.getTitle())
                    .excerpt(trimExcerpt(activity.getExcerpt()))
                    .time(time == null ? null : time.truncatedTo(ChronoUnit.SECONDS).toString())
                    .build());
            });
        return List.copyOf(items);
    }

    private UserRankResponse.Me resolveMe(List<RankedUser> users, String openid, RankConfigDTO config) {
        var normalized = trim(openid);
        if (normalized.isEmpty()) {
            return null;
        }
        return users.stream()
            .filter(user -> normalized.equals(user.openid()))
            .findFirst()
            .map(user -> UserRankResponse.Me.builder()
                .rank(user.item().getRank())
                .value(config.isShowScore() ? user.value() : 0L)
                .joined(true)
                .build())
            .orElseGet(() -> UserRankResponse.Me.builder()
                .rank(0)
                .value(0)
                .joined(false)
                .build());
    }

    private static UserRankResponse emptyUserRank(String type, String range) {
        return UserRankResponse.builder()
            .type(type)
            .range(range)
            .items(List.of())
            .total(0)
            .me(null)
            .build();
    }

    private static Map<String, UserPoints> indexAccounts(List<UserPoints> accounts) {
        var indexed = new HashMap<String, UserPoints>();
        for (var account : accounts) {
            if (account == null) {
                continue;
            }
            var openid = trim(account.getWxOpenid());
            if (!openid.isEmpty()) {
                indexed.put(openid, account);
            }
        }
        return indexed;
    }

    /** 头像框仅在「持有且未过期」时下发，避免限时装扮过期后仍在榜单上显示 */
    private static String validAvatarFrame(UserPoints account) {
        return validOutfit(account, account == null ? null : account.getActiveAvatarFrame());
    }

    private static String validNickFrame(UserPoints account) {
        return validOutfit(account, account == null ? null : account.getActiveNickFrame());
    }

    private static String validOutfit(UserPoints account, String key) {
        var normalized = trim(key);
        if (account == null || normalized.isEmpty()) {
            return null;
        }
        var owned = OwnedOutfitParser.parse(account.getOutfitOwned());
        return OwnedOutfitParser.isOwnedAndValid(owned, normalized) ? normalized : null;
    }

    private static boolean isWallActivity(UserActivity activity, RankConfigDTO config) {
        var type = trim(activity.getType()).toLowerCase();
        if (!WALL_ACTIVITY_TYPES.contains(type)) {
            return false;
        }
        if (ACTIVITY_TYPE_UPVOTE.equals(type) && !config.isWallIncludeUpvote()) {
            return false;
        }
        return StringUtils.hasText(activity.getTitle())
            && StringUtils.hasText(activity.getArticleName());
    }

    private static String actionTextOf(String type) {
        return switch (trim(type).toLowerCase()) {
            case ACTIVITY_TYPE_COMMENT -> "评论了";
            case ACTIVITY_TYPE_UPVOTE -> "赞了";
            case ACTIVITY_TYPE_VOTE -> "投票了";
            case ACTIVITY_TYPE_REACTION -> "互动了";
            default -> "浏览了";
        };
    }

    private static String trimExcerpt(String excerpt) {
        var normalized = trim(excerpt);
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    private static Instant activityTime(UserActivity activity) {
        if (activity.getRecordTime() != null) {
            return activity.getRecordTime();
        }
        return activity.getMetadata() == null ? null : activity.getMetadata().getCreationTimestamp();
    }

    private static Instant resolveRecordTime(PointsRecord record) {
        if (record.getCreateTime() != null) {
            return record.getCreateTime();
        }
        return record.getMetadata() == null ? null : record.getMetadata().getCreationTimestamp();
    }

    /** 上一自然周（周一 00:00 ~ 本周一 00:00，Asia/Shanghai）与它的 ISO 周标识（如 2026-W36） */
    private static WeekWindow resolvePreviousWeek() {
        var thisMonday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var lastMonday = thisMonday.minusWeeks(1);
        var weekKey = "%d-W%02d".formatted(lastMonday.get(IsoFields.WEEK_BASED_YEAR),
            lastMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        return new WeekWindow(weekKey, lastMonday.atStartOfDay(ZONE).toInstant(),
            thisMonday.atStartOfDay(ZONE).toInstant());
    }

    private static String resolveNickname(WxUser user) {
        return user == null ? DEFAULT_NICKNAME : resolveNickname(user.getNickname());
    }

    private static String resolveNickname(String nickname) {
        return StringUtils.hasText(nickname) ? nickname.trim() : DEFAULT_NICKNAME;
    }

    private static String normalizeUserRankType(String type) {
        var normalized = trim(type).toLowerCase();
        return switch (normalized) {
            case USER_RANK_TYPE_ACTIVE -> USER_RANK_TYPE_ACTIVE;
            case USER_RANK_TYPE_INVITE -> USER_RANK_TYPE_INVITE;
            default -> USER_RANK_TYPE_POINTS;
        };
    }

    /**
     * 邀请榜是累计关系（恒为 all），活跃榜本身就是近 7 日榜（恒为 week，
     * 否则会出现「active + all」这种取不到窗口数据、榜单为空的组合）；
     * 积分榜按请求区分全部/近 7 日。
     */
    private static String normalizeUserRankRange(String range, String type) {
        if (USER_RANK_TYPE_INVITE.equals(type)) {
            return USER_RANK_RANGE_ALL;
        }
        if (USER_RANK_TYPE_ACTIVE.equals(type)) {
            return USER_RANK_RANGE_WEEK;
        }
        return USER_RANK_RANGE_WEEK.equals(trim(range).toLowerCase())
            ? USER_RANK_RANGE_WEEK
            : USER_RANK_RANGE_ALL;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 榜单内部排序用的中间结构，openid 仅用于定位「我的排名」与发放奖励，不下发到响应 */
    private record RankedUser(String openid, long value, UserRankItemDTO item) {
    }

    private record CachedBoard(Instant expireAt, List<RankedUser> users) {
    }

    private record WeekWindow(String weekKey, Instant from, Instant to) {
    }
}
