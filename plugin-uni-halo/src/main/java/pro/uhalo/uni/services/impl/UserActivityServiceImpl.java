package pro.uhalo.uni.services.impl;



import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;

import pro.uhalo.uni.dto.AdminUserActivityItemDTO;
import pro.uhalo.uni.dto.AdminUserActivityListResponse;
import pro.uhalo.uni.dto.CancelUpvoteResponse;

import pro.uhalo.uni.dto.ContentReactionStatusDTO;

import pro.uhalo.uni.dto.RecordUserActivityRequest;

import pro.uhalo.uni.dto.UpvoteStatusResponse;

import pro.uhalo.uni.dto.UserActivityItemDTO;

import pro.uhalo.uni.dto.UserActivityListResponse;

import pro.uhalo.uni.enums.UserActivityType;

import pro.uhalo.uni.enums.UserContentType;

import pro.uhalo.uni.exception.WxAuthException;

import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.PointsRecord;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.WxUser;

import pro.uhalo.uni.services.UpvoteTrackerService;

import pro.uhalo.uni.services.UserActivityService;

import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.PointsRecordHelper;
import pro.uhalo.uni.utils.WxUserLookup;

import reactor.core.publisher.Mono;

import run.halo.app.extension.ListOptions;

import run.halo.app.extension.Metadata;

import run.halo.app.extension.ReactiveExtensionClient;

import run.halo.app.extension.exception.ExtensionException;

import run.halo.app.extension.router.selector.FieldSelector;



import java.time.Instant;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;



import static run.halo.app.extension.index.query.QueryFactory.equal;



@Service

@RequiredArgsConstructor

@Slf4j

public class UserActivityServiceImpl implements UserActivityService {



    private static final int DEFAULT_PAGE = 0;

    private static final int DEFAULT_SIZE = 10;

    private static final int MAX_SIZE = 100;

    private static final String ACTIVITY_KEY_SEP = "\u0001";



    private final ReactiveExtensionClient client;

    private final WxUserService wxUserService;

    private final UpvoteTrackerService upvoteTrackerService;

    private final PointsPersistence pointsPersistence;



    @Override

    public Mono<UserActivityItemDTO> recordActivity(String openid, RecordUserActivityRequest request) {

        return Mono.fromCallable(() -> {

            assertOpenid(openid);

            return validateRecordRequest(request);

        }).flatMap(activityRequest -> {

            var activityType = UserActivityType.parse(activityRequest.getType());

            var contentType = UserContentType.parse(activityRequest.getContentType());

            return wxUserService.fetchByOpenid(openid.trim())

                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))

                .flatMap(wxUser -> fetchExisting(wxUser.getOpenid(), activityType.value(),

                    activityRequest.getArticleName(), contentType)

                    .flatMap(existing -> handleExistingActivity(existing, activityRequest, activityType))

                    .switchIfEmpty(Mono.defer(() -> createActivity(wxUser.getId(), wxUser.getOpenid(),

                        activityRequest, activityType, contentType)

                        .flatMap(activity -> {

                            if (activityType == UserActivityType.UPVOTE) {

                                return upvoteTrackerService.submitUpvote(activityRequest.getArticleName(),

                                        contentType)

                                    .thenReturn(activity);

                            }

                            return Mono.just(activity);

                        }))));

        }).map(activity -> toItemDto(activity, List.of()));

    }



    @Override

    public Mono<UpvoteStatusResponse> getUpvoteStatus(String openid, String articleName,

                                                      UserContentType contentType) {

        return Mono.fromCallable(() -> {

            assertOpenid(openid);

            if (!StringUtils.hasText(articleName)) {

                throw new WxAuthException("缺少 articleName 参数");

            }

            return articleName.trim();

        }).flatMap(normalizedArticleName -> wxUserService.fetchByOpenid(openid.trim())

            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))

            .flatMap(wxUser -> fetchExisting(wxUser.getOpenid(), UserActivityType.UPVOTE.value(),

                normalizedArticleName, contentType)

                .map(existing -> UpvoteStatusResponse.builder().upvoted(true).build())

                .defaultIfEmpty(UpvoteStatusResponse.builder().upvoted(false).build())));

    }



    @Override

    public Mono<CancelUpvoteResponse> cancelActivity(String openid, RecordUserActivityRequest request) {

        return Mono.fromCallable(() -> {

            assertOpenid(openid);

            return validateCancelRequest(request);

        })

            .flatMap(cancelRequest -> {

                var contentType = UserContentType.parse(cancelRequest.getContentType());

                return wxUserService.fetchByOpenid(openid.trim())

                    .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))

                    .flatMap(wxUser -> fetchExisting(wxUser.getOpenid(), UserActivityType.UPVOTE.value(),

                        cancelRequest.getArticleName(), contentType)

                        .flatMap(activity -> client.delete(activity)

                            .then(upvoteTrackerService.cancelUpvote(cancelRequest.getArticleName(), contentType))

                            .thenReturn(CancelUpvoteResponse.cancelled()))

                        .switchIfEmpty(Mono.just(CancelUpvoteResponse.cancelled())));

            });

    }



    @Override
    public Mono<UserActivityItemDTO> recordReaction(String openid, RecordUserActivityRequest request) {
        return Mono.fromCallable(() -> {
            assertOpenid(openid);
            return validateReactionRequest(request);
        }).flatMap(reactionRequest -> {
            var contentType = UserContentType.parse(reactionRequest.getContentType());
            return wxUserService.fetchByOpenid(openid.trim())
                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                .flatMap(wxUser -> {
                    var normalizedKind = reactionRequest.getReactionKind().trim();
                    var reactionKey = buildReactionKey(wxUser.getOpenid(), contentType,
                        reactionRequest.getArticleName(), normalizedKind);
                    return fetchByActivityKey(reactionKey)
                        .flatMap(existing -> {
                            existing.setRecordTime(Instant.now());
                            return client.update(existing);
                        })
                        .switchIfEmpty(Mono.defer(() -> deleteUserReactions(wxUser.getOpenid(),
                                reactionRequest.getArticleName(), contentType)
                            .then(createReaction(wxUser.getId(), wxUser.getOpenid(),
                                reactionRequest, contentType, normalizedKind, reactionKey))));
                });
        }).map(activity -> toItemDto(activity, List.of()));
    }

    @Override
    public Mono<CancelUpvoteResponse> cancelReaction(String openid, RecordUserActivityRequest request) {
        return Mono.fromCallable(() -> {
            assertOpenid(openid);
            return validateReactionCancelRequest(request);
        }).flatMap(cancelRequest -> {
            var contentType = UserContentType.parse(cancelRequest.getContentType());
            return wxUserService.fetchByOpenid(openid.trim())
                .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))
                .flatMap(wxUser -> deleteUserReactions(wxUser.getOpenid(),
                    cancelRequest.getArticleName(), contentType)
                    .thenReturn(CancelUpvoteResponse.cancelled()));
        });
    }

    @Override
    public Mono<ContentReactionStatusDTO> getReactionStatus(String openid, String articleName,
        UserContentType contentType) {
        return Mono.fromCallable(() -> {
            if (!StringUtils.hasText(articleName)) {
                throw new WxAuthException("缺少 articleName 参数");
            }
            return new ReactionQuery(StringUtils.hasText(openid) ? openid.trim() : "",
                articleName.trim(), contentType);
        }).flatMap(query -> client.listAll(UserActivity.class, new ListOptions(), null)
            .filter(activity -> UserActivityType.REACTION.value().equals(activity.getType()))
            .filter(activity -> query.articleName().equals(activity.getArticleName()))
            .filter(activity -> contentTypeMatches(activity, query.contentType()))
            .collectList()
            .map(activities -> {
                var counts = new java.util.LinkedHashMap<String, Long>();
                var mine = "";
                for (var activity : activities) {
                    var kind = StringUtils.hasText(activity.getReactionKind())
                        ? activity.getReactionKind() : "unknown";
                    counts.merge(kind, 1L, Long::sum);
                    if (!query.openid().isEmpty() && query.openid().equals(activity.getWxOpenid())) {
                        mine = activity.getReactionKind();
                    }
                }
                return ContentReactionStatusDTO.builder()
                    .counts(counts)
                    .mine(StringUtils.hasText(mine) ? mine : null)
                    .build();
            }));
    }

    private static RecordUserActivityRequest validateReactionRequest(RecordUserActivityRequest request) {
        if (request == null) {
            throw new WxAuthException("请求体格式错误");
        }
        if (UserActivityType.parse(request.getType()) != UserActivityType.REACTION) {
            throw new WxAuthException("type 须为 reaction");
        }
        if (!StringUtils.hasText(request.getArticleName())) {
            throw new WxAuthException("缺少 articleName 参数");
        }
        if (!StringUtils.hasText(request.getReactionKind())) {
            throw new WxAuthException("缺少 reactionKind 参数");
        }
        request.setArticleName(request.getArticleName().trim());
        request.setReactionKind(request.getReactionKind().trim());
        if (request.getReactionKind().length() > 20) {
            throw new WxAuthException("reactionKind 过长");
        }
        UserContentType.parse(request.getContentType());
        return request;
    }

    private static RecordUserActivityRequest validateReactionCancelRequest(
        RecordUserActivityRequest request) {
        if (request == null) {
            throw new WxAuthException("请求体格式错误");
        }
        if (UserActivityType.parse(request.getType()) != UserActivityType.REACTION) {
            throw new WxAuthException("type 须为 reaction");
        }
        if (!StringUtils.hasText(request.getArticleName())) {
            throw new WxAuthException("缺少 articleName 参数");
        }
        request.setArticleName(request.getArticleName().trim());
        UserContentType.parse(request.getContentType());
        return request;
    }

    private Mono<Void> deleteUserReactions(String openid, String articleName,
        UserContentType contentType) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));
        return client.listAll(UserActivity.class, listOptions, null)
            .filter(activity -> UserActivityType.REACTION.value().equals(activity.getType()))
            .filter(activity -> articleName.equals(activity.getArticleName()))
            .filter(activity -> contentTypeMatches(activity, contentType))
            .concatMap(client::delete)
            .then();
    }

    private Mono<UserActivity> createReaction(String wxUserId, String wxOpenid,
        RecordUserActivityRequest request, UserContentType contentType, String kind,
        String activityKey) {
        var activity = new UserActivity();
        activity.setWxUserId(wxUserId);
        activity.setWxOpenid(wxOpenid);
        activity.setType(UserActivityType.REACTION.value());
        activity.setContentType(contentType.getValue());
        activity.setArticleName(request.getArticleName());
        activity.setActivityKey(activityKey);
        activity.setReactionKind(kind);
        applyMetadata(activity, request);
        activity.setRecordTime(Instant.now());
        if (activity.getVisit() == null) {
            activity.setVisit(0);
        }
        var metadata = new Metadata();
        metadata.setName("ua-" + UUID.randomUUID());
        metadata.setCreationTimestamp(Instant.now());
        activity.setMetadata(metadata);
        return client.create(activity).onErrorMap(this::mapExtensionError);
    }

    private record ReactionQuery(String openid, String articleName, UserContentType contentType) {
    }

    @Override

    public Mono<UserActivityListResponse> listActivities(String openid, String type, int page, int size) {

        return Mono.fromCallable(() -> {

            assertOpenid(openid);

            if (StringUtils.hasText(type)) {

                UserActivityType.parse(type);

            }

            return new ListQuery(openid.trim(), type, Math.max(page, DEFAULT_PAGE),

                Math.min(Math.max(size, 1), MAX_SIZE));

        }).flatMap(query -> wxUserService.fetchByOpenid(query.openid())

            .switchIfEmpty(Mono.error(new WxAuthException("用户不存在，请先登录")))

            .flatMap(wxUser -> Mono.zip(
                listByOpenid(wxUser.getOpenid(), query.type()),
                pointsPersistence.listRecordsByOpenid(wxUser.getOpenid()))
                .map(tuple -> toPageResponse(tuple.getT1(), tuple.getT2(),
                    query.page(), query.size()))));

    }



    @Override

    public Mono<AdminUserActivityListResponse> listAllActivities(String type, String keyword, int page, int size) {

        return Mono.fromCallable(() -> {

            if (StringUtils.hasText(type)) {

                UserActivityType.parse(type);

            }

            return new AdminListQuery(

                StringUtils.hasText(type) ? type.trim().toLowerCase() : null,

                StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null,

                Math.max(page, DEFAULT_PAGE),

                Math.min(Math.max(size, 1), MAX_SIZE));

        }).flatMap(query -> Mono.zip(

            client.listAll(UserActivity.class, new ListOptions(), null)

                .filter(activity -> query.type() == null || query.type().equals(activity.getType()))

                .collectList(),

            wxUserService.loadUserLookup(),

            pointsPersistence.listAllRecords())

            .map(tuple -> {

                var activities = tuple.getT1();

                var userLookup = tuple.getT2();

                var records = tuple.getT3();

                var filtered = activities.stream()

                    .filter(activity -> matchesActivityKeyword(activity,

                        userLookup.resolve(activity.getWxOpenid(), activity.getWxUserId()), query.keyword()))

                    .toList();

                return toAdminPageResponse(filtered, userLookup, records, query.page(), query.size());

            }));

    }



    private record ListQuery(String openid, String type, int page, int size) {

    }



    private record AdminListQuery(String type, String keyword, int page, int size) {

    }



    private Mono<UserActivity> fetchExisting(String openid, String type, String articleName,

        UserContentType contentType) {

        var activityKey = buildActivityKey(openid, type, articleName, contentType);

        return fetchByActivityKey(activityKey)

            .onErrorResume(ex -> {

                log.warn("fetchExisting by activityKey failed, fallback to wxOpenid filter: {}",

                    ex.getMessage());

                return fetchByOpenidAndFilter(openid, type, articleName, contentType);

            });

    }



    private Mono<UserActivity> fetchByActivityKey(String activityKey) {

        var listOptions = new ListOptions();

        listOptions.setFieldSelector(FieldSelector.of(equal("activityKey", activityKey)));

        return client.listAll(UserActivity.class, listOptions, null).next();

    }



    private Mono<UserActivity> fetchByOpenidAndFilter(String openid, String type, String articleName,

        UserContentType contentType) {

        var listOptions = new ListOptions();

        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));

        return client.listAll(UserActivity.class, listOptions, null)

            .filter(activity -> type.equals(activity.getType())

                && articleName.equals(activity.getArticleName())

                && contentTypeMatches(activity, contentType))

            .next();

    }



    private Mono<UserActivity> createActivity(String wxUserId, String wxOpenid,

        RecordUserActivityRequest request, UserActivityType activityType, UserContentType contentType) {

        var activity = new UserActivity();

        activity.setWxUserId(wxUserId);

        activity.setWxOpenid(wxOpenid);

        activity.setType(activityType.value());

        activity.setContentType(contentType.getValue());

        activity.setArticleName(request.getArticleName());

        activity.setActivityKey(buildActivityKey(wxOpenid, activityType.value(), request.getArticleName(),

            contentType));

        applyMetadata(activity, request);

        activity.setRecordTime(Instant.now());

        if (activity.getVisit() == null) {

            activity.setVisit(0);

        }



        var metadata = new Metadata();

        metadata.setName(UUID.randomUUID().toString());

        metadata.setCreationTimestamp(Instant.now());

        activity.setMetadata(metadata);

        return client.create(activity).onErrorMap(this::mapExtensionError);

    }



    private Mono<UserActivity> handleExistingActivity(UserActivity activity,

        RecordUserActivityRequest request, UserActivityType activityType) {

        if (activityType == UserActivityType.UPVOTE) {

            return Mono.just(activity);

        }

        return updateActivity(activity, request, activityType);

    }



    private Mono<UserActivity> updateActivity(UserActivity activity, RecordUserActivityRequest request,

        UserActivityType activityType) {

        ensureActivityKey(activity);

        activity.setRecordTime(Instant.now());

        if (activityType == UserActivityType.BROWSE) {

            applyMetadata(activity, request);

        }

        return client.update(activity).onErrorMap(this::mapExtensionError);

    }



    private Mono<List<UserActivity>> listByOpenid(String openid, String type) {

        var listOptions = new ListOptions();

        listOptions.setFieldSelector(FieldSelector.of(equal("wxOpenid", openid)));

        var normalizedType = StringUtils.hasText(type) ? type.trim().toLowerCase() : null;

        return client.listAll(UserActivity.class, listOptions, null)

            .filter(activity -> normalizedType == null || normalizedType.equals(activity.getType()))

            .collectList();

    }



    private Mono<List<UserActivity>> listAllFiltered(String type, String openid) {

        return client.listAll(UserActivity.class, new ListOptions(), null)

            .filter(activity -> type == null || type.equals(activity.getType()))

            .filter(activity -> openid == null || openid.equals(activity.getWxOpenid()))

            .collectList();

    }

    private static boolean matchesActivityKeyword(UserActivity activity, WxUser wxUser, String keyword) {
        if (keyword == null) {
            return true;
        }
        if (containsIgnoreCase(activity.getWxOpenid(), keyword)) {
            return true;
        }
        if (containsIgnoreCase(activity.getTitle(), keyword)) {
            return true;
        }
        if (containsIgnoreCase(activity.getArticleName(), keyword)) {
            return true;
        }
        if (containsIgnoreCase(activity.getExcerpt(), keyword)) {
            return true;
        }
        if (wxUser == null) {
            return false;
        }
        return containsIgnoreCase(wxUser.getNickname(), keyword)
            || containsIgnoreCase(wxUser.getHaloUserName(), keyword)
            || containsIgnoreCase(wxUser.getUnionid(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }



    private AdminUserActivityListResponse toAdminPageResponse(List<UserActivity> activities,

        WxUserLookup userLookup, List<PointsRecord> records, int page, int size) {

        var sorted = activities.stream()

            .sorted(Comparator.comparing(UserActivity::getRecordTime,

                Comparator.nullsLast(Comparator.reverseOrder())))

            .toList();

        var total = sorted.size();

        var from = Math.min(page * size, total);

        var to = Math.min(from + size, total);

        var items = sorted.subList(from, to).stream()

            .map(activity -> toAdminItemDto(activity, userLookup, records))

            .toList();

        return AdminUserActivityListResponse.builder()

            .items(items)

            .total(total)

            .hasNext(to < total)

            .page(page)

            .size(size)

            .build();

    }



    private AdminUserActivityItemDTO toAdminItemDto(UserActivity activity, WxUserLookup userLookup,

        List<PointsRecord> records) {

        var wxUser = userLookup.resolve(activity.getWxOpenid(), activity.getWxUserId());

        var contentType = activity.getContentType();

        if (!StringUtils.hasText(contentType)) {

            contentType = UserContentType.POST.getValue();

        }

        return AdminUserActivityItemDTO.builder()

            .wxUserId(activity.getWxUserId())

            .wxOpenid(activity.getWxOpenid())

            .nickname(PointsRecordHelper.resolveDisplayName(wxUser, activity.getWxOpenid()))

            .avatarUrl(wxUser != null ? wxUser.getAvatarUrl() : null)

            .haloUserName(wxUser != null ? wxUser.getHaloUserName() : null)

            .type(activity.getType())

            .contentType(contentType)

            .articleName(activity.getArticleName())

            .title(activity.getTitle())

            .cover(activity.getCover())

            .excerpt(activity.getExcerpt())

            .publishTime(activity.getPublishTime())

            .visit(activity.getVisit() != null ? activity.getVisit() : 0)

            .recordTime(activity.getRecordTime())

            .recordTimeText(PointsRecordHelper.formatInstant(resolveActivityTime(activity)))

            .pointsEarned(resolveActivityPoints(activity, records))

            .build();

    }



    /**
     * 行为记录对应的领取积分数量：先按「同日精确匹配」earnKey，
     * 匹配失败回退为该用户该行为该文章最近一次领分流水（时间被刷新/跨天领取也能正确显示）。
     * 从未领取过返回 null（前端展示为空/未获得），避免误显示 0。
     */
    static Integer resolveActivityPoints(UserActivity activity, List<PointsRecord> records) {

        if (records == null || records.isEmpty()

            || activity == null

            || !StringUtils.hasText(activity.getWxOpenid())

            || !StringUtils.hasText(activity.getType())

            || !StringUtils.hasText(activity.getArticleName())) {

            return null;

        }

        var openid = activity.getWxOpenid().trim();

        var type = activity.getType().trim().toLowerCase();

        if (!UserActivityType.BROWSE.value().equals(type)

            && !UserActivityType.COMMENT.value().equals(type)

            && !UserActivityType.UPVOTE.value().equals(type)) {

            return null;

        }

        var articleName = activity.getArticleName().trim();

        var recordTime = resolveActivityTime(activity);

        // 1) 同日精确匹配：行为发生/刷新当天领取的积分
        var earnKey = PointsServiceImpl.buildDailyEarnKey(

            openid, type, articleName,

            PointsServiceImpl.recordDate(recordTime));

        for (var record : records) {

            if (earnKey.equals(record.getEarnKey())) {

                return record.getPoints();

            }

        }

        // 2) 回退：该用户该行为该文章最近一次领分流水
        return records.stream()

            .filter(record -> openid.equals(record.getWxOpenid())

                && type.equals(record.getAction())

                && StringUtils.hasText(record.getRefName())

                && articleName.equals(record.getRefName().trim()))

            .max(Comparator.comparing(PointsRecord::getCreateTime,

                Comparator.nullsLast(Comparator.naturalOrder())))

            .map(PointsRecord::getPoints)

            .orElse(null);

    }

    private static Instant resolveActivityTime(UserActivity activity) {

        if (activity.getRecordTime() != null) {

            return activity.getRecordTime();

        }

        return activity.getMetadata() == null ? null : activity.getMetadata().getCreationTimestamp();

    }



    private static void ensureActivityKey(UserActivity activity) {

        if (!StringUtils.hasText(activity.getActivityKey())

            && StringUtils.hasText(activity.getWxOpenid())

            && StringUtils.hasText(activity.getType())

            && StringUtils.hasText(activity.getArticleName())) {

            activity.setActivityKey(buildActivityKey(

                activity.getWxOpenid(), activity.getType(), activity.getArticleName(),

                UserContentType.parse(activity.getContentType())));

        }

    }



    static String buildActivityKey(String openid, String type, String articleName,

        UserContentType contentType) {

        if (contentType == UserContentType.POST) {

            return openid + ACTIVITY_KEY_SEP + type + ACTIVITY_KEY_SEP + articleName;

        }

        return openid + ACTIVITY_KEY_SEP + type + ACTIVITY_KEY_SEP + contentType.getValue()

            + ACTIVITY_KEY_SEP + articleName;

    }

    /** 表情反馈唯一键：始终带 contentType 与 kind，与旧行为记录 key 格式互不干扰 */
    static String buildReactionKey(String openid, UserContentType contentType, String articleName,
        String kind) {
        return openid + ACTIVITY_KEY_SEP + UserActivityType.REACTION.value() + ACTIVITY_KEY_SEP
            + contentType.getValue() + ACTIVITY_KEY_SEP + articleName + ACTIVITY_KEY_SEP + kind;
    }



    private static boolean contentTypeMatches(UserActivity activity, UserContentType contentType) {

        return UserContentType.parse(activity.getContentType()) == contentType;

    }



    private WxAuthException mapExtensionError(Throwable ex) {

        log.warn("UserActivity extension operation failed", ex);

        return new WxAuthException("记录行为失败");

    }



    private static void applyMetadata(UserActivity activity, RecordUserActivityRequest request) {

        if (StringUtils.hasText(request.getTitle())) {

            activity.setTitle(request.getTitle().trim());

        }

        if (StringUtils.hasText(request.getCover())) {

            activity.setCover(request.getCover().trim());

        }

        if (StringUtils.hasText(request.getExcerpt())) {

            activity.setExcerpt(request.getExcerpt().trim());

        }

    }



    private static RecordUserActivityRequest validateCancelRequest(RecordUserActivityRequest request) {

        if (request == null) {

            throw new WxAuthException("请求体格式错误");

        }

        var activityType = UserActivityType.parse(request.getType());

        if (activityType != UserActivityType.UPVOTE) {

            throw new WxAuthException("仅支持取消 upvote 行为");

        }

        if (!StringUtils.hasText(request.getArticleName())) {

            throw new WxAuthException("缺少 articleName 参数");

        }

        request.setArticleName(request.getArticleName().trim());

        UserContentType.parse(request.getContentType());

        return request;

    }



    private static RecordUserActivityRequest validateRecordRequest(RecordUserActivityRequest request) {

        if (request == null) {

            throw new WxAuthException("请求体格式错误");

        }

        UserActivityType.parse(request.getType());

        if (!StringUtils.hasText(request.getArticleName())) {

            throw new WxAuthException("缺少 articleName 参数");

        }

        request.setArticleName(request.getArticleName().trim());

        UserContentType.parse(request.getContentType());

        return request;

    }



    private static void assertOpenid(String openid) {

        if (!StringUtils.hasText(openid)) {

            throw new WxAuthException("缺少 Wechat-Session-Id");

        }

    }



    private UserActivityListResponse toPageResponse(List<UserActivity> activities,
        List<PointsRecord> records, int page, int size) {

        var sorted = activities.stream()

            .sorted(Comparator.comparing(UserActivity::getRecordTime,

                Comparator.nullsLast(Comparator.reverseOrder())))

            .toList();

        var total = sorted.size();

        var from = Math.min(page * size, total);

        var to = Math.min(from + size, total);

        var items = sorted.subList(from, to).stream().map(activity -> toItemDto(activity, records)).toList();

        return UserActivityListResponse.builder()

            .items(items)

            .total(total)

            .hasNext(to < total)

            .page(page)

            .size(size)

            .build();

    }



    private UserActivityItemDTO toItemDto(UserActivity activity, List<PointsRecord> records) {

        var contentType = activity.getContentType();

        if (!StringUtils.hasText(contentType)) {

            contentType = UserContentType.POST.getValue();

        }

        return UserActivityItemDTO.builder()

            .type(activity.getType())

            .contentType(contentType)

            .articleName(activity.getArticleName())

            .title(activity.getTitle())

            .cover(activity.getCover())

            .excerpt(activity.getExcerpt())

            .publishTime(activity.getPublishTime())

            .visit(activity.getVisit() != null ? activity.getVisit() : 0)

            .recordTime(activity.getRecordTime())

            .recordTimeText(PointsRecordHelper.formatInstant(resolveActivityTime(activity)))

            .pointsEarned(resolveActivityPoints(activity, records))

            .build();

    }

}

