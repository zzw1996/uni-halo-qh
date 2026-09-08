package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.dto.RecordUserActivityRequest;
import pro.uhalo.uni.enums.UserContentType;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.services.UpvoteTrackerService;
import pro.uhalo.uni.services.WxUserService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserActivityReactionTest {

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private WxUserService wxUserService;

    @Mock
    private UpvoteTrackerService upvoteTrackerService;

    @Mock
    private PointsPersistence pointsPersistence;

    private UserActivityServiceImpl newService() {
        return new UserActivityServiceImpl(client, wxUserService, upvoteTrackerService,
            pointsPersistence);
    }

    private static UserActivity reaction(String openid, String contentType, String articleName,
        String kind) {
        var activity = new UserActivity();
        activity.setWxUserId("u-" + openid);
        activity.setWxOpenid(openid);
        activity.setType("reaction");
        activity.setContentType(contentType);
        activity.setArticleName(articleName);
        activity.setReactionKind(kind);
        activity.setActivityKey(UserActivityServiceImpl.buildReactionKey(openid,
            UserContentType.parse(contentType), articleName, kind));
        activity.setRecordTime(Instant.now());
        var metadata = new Metadata();
        metadata.setName("ua-" + openid + "-" + kind);
        metadata.setCreationTimestamp(Instant.now());
        activity.setMetadata(metadata);
        return activity;
    }

    @Test
    void buildReactionKeyShouldBeUniqueByKindOnlyForReaction() {
        var key = UserActivityServiceImpl.buildReactionKey("o1", UserContentType.POST, "a1", "love");
        assertThat(key).contains("reaction").contains("a1").contains("love");
        assertThat(UserActivityServiceImpl.buildReactionKey("o1", UserContentType.POST, "a1", "haha"))
            .isNotEqualTo(key);
    }

    @Test
    void recordReactionShouldCreateWhenAbsent() {
        var service = newService();
        var wxUser = new pro.uhalo.uni.scheme.WxUser();
        wxUser.setId("u-o1");
        wxUser.setOpenid("o1");
        when(wxUserService.fetchByOpenid("o1")).thenReturn(Mono.just(wxUser));
        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.empty());
        var created = reaction("o1", "post", "a1", "love");
        when(client.create(any(UserActivity.class))).thenReturn(Mono.just(created));

        var request = new RecordUserActivityRequest();
        request.setType("reaction");
        request.setContentType("post");
        request.setArticleName("a1");
        request.setReactionKind("love");

        StepVerifier.create(service.recordReaction("o1", request))
            .consumeNextWith(item -> {
                assertThat(item.getArticleName()).isEqualTo("a1");
            })
            .verifyComplete();
    }

    @Test
    void recordReactionShouldDeleteOldBeforeCreateNew() {
        var service = newService();
        var wxUser = new pro.uhalo.uni.scheme.WxUser();
        wxUser.setId("u-o1");
        wxUser.setOpenid("o1");
        when(wxUserService.fetchByOpenid("o1")).thenReturn(Mono.just(wxUser));
        var old = reaction("o1", "post", "a1", "love");
        var switched = reaction("o1", "post", "a1", "haha");
        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.empty())
            .thenReturn(Flux.just(old));
        when(client.delete(any(UserActivity.class))).thenReturn(Mono.empty());
        when(client.create(any(UserActivity.class))).thenReturn(Mono.just(switched));

        var request = new RecordUserActivityRequest();
        request.setType("reaction");
        request.setContentType("post");
        request.setArticleName("a1");
        request.setReactionKind("haha");

        StepVerifier.create(service.recordReaction("o1", request))
            .consumeNextWith(item -> {
                assertThat(item.getType()).isEqualTo("reaction");
                assertThat(item.getArticleName()).isEqualTo("a1");
            })
            .verifyComplete();
    }

    @Test
    void getReactionStatusShouldAggregateCountsAndMine() {
        var service = newService();
        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.just(
                reaction("o1", "post", "a1", "love"),
                reaction("o2", "post", "a1", "love"),
                reaction("o3", "post", "a1", "haha"),
                reaction("o4", "post", "a1", "love")));

        StepVerifier.create(service.getReactionStatus("o1", "a1", UserContentType.POST))
            .consumeNextWith(status -> {
                Map<String, Long> expected = new LinkedHashMap<>();
                expected.put("love", 3L);
                expected.put("haha", 1L);
                assertThat(status.getCounts()).isEqualTo(expected);
                assertThat(status.getMine()).isEqualTo("love");
            })
            .verifyComplete();
    }

    @Test
    void getReactionStatusShouldHandleAnonymousMineEmpty() {
        var service = newService();
        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.just(
                reaction("o2", "post", "a1", "love")));

        StepVerifier.create(service.getReactionStatus(null, "a1", UserContentType.POST))
            .consumeNextWith(status -> {
                assertThat(status.getCounts()).isEqualTo(Map.of("love", 1L));
                assertThat(status.getMine()).isNull();
            })
            .verifyComplete();
    }
}
