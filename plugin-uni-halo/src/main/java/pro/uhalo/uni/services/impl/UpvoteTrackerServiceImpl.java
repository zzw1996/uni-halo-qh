package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.enums.UserContentType;
import pro.uhalo.uni.services.UpvoteTrackerService;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpvoteTrackerServiceImpl implements UpvoteTrackerService {

    private final ReactiveExtensionClient client;

    @Override
    public Mono<Void> submitUpvote(String resourceName, UserContentType contentType) {
        return adjustUpvote(resourceName, contentType, 1);
    }

    @Override
    public Mono<Void> cancelUpvote(String resourceName, UserContentType contentType) {
        return adjustUpvote(resourceName, contentType, -1);
    }

    private Mono<Void> adjustUpvote(String resourceName, UserContentType contentType, int delta) {
        if (!StringUtils.hasText(resourceName) || contentType == null) {
            return Mono.empty();
        }
        var counterName = meterNameOf(resourceName.trim(), contentType);
        return client.fetch(Counter.class, counterName)
            .flatMap(counter -> updateUpvote(counter, delta))
            .switchIfEmpty(Mono.defer(() -> {
                if (delta <= 0) {
                    return Mono.empty();
                }
                return createCounter(counterName, delta);
            }))
            .then()
            .onErrorResume(ex -> {
                log.warn("adjust upvote failed, resourceName={}, contentType={}, delta={}: {}",
                    resourceName, contentType.getValue(), delta, ex.getMessage());
                return Mono.empty();
            });
    }

    private Mono<Counter> updateUpvote(Counter counter, int delta) {
        var next = Math.max(0, safeInt(counter.getUpvote()) + delta);
        counter.setUpvote(next);
        return client.update(counter);
    }

    private Mono<Counter> createCounter(String counterName, int upvote) {
        var counter = new Counter();
        counter.setVisit(0);
        counter.setUpvote(upvote);
        counter.setDownvote(0);
        counter.setTotalComment(0);
        counter.setApprovedComment(0);

        var metadata = new Metadata();
        metadata.setName(counterName);
        counter.setMetadata(metadata);
        return client.create(counter);
    }

    static String meterNameOf(String resourceName, UserContentType contentType) {
        return contentType.counterMeterName(resourceName);
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
