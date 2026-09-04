package pro.uhalo.uni.services;

import pro.uhalo.uni.enums.UserContentType;
import reactor.core.publisher.Mono;

public interface UpvoteTrackerService {

    Mono<Void> submitUpvote(String resourceName, UserContentType contentType);

    Mono<Void> cancelUpvote(String resourceName, UserContentType contentType);
}
