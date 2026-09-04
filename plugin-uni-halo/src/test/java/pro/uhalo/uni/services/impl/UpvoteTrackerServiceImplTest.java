package pro.uhalo.uni.services.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.uhalo.uni.enums.UserContentType;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Counter;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpvoteTrackerServiceImplTest {

    @Mock
    private ReactiveExtensionClient client;

    @InjectMocks
    private UpvoteTrackerServiceImpl upvoteTrackerService;

    @Test
    void meterNameOfShouldFollowPostCounterPattern() {
        assertThat(UpvoteTrackerServiceImpl.meterNameOf("post-1", UserContentType.POST))
            .isEqualTo("posts.content.halo.run/post-1");
    }

    @Test
    void meterNameOfShouldFollowMomentCounterPattern() {
        assertThat(UpvoteTrackerServiceImpl.meterNameOf("moment-1", UserContentType.MOMENT))
            .isEqualTo("moments.moment.halo.run/moment-1");
    }

    @Test
    void submitUpvoteShouldIncrementExistingCounter() {
        var counter = new Counter();
        counter.setUpvote(2);
        var metadata = new Metadata();
        metadata.setName("posts.content.halo.run/post-1");
        counter.setMetadata(metadata);

        when(client.fetch(eq(Counter.class), eq("posts.content.halo.run/post-1")))
            .thenReturn(Mono.just(counter));
        when(client.update(counter)).thenReturn(Mono.just(counter));

        StepVerifier.create(upvoteTrackerService.submitUpvote("post-1", UserContentType.POST))
            .verifyComplete();

        assertThat(counter.getUpvote()).isEqualTo(3);
        verify(client).update(counter);
        verify(client, never()).create(any());
    }

    @Test
    void submitUpvoteShouldCreateMomentCounterWhenMissing() {
        when(client.fetch(eq(Counter.class), eq("moments.moment.halo.run/moment-1")))
            .thenReturn(Mono.empty());
        when(client.create(any(Counter.class))).thenAnswer(invocation -> {
            var created = invocation.getArgument(0, Counter.class);
            return Mono.just(created);
        });

        StepVerifier.create(upvoteTrackerService.submitUpvote("moment-1", UserContentType.MOMENT))
            .verifyComplete();

        verify(client).create(any(Counter.class));
    }

    @Test
    void cancelUpvoteShouldDecrementExistingCounter() {
        var counter = new Counter();
        counter.setUpvote(2);
        var metadata = new Metadata();
        metadata.setName("moments.moment.halo.run/moment-1");
        counter.setMetadata(metadata);

        when(client.fetch(eq(Counter.class), eq("moments.moment.halo.run/moment-1")))
            .thenReturn(Mono.just(counter));
        when(client.update(counter)).thenReturn(Mono.just(counter));

        StepVerifier.create(upvoteTrackerService.cancelUpvote("moment-1", UserContentType.MOMENT))
            .verifyComplete();

        assertThat(counter.getUpvote()).isEqualTo(1);
    }

    @Test
    void cancelUpvoteShouldNotGoBelowZero() {
        var counter = new Counter();
        counter.setUpvote(0);
        var metadata = new Metadata();
        metadata.setName("posts.content.halo.run/post-1");
        counter.setMetadata(metadata);

        when(client.fetch(eq(Counter.class), eq("posts.content.halo.run/post-1")))
            .thenReturn(Mono.just(counter));
        when(client.update(counter)).thenReturn(Mono.just(counter));

        StepVerifier.create(upvoteTrackerService.cancelUpvote("post-1", UserContentType.POST))
            .verifyComplete();

        assertThat(counter.getUpvote()).isEqualTo(0);
    }
}
