package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminWxUserItemDTO;
import pro.uhalo.uni.dto.WxUserListResponse;
import pro.uhalo.uni.scheme.UserActivity;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.scheme.WxUser;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

import static run.halo.app.extension.index.query.QueryFactory.equal;

@Service
@RequiredArgsConstructor
public class WxUserServiceImpl implements WxUserService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ReactiveExtensionClient client;

    @Override
    public Mono<WxUser> fetchByOpenid(String openid) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(equal("openid", openid)));
        return client.listAll(WxUser.class, listOptions, null).next();
    }

    @Override
    public Mono<WxUser> create(WxUser wxUser) {
        var metadata = new Metadata();
        var name = StringUtils.hasText(wxUser.getId())
            ? wxUser.getId()
            : UUID.randomUUID().toString();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.now());
        wxUser.setMetadata(metadata);
        wxUser.setId(name);
        return client.create(wxUser);
    }

    @Override
    public Mono<WxUser> update(WxUser wxUser) {
        return client.update(wxUser);
    }

    @Override
    public Mono<WxUserListResponse> listUsers(String keyword, int page, int size) {
        var normalizedPage = Math.max(page, DEFAULT_PAGE);
        var normalizedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        var normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;

        return client.listAll(WxUser.class, new ListOptions(), null)
            .filter(user -> matchesKeyword(user, normalizedKeyword))
            .collectList()
            .map(users -> toPageResponse(users, normalizedPage, normalizedSize));
    }

    @Override
    public Mono<WxUserLookup> loadUserLookup() {
        return Mono.zip(
                client.listAll(WxUser.class, new ListOptions(), null).collectList(),
                client.listAll(UserPoints.class, new ListOptions(), null).collectList(),
                client.listAll(UserActivity.class, new ListOptions(), null).collectList())
            .map(tuple -> WxUserLookup.from(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private static boolean matchesKeyword(WxUser user, String keyword) {
        if (keyword == null) {
            return true;
        }
        return containsIgnoreCase(user.getOpenid(), keyword)
            || containsIgnoreCase(user.getNickname(), keyword)
            || containsIgnoreCase(user.getHaloUserName(), keyword)
            || containsIgnoreCase(user.getUnionid(), keyword)
            || containsIgnoreCase(user.getId(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private WxUserListResponse toPageResponse(java.util.List<WxUser> users, int page, int size) {
        var sorted = users.stream()
            .sorted(Comparator.comparing(
                user -> user.getMetadata() != null ? user.getMetadata().getCreationTimestamp() : null,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        var total = sorted.size();
        var from = Math.min(page * size, total);
        var to = Math.min(from + size, total);
        var items = sorted.subList(from, to).stream().map(this::toAdminDto).toList();
        return WxUserListResponse.builder()
            .items(items)
            .total(total)
            .hasNext(to < total)
            .page(page)
            .size(size)
            .build();
    }

    private AdminWxUserItemDTO toAdminDto(WxUser user) {
        var metadata = user.getMetadata();
        return AdminWxUserItemDTO.builder()
            .id(user.getId())
            .openid(user.getOpenid())
            .unionid(user.getUnionid())
            .nickname(user.getNickname())
            .avatarUrl(user.getAvatarUrl())
            .profileComplete(user.getProfileComplete())
            .profileModifyCount(user.getProfileModifyCount())
            .haloUserName(user.getHaloUserName())
            .creationTimestamp(metadata != null ? metadata.getCreationTimestamp() : null)
            .build();
    }
}
