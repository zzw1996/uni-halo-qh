package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.services.PointsEvidenceService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * {@link PointsEvidenceService} 实现：基于插件服务端已有数据做行为核验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PointsEvidenceServiceImpl implements PointsEvidenceService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 与 UserActivityServiceImpl.ACTIVITY_KEY_SEP 保持一致 */
    static final String ACTIVITY_KEY_SEP = "\u0001";

    private final ReactiveExtensionClient client;

    @Override
    public Mono<Boolean> evidenceReady(String openid, String action, String articleName) {
        if (!StringUtils.hasText(openid) || !StringUtils.hasText(action)
            || !StringUtils.hasText(articleName)) {
            return Mono.just(false);
        }
        var normalizedAction = action.trim().toLowerCase();
        var normalizedName = articleName.trim();
        return switch (normalizedAction) {
            case "browse" -> postExists(normalizedName)
                .flatMap(exists -> exists ? activityToday(openid, "browse", normalizedName) : Mono.just(false));
            case "comment" -> commentToday(openid, normalizedName);
            case "upvote" -> activityToday(openid, "upvote", normalizedName);
            default -> Mono.just(false);
        };
    }

    private Mono<Boolean> postExists(String articleName) {
        return client.fetch(Post.class, articleName)
            .map(post -> Boolean.TRUE)
            .defaultIfEmpty(Boolean.FALSE)
            .onErrorResume(ex -> {
                log.warn("查询文章存在性失败: {}: {}", articleName, ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<Boolean> activityToday(String openid, String type, String articleName) {
        var activityKey = buildActivityKey(openid, type, articleName);
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("activityKey", activityKey)));
        return client.listAll(UserActivity.class, listOptions, null).next()
            .map(activity -> isAfterStartOfToday(resolveActivityTime(activity)))
            .defaultIfEmpty(false)
            .onErrorResume(ex -> {
                log.warn("查询行为记录失败: activityKey={}: {}", activityKey, ex.getMessage());
                return Mono.just(false);
            });
    }

    /**
     * 评论证据：由评论代理在服务端创建（metadata.annotations 含微信 openid），
     * 校验主题为对应文章且创建时间为「今天」。
     */
    private Mono<Boolean> commentToday(String openid, String articleName) {
        return client.listAll(Comment.class, new ListOptions(), null)
            .filter(comment -> comment != null && comment.getMetadata() != null
                && comment.getSpec() != null && comment.getSpec().getSubjectRef() != null)
            .filter(comment -> openid.equals(annotation(comment, Constants.UNIHALO_OPENID_ANNOTATION)))
            .filter(comment -> "Post".equals(comment.getSpec().getSubjectRef().getKind())
                || "post".equalsIgnoreCase(comment.getSpec().getSubjectRef().getKind()))
            .filter(comment -> articleName.equals(comment.getSpec().getSubjectRef().getName()))
            .filter(comment -> isAfterStartOfToday(resolveCommentTime(comment)))
            .next()
            .map(ignored -> Boolean.TRUE)
            .defaultIfEmpty(Boolean.FALSE)
            .onErrorResume(ex -> {
                log.warn("查询评论证据失败: openid={}, article={}: {}", openid, articleName, ex.getMessage());
                return Mono.just(false);
            });
    }

    private static String annotation(Comment comment, String key) {
        var annotations = comment.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(key);
    }

    static String buildActivityKey(String openid, String type, String articleName) {
        return openid + ACTIVITY_KEY_SEP + type + ACTIVITY_KEY_SEP + articleName;
    }

    private static Instant resolveActivityTime(UserActivity activity) {
        if (activity.getRecordTime() != null) {
            return activity.getRecordTime();
        }
        return activity.getMetadata() == null ? null : activity.getMetadata().getCreationTimestamp();
    }

    private static Instant resolveCommentTime(Comment comment) {
        if (comment.getSpec().getCreationTime() != null) {
            return comment.getSpec().getCreationTime();
        }
        return comment.getMetadata().getCreationTimestamp();
    }

    static Instant startOfToday() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    static boolean isAfterStartOfToday(Instant time) {
        return time != null && !time.isBefore(startOfToday());
    }
}
