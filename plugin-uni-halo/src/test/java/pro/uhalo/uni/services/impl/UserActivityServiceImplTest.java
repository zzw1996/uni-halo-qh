package pro.uhalo.uni.services.impl;



import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;

import org.mockito.InjectMocks;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;

import pro.uhalo.uni.dto.RecordUserActivityRequest;

import pro.uhalo.uni.enums.UserContentType;

import pro.uhalo.uni.exception.WxAuthException;

import pro.uhalo.uni.infra.PointsPersistence;

import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.utils.WxUserLookup;

import pro.uhalo.uni.scheme.WxUser;

import pro.uhalo.uni.services.UpvoteTrackerService;

import pro.uhalo.uni.services.WxUserService;

import reactor.core.publisher.Flux;

import reactor.core.publisher.Mono;

import reactor.test.StepVerifier;

import run.halo.app.extension.ListOptions;

import run.halo.app.extension.Metadata;

import run.halo.app.extension.ReactiveExtensionClient;

import run.halo.app.extension.exception.ExtensionException;



import java.time.Instant;



import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)

class UserActivityServiceImplTest {



    @Mock

    private ReactiveExtensionClient client;



    @Mock

    private WxUserService wxUserService;



    @Mock

    private UpvoteTrackerService upvoteTrackerService;



    @Mock

    private PointsPersistence pointsPersistence;



    @InjectMocks

    private UserActivityServiceImpl userActivityService;



    private WxUser wxUser;

    private UserActivity existingActivity;



    @BeforeEach

    void setUp() {

        wxUser = new WxUser();

        wxUser.setId("user-1");

        wxUser.setOpenid("openid-123");



        existingActivity = new UserActivity();

        existingActivity.setWxUserId("user-1");

        existingActivity.setWxOpenid("openid-123");

        existingActivity.setType("comment");

        existingActivity.setContentType("post");

        existingActivity.setArticleName("post-1");

        existingActivity.setActivityKey(

            UserActivityServiceImpl.buildActivityKey("openid-123", "comment", "post-1",

                UserContentType.POST));

        existingActivity.setTitle("旧标题");

        existingActivity.setCover("https://example.com/old.png");

        existingActivity.setExcerpt("旧摘要");

        existingActivity.setVisit(0);

        existingActivity.setRecordTime(Instant.parse("2026-01-01T00:00:00Z"));

        var metadata = new Metadata();

        metadata.setName("activity-1");

        existingActivity.setMetadata(metadata);

    }



    @Test

    void recordActivityShouldFailWhenOpenidMissing() {

        StepVerifier.create(userActivityService.recordActivity(null, new RecordUserActivityRequest()))

            .expectErrorMatches(ex -> ex instanceof WxAuthException

                && "缺少 Wechat-Session-Id".equals(((WxAuthException) ex).getReason()))

            .verify();

    }



    @Test

    void recordActivityShouldCreateWhenNotExists() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());

        when(client.create(any(UserActivity.class))).thenAnswer(invocation -> {

            var activity = invocation.getArgument(0, UserActivity.class);

            return Mono.just(activity);

        });



        var request = new RecordUserActivityRequest();

        request.setType("browse");

        request.setArticleName("post-2");

        request.setTitle("标题");

        request.setCover("https://example.com/cover.png");

        request.setExcerpt("摘要");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "post-2".equals(dto.getArticleName())

                && "post".equals(dto.getContentType())

                && "标题".equals(dto.getTitle())

                && dto.getRecordTime() != null)

            .verifyComplete();



        var captor = ArgumentCaptor.forClass(UserActivity.class);

        verify(client).create(captor.capture());

        assertThat(captor.getValue().getWxUserId()).isEqualTo("user-1");

        assertThat(captor.getValue().getType()).isEqualTo("browse");

        assertThat(captor.getValue().getContentType()).isEqualTo("post");

    }



    @Test

    void recordActivityShouldCreateUpvoteAndSubmitTracker() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());

        when(client.create(any(UserActivity.class))).thenAnswer(invocation -> {

            var activity = invocation.getArgument(0, UserActivity.class);

            return Mono.just(activity);

        });

        when(upvoteTrackerService.submitUpvote("post-2", UserContentType.POST)).thenReturn(Mono.empty());



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setArticleName("post-2");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "post-2".equals(dto.getArticleName()))

            .verifyComplete();



        verify(upvoteTrackerService).submitUpvote("post-2", UserContentType.POST);

    }



    @Test

    void recordActivityShouldCreateMomentUpvoteAndSubmitMomentTracker() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());

        when(client.create(any(UserActivity.class))).thenAnswer(invocation -> {

            var activity = invocation.getArgument(0, UserActivity.class);

            return Mono.just(activity);

        });

        when(upvoteTrackerService.submitUpvote("moment-1", UserContentType.MOMENT)).thenReturn(Mono.empty());



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setContentType("moment");

        request.setArticleName("moment-1");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "moment-1".equals(dto.getArticleName())

                && "moment".equals(dto.getContentType()))

            .verifyComplete();



        verify(upvoteTrackerService).submitUpvote("moment-1", UserContentType.MOMENT);

    }



    @Test

    void recordActivityShouldBeIdempotentForUpvoteDuplicate() {

        existingActivity.setType("upvote");

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setArticleName("post-1");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "post-1".equals(dto.getArticleName()))

            .verifyComplete();



        verify(client, never()).update(any());

        verify(client, never()).create(any());

        verify(upvoteTrackerService, never()).submitUpvote(any(), any());

    }



    @Test

    void getUpvoteStatusShouldReturnTrueWhenExists() {

        existingActivity.setType("upvote");

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));



        StepVerifier.create(userActivityService.getUpvoteStatus("openid-123", "post-1", UserContentType.POST))

            .expectNextMatches(response -> response.isUpvoted())

            .verifyComplete();

    }



    @Test

    void getUpvoteStatusShouldReturnFalseWhenNotExists() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());



        StepVerifier.create(userActivityService.getUpvoteStatus("openid-123", "post-1", UserContentType.POST))

            .expectNextMatches(response -> !response.isUpvoted())

            .verifyComplete();

    }



    @Test

    void getUpvoteStatusShouldDistinguishMomentFromPost() {

        var momentActivity = new UserActivity();

        momentActivity.setWxOpenid("openid-123");

        momentActivity.setType("upvote");

        momentActivity.setContentType("moment");

        momentActivity.setArticleName("shared-name");

        momentActivity.setActivityKey(UserActivityServiceImpl.buildActivityKey(

            "openid-123", "upvote", "shared-name", UserContentType.MOMENT));



        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(momentActivity));



        StepVerifier.create(userActivityService.getUpvoteStatus("openid-123", "shared-name",

                UserContentType.MOMENT))

            .expectNextMatches(response -> response.isUpvoted())

            .verifyComplete();



        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());



        StepVerifier.create(userActivityService.getUpvoteStatus("openid-123", "shared-name",

                UserContentType.POST))

            .expectNextMatches(response -> !response.isUpvoted())

            .verifyComplete();

    }



    @Test

    void cancelActivityShouldDeleteUpvoteAndCancelTracker() {

        existingActivity.setType("upvote");

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));

        when(client.delete(existingActivity)).thenReturn(Mono.just(existingActivity));

        when(upvoteTrackerService.cancelUpvote("post-1", UserContentType.POST)).thenReturn(Mono.empty());



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setArticleName("post-1");



        StepVerifier.create(userActivityService.cancelActivity("openid-123", request))

            .expectNextMatches(response -> response.isSuccess() && !response.isUpvoted())

            .verifyComplete();



        verify(client).delete(existingActivity);

        verify(upvoteTrackerService).cancelUpvote("post-1", UserContentType.POST);

    }



    @Test

    void cancelActivityShouldCancelMomentTracker() {

        existingActivity.setType("upvote");

        existingActivity.setContentType("moment");

        existingActivity.setArticleName("moment-1");

        existingActivity.setActivityKey(UserActivityServiceImpl.buildActivityKey(

            "openid-123", "upvote", "moment-1", UserContentType.MOMENT));



        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));

        when(client.delete(existingActivity)).thenReturn(Mono.just(existingActivity));

        when(upvoteTrackerService.cancelUpvote("moment-1", UserContentType.MOMENT)).thenReturn(Mono.empty());



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setContentType("moment");

        request.setArticleName("moment-1");



        StepVerifier.create(userActivityService.cancelActivity("openid-123", request))

            .expectNextMatches(response -> response.isSuccess())

            .verifyComplete();



        verify(upvoteTrackerService).cancelUpvote("moment-1", UserContentType.MOMENT);

    }



    @Test

    void cancelActivityShouldBeIdempotentWhenUpvoteNotExists() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());



        var request = new RecordUserActivityRequest();

        request.setType("upvote");

        request.setArticleName("post-1");



        StepVerifier.create(userActivityService.cancelActivity("openid-123", request))

            .expectNextMatches(response -> response.isSuccess() && !response.isUpvoted())

            .verifyComplete();



        verify(client, never()).delete(any());

        verify(upvoteTrackerService, never()).cancelUpvote(any(), any());

    }



    @Test

    void recordActivityShouldOnlyUpdateRecordTimeForCommentDuplicate() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));

        when(client.update(existingActivity)).thenReturn(Mono.just(existingActivity));



        var request = new RecordUserActivityRequest();

        request.setType("comment");

        request.setArticleName("post-1");

        request.setTitle("新标题");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "post-1".equals(dto.getArticleName())

                && "旧标题".equals(dto.getTitle()))

            .verifyComplete();



        assertThat(existingActivity.getTitle()).isEqualTo("旧标题");

        assertThat(existingActivity.getRecordTime()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));

    }



    @Test

    void recordActivityShouldUpdateMetadataForBrowseDuplicate() {

        existingActivity.setType("browse");

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity));

        when(client.update(existingActivity)).thenReturn(Mono.just(existingActivity));



        var request = new RecordUserActivityRequest();

        request.setType("browse");

        request.setArticleName("post-1");

        request.setTitle("新标题");

        request.setExcerpt("新摘要");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "新标题".equals(dto.getTitle())

                && "新摘要".equals(dto.getExcerpt()))

            .verifyComplete();

    }



    @Test

    void listActivitiesShouldReturnPagedResultWithContentType() {

        var activity2 = new UserActivity();

        activity2.setArticleName("moment-2");

        activity2.setType("upvote");

        activity2.setContentType("moment");

        activity2.setRecordTime(Instant.parse("2026-06-01T00:00:00Z"));

        activity2.setVisit(3);



        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.just(existingActivity, activity2));



        StepVerifier.create(userActivityService.listActivities("openid-123", null, 0, 10))

            .expectNextMatches(response -> response.getTotal() == 2

                && response.getItems().size() == 2

                && "moment-2".equals(response.getItems().get(0).getArticleName())

                && "moment".equals(response.getItems().get(0).getContentType())

                && "upvote".equals(response.getItems().get(0).getType())

                && !response.isHasNext())

            .verifyComplete();

    }



    @Test

    void listActivitiesShouldFailWhenUserNotFound() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.empty());



        StepVerifier.create(userActivityService.listActivities("openid-123", "browse", 0, 10))

            .expectErrorMatches(ex -> ex instanceof WxAuthException

                && "用户不存在，请先登录".equals(((WxAuthException) ex).getReason()))

            .verify();



        verify(client, never()).listAll(any(), any(), any());

    }



    @Test

    void recordActivityShouldMapExtensionExceptionToWxAuthException() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.empty());

        when(client.create(any(UserActivity.class)))

            .thenReturn(Mono.error(new ExtensionException("create failed")));



        var request = new RecordUserActivityRequest();

        request.setType("browse");

        request.setArticleName("post-2");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectErrorMatches(ex -> ex instanceof WxAuthException

                && "记录行为失败".equals(((WxAuthException) ex).getReason()))

            .verify();

    }



    @Test

    void recordActivityShouldFallbackToOpenidFilterWhenActivityKeyQueryFails() {

        when(wxUserService.fetchByOpenid("openid-123")).thenReturn(Mono.just(wxUser));

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))

            .thenReturn(Flux.error(new ExtensionException("index error")))

            .thenReturn(Flux.just(existingActivity));

        when(client.update(existingActivity)).thenReturn(Mono.just(existingActivity));



        var request = new RecordUserActivityRequest();

        request.setType("comment");

        request.setArticleName("post-1");



        StepVerifier.create(userActivityService.recordActivity("openid-123", request))

            .expectNextMatches(dto -> "post-1".equals(dto.getArticleName()))

            .verifyComplete();

    }

    @Test
    void listAllActivitiesShouldFilterByArticleTitleKeyword() {
        var wxUser = new WxUser();
        wxUser.setOpenid("openid-123");
        wxUser.setNickname("测试昵称");
        wxUser.setHaloUserName("halo-user");

        var activity = new UserActivity();
        activity.setWxOpenid("openid-123");
        activity.setType("browse");
        activity.setTitle("Spring Boot 入门");
        activity.setArticleName("post-1");
        activity.setRecordTime(Instant.now());

        when(client.listAll(eq(UserActivity.class), any(ListOptions.class), eq(null)))
            .thenReturn(Flux.just(activity));
        when(wxUserService.loadUserLookup())
            .thenReturn(Mono.just(WxUserLookup.from(java.util.List.of(wxUser), java.util.List.of())));
        when(pointsPersistence.listAllRecords()).thenReturn(Mono.just(java.util.List.of()));

        StepVerifier.create(userActivityService.listAllActivities(null, "Spring", 0, 20))
            .expectNextMatches(response -> response.getTotal() == 1
                && "halo-user".equals(response.getItems().get(0).getHaloUserName())
                && "Spring Boot 入门".equals(response.getItems().get(0).getTitle()))
            .verifyComplete();
    }

}

