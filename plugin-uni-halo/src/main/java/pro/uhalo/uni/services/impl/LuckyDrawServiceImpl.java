package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AdminLuckyWinningItemDTO;
import pro.uhalo.uni.dto.AdminLuckyWinningListResponse;
import pro.uhalo.uni.dto.LuckyClaimRequest;
import pro.uhalo.uni.dto.LuckyClaimResponse;
import pro.uhalo.uni.dto.LuckyConfigDTO;
import pro.uhalo.uni.dto.LuckyDrawResponse;
import pro.uhalo.uni.dto.LuckyPrizeDTO;
import pro.uhalo.uni.dto.LuckyShipRequest;
import pro.uhalo.uni.dto.LuckyShipResponse;
import pro.uhalo.uni.dto.LuckyWinningUpdateRequest;
import pro.uhalo.uni.dto.MyLuckyWinningDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.LuckyWinning;
import pro.uhalo.uni.services.LuckyDrawService;
import pro.uhalo.uni.services.WxUserService;
import pro.uhalo.uni.utils.LuckyDrawConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxUserIds;
import pro.uhalo.uni.utils.WxUserLookup;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 幸运抽奖实现：扣积分与出奖写为一条 PointsRecord（action=lucky_draw，
 * points=-cost，refName=奖品名），积分明细可见；奖品配置需要地址时
 * 生成 LuckyWinning 中奖单，用户提交收货信息后由站长人工发货。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LuckyDrawServiceImpl implements LuckyDrawService {

    private static final String ACTION = "lucky_draw";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_SUBMITTED = "submitted";
    private static final String STATUS_SHIPPED = "shipped";
    private static final int DRAW_RATE_LIMIT_PER_MINUTE = 10;
    private static final int ADMIN_MAX_SIZE = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PointsPersistence persistence;
    private final WxUserService wxUserService;
    private final ReactiveSettingFetcher settingFetcher;
    private final RateLimiter rateLimiter;
    private final ReactiveExtensionClient client;

    @Override
    public Mono<LuckyConfigDTO> getConfig() {
        return loadConfig();
    }

    @Override
    public Mono<LuckyDrawResponse> draw(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                ensureRateLimit(id, "lucky-draw", DRAW_RATE_LIMIT_PER_MINUTE, "抽奖过于频繁，请稍后再试");
                return loadConfig()
                    .flatMap(config -> {
                        if (!config.isEnabled()) {
                            return Mono.error(new WxAuthException("幸运抽奖未开启"));
                        }
                        return persistence.getOrCreateAccount(id)
                            .flatMap(account -> {
                                var balance = safeBalance(account);
                                var cost = config.getCostPoints();
                                if (balance < cost) {
                                    return Mono.error(new WxAuthException(
                                        "积分不足，抽奖需 " + cost + " 积分"));
                                }
                                var prize = pickPrize(config.getPrizes());
                                var won = prize != null && !"感谢参与".equals(prize.getName())
                                    && !"再来一次".equals(prize.getName());
                                var needsAddress = won && prize != null && prize.isNeedsAddress();
                                var newBalance = balance - cost;
                                return resolveWxUserId(id, account)
                                    .flatMap(wxUserId -> {
                                        account.setBalance(newBalance);
                                        if (StringUtils.hasText(wxUserId)
                                            && !wxUserId.equals(account.getWxUserId())) {
                                            account.setWxUserId(wxUserId);
                                        }
                                        var description = prize == null
                                            ? "积分抽奖"
                                            : "积分抽奖·" + prize.getName();
                                        var record = persistence.newRecord(id, ACTION, -cost,
                                            newBalance, description,
                                            prize == null ? "" : prize.getName(),
                                            PointsServiceImpl.buildEarnKey(id, ACTION,
                                                String.valueOf(System.nanoTime())),
                                            wxUserId);
                                        java.util.function.Function<LuckyWinning, LuckyDrawResponse> buildResponse =
                                            (LuckyWinning winning) -> LuckyDrawResponse.builder()
                                            .won(won)
                                            .name(prize == null ? "" : prize.getName())
                                            .emoji(prize == null ? "🙏" : prize.getEmoji())
                                            .winningId(winning == null ? null
                                                : (winning.getMetadata() == null ? null
                                                    : winning.getMetadata().getName()))
                                            .needsAddress(needsAddress)
                                            .balance(newBalance)
                                            .message(won
                                                ? "恭喜抽中「" + prize.getName() + "」"
                                                : "谢谢参与")
                                            .build();
                                        return (needsAddress
                                            ? createWinning(id, wxUserId, prize)
                                            : Mono.<LuckyWinning>empty())
                                            .flatMap(winning -> persistence.saveAccount(account)
                                                .then(persistence.appendRecord(record))
                                                .thenReturn(buildResponse.apply(winning)))
                                            .switchIfEmpty(Mono.defer(() -> persistence.saveAccount(account)
                                                .then(persistence.appendRecord(record))
                                                .thenReturn(buildResponse.apply(null))));
                                    });
                            });
                    });
            });
    }

    private Mono<LuckyWinning> createWinning(String openid, String wxUserId, LuckyPrizeDTO prize) {
        var winning = new LuckyWinning();
        winning.setWxOpenid(openid);
        winning.setWxUserId(wxUserId == null ? "" : wxUserId);
        winning.setPrizeName(prize.getName());
        winning.setEmoji(prize.getEmoji());
        winning.setStatus(STATUS_PENDING);
        winning.setWinTime(Instant.now());
        var metadata = new Metadata();
        metadata.setName("lw-" + UUID.randomUUID());
        metadata.setCreationTimestamp(Instant.now());
        winning.setMetadata(metadata);
        return client.create(winning);
    }

    @Override
    public Mono<LuckyClaimResponse> submitClaim(String openid, LuckyClaimRequest request) {
        return requireOpenid(openid)
            .flatMap(id -> {
                if (request == null || !StringUtils.hasText(request.getWinningId())) {
                    return Mono.error(new WxAuthException("winningId 不能为空"));
                }
                var contactName = StringUtils.hasText(request.getContactName())
                    ? request.getContactName().trim() : "";
                var phone = StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : "";
                var address = StringUtils.hasText(request.getAddress()) ? request.getAddress().trim() : "";
                if (contactName.length() > 32) {
                    return Mono.error(new WxAuthException("联系人姓名过长"));
                }
                if (!phone.matches("^1[0-9]{10}$")) {
                    return Mono.error(new WxAuthException("手机号格式不正确"));
                }
                if (address.length() < 5 || address.length() > 200) {
                    return Mono.error(new WxAuthException("请填写详细的收货地址"));
                }
                var winningId = request.getWinningId().trim();
                return client.fetch(LuckyWinning.class, winningId)
                    .switchIfEmpty(Mono.error(new WxAuthException("中奖记录不存在")))
                    .flatMap(winning -> {
                        if (!id.equals(winning.getWxOpenid())) {
                            return Mono.error(new WxAuthException("无权操作该中奖记录"));
                        }
                        if (!STATUS_PENDING.equals(winning.getStatus())) {
                            return Mono.error(new WxAuthException("该中奖信息已提交，请勿重复提交"));
                        }
                        winning.setContactName(contactName);
                        winning.setPhone(phone);
                        winning.setAddress(address);
                        winning.setStatus(STATUS_SUBMITTED);
                        winning.setSubmitTime(Instant.now());
                        return client.update(winning)
                            .map(updated -> LuckyClaimResponse.builder()
                                .ok(true)
                                .message("收货信息已提交，奖品将由站长安排发放")
                                .build());
                    });
            });
    }

    @Override
    public Mono<List<MyLuckyWinningDTO>> getMyClaims(String openid) {
        return requireOpenid(openid)
            .flatMap(id -> {
                var listOptions = new ListOptions();
                listOptions.setFieldSelector(run.halo.app.extension.router.selector.FieldSelector.of(
                    run.halo.app.extension.index.query.QueryFactory.equal("wxOpenid", id)));
                return client.listAll(LuckyWinning.class, listOptions, null)
                    .collectList()
                    .map(winnings -> winnings.stream()
                        .map(winning -> MyLuckyWinningDTO.builder()
                            .winningId(winning.getMetadata() == null ? null
                                : winning.getMetadata().getName())
                            .prizeName(winning.getPrizeName())
                            .emoji(winning.getEmoji())
                            .status(winning.getStatus())
                            .winTime(winning.getWinTime())
                            .contactName(winning.getContactName())
                            .phone(winning.getPhone())
                            .address(winning.getAddress())
                            .expressCompany(winning.getExpressCompany())
                            .expressNo(winning.getExpressNo())
                            .shipTime(winning.getShipTime())
                            .build())
                        .toList());
            });
    }

    @Override
    public Mono<LuckyShipResponse> ship(String winningId, LuckyShipRequest request) {
        if (!StringUtils.hasText(winningId)) {
            return Mono.error(new IllegalArgumentException("winningId 不能为空"));
        }
        return client.fetch(LuckyWinning.class, winningId.trim())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("中奖记录不存在")))
            .flatMap(winning -> {
                if (!STATUS_SUBMITTED.equals(winning.getStatus())) {
                    return Mono.error(new IllegalArgumentException(
                        "仅「已提交待发货」的中奖单可标记发货，请等待用户填写收货信息"));
                }
                winning.setStatus(STATUS_SHIPPED);
                winning.setExpressCompany(request == null ? null
                    : trimToNull(request.getExpressCompany()));
                winning.setExpressNo(request == null ? null : trimToNull(request.getExpressNo()));
                if (request != null && StringUtils.hasText(request.getRemark())) {
                    winning.setRemark(request.getRemark().trim());
                }
                winning.setShipTime(Instant.now());
                return client.update(winning)
                    .map(updated -> LuckyShipResponse.builder()
                        .ok(true)
                        .message("已标记为已发货")
                        .build());
            });
    }

    @Override
    public Mono<LuckyShipResponse> reopenShipment(String winningId) {
        if (!StringUtils.hasText(winningId)) {
            return Mono.error(new IllegalArgumentException("winningId 不能为空"));
        }
        return client.fetch(LuckyWinning.class, winningId.trim())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("中奖记录不存在")))
            .flatMap(winning -> {
                if (!STATUS_SHIPPED.equals(winning.getStatus())) {
                    return Mono.error(new IllegalArgumentException(
                        "仅「已发货」的中奖单可取消发货"));
                }
                winning.setStatus(STATUS_SUBMITTED);
                winning.setExpressCompany(null);
                winning.setExpressNo(null);
                winning.setShipTime(null);
                return client.update(winning)
                    .map(updated -> LuckyShipResponse.builder()
                        .ok(true)
                        .message("已取消发货，状态回退为待发货")
                        .build());
            });
    }

    @Override
    public Mono<AdminLuckyWinningListResponse> listAllWinningsForAdmin(String status, String keyword,
        int page, int size) {
        var normalizedPage = Math.max(page, 0);
        var normalizedSize = Math.min(Math.max(size, 1), ADMIN_MAX_SIZE);
        var normalizedStatus = StringUtils.hasText(status) ? status.trim().toLowerCase() : "";
        var normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : "";
        return client.listAll(LuckyWinning.class, new ListOptions(), null)
            .filter(winning -> normalizedStatus.isEmpty() || normalizedStatus.equals(winning.getStatus()))
            .filter(winning -> normalizedKeyword.isEmpty()
                || containsIgnoreCase(winning.getPrizeName(), normalizedKeyword)
                || containsIgnoreCase(winning.getContactName(), normalizedKeyword)
                || containsIgnoreCase(winning.getPhone(), normalizedKeyword)
                || containsIgnoreCase(winning.getWxOpenid(), normalizedKeyword))
            .collectList()
            .flatMap(winnings -> Mono.zip(
                Mono.just(winnings.stream()
                    .sorted(Comparator.comparing(LuckyWinning::getWinTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList()),
                wxUserService.loadUserLookup()))
            .map(tuple -> toAdminPage(tuple.getT1(), tuple.getT2(), normalizedPage, normalizedSize));
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private AdminLuckyWinningListResponse toAdminPage(List<LuckyWinning> sorted, WxUserLookup lookup,
        int page, int size) {
        var total = sorted.size();
        var from = Math.min(page * size, total);
        var to = Math.min(from + size, total);
        var items = sorted.subList(from, to).stream()
            .map(winning -> toAdminItem(winning, lookup))
            .toList();
        return AdminLuckyWinningListResponse.builder()
            .items(items)
            .total(total)
            .hasNext(to < total)
            .page(page)
            .size(size)
            .build();
    }

    private static AdminLuckyWinningItemDTO toAdminItem(LuckyWinning winning, WxUserLookup lookup) {
        var wxUser = lookup.resolve(winning.getWxOpenid(), winning.getWxUserId());
        return AdminLuckyWinningItemDTO.builder()
            .id(winning.getMetadata() == null ? null : winning.getMetadata().getName())
            .wxOpenid(winning.getWxOpenid())
            .nickname(wxUser == null ? "" : (StringUtils.hasText(wxUser.getNickname())
                ? wxUser.getNickname() : ""))
            .avatarUrl(wxUser == null ? null : wxUser.getAvatarUrl())
            .prizeName(winning.getPrizeName())
            .emoji(winning.getEmoji())
            .status(winning.getStatus())
            .contactName(winning.getContactName())
            .phone(winning.getPhone())
            .address(winning.getAddress())
            .winTime(winning.getWinTime())
            .submitTime(winning.getSubmitTime())
            .expressCompany(winning.getExpressCompany())
            .expressNo(winning.getExpressNo())
            .shipTime(winning.getShipTime())
            .remark(winning.getRemark())
            .build();
    }

    @Override
    public Mono<LuckyShipResponse> update(String winningId, LuckyWinningUpdateRequest request) {
        if (!StringUtils.hasText(winningId)) {
            return Mono.error(new IllegalArgumentException("winningId 不能为空"));
        }
        if (request == null) {
            return Mono.error(new IllegalArgumentException("请求体不能为空"));
        }
        var status = StringUtils.hasText(request.getStatus()) ? request.getStatus().trim() : "";
        if (!STATUS_PENDING.equals(status) && !STATUS_SUBMITTED.equals(status)
            && !STATUS_SHIPPED.equals(status)) {
            return Mono.error(new IllegalArgumentException("状态不合法"));
        }
        return client.fetch(LuckyWinning.class, winningId.trim())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("中奖记录不存在")))
            .flatMap(winning -> {
                winning.setStatus(status);
                winning.setContactName(trimToNull(request.getContactName()));
                winning.setPhone(trimToNull(request.getPhone()));
                winning.setAddress(trimToNull(request.getAddress()));
                winning.setRemark(trimToNull(request.getRemark()));
                // 用户端未提交（或无有效地址）时不应停留在已发货/待发货态
                if (STATUS_PENDING.equals(status)) {
                    winning.setSubmitTime(null);
                    winning.setExpressCompany(null);
                    winning.setExpressNo(null);
                    winning.setShipTime(null);
                }
                return client.update(winning)
                    .map(updated -> LuckyShipResponse.builder()
                        .ok(true)
                        .message("中奖单已更新")
                        .build());
            });
    }

    @Override
    public Mono<LuckyShipResponse> delete(String winningId) {
        if (!StringUtils.hasText(winningId)) {
            return Mono.error(new IllegalArgumentException("winningId 不能为空"));
        }
        return client.fetch(LuckyWinning.class, winningId.trim())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("中奖记录不存在")))
            .flatMap(winning -> client.delete(winning)
                .map(deleted -> LuckyShipResponse.builder()
                    .ok(true)
                    .message("中奖单已删除")
                    .build()));
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 权重随机：总权重 <= 0 时返回第一个。
     *
     * <p>抽取口径为 {@code RANDOM.nextInt(总权重)} 后逐个累加权重命中，
     * 因此单个奖品的真实概率就是 {@code weight / 总权重}，
     * 与 {@code LuckyDrawConfigHelper.withProbabilities} 公示的概率一致。</p>
     */
    static LuckyPrizeDTO pickPrize(java.util.List<LuckyPrizeDTO> prizes) {
        if (prizes == null || prizes.isEmpty()) {
            return null;
        }
        var total = prizes.stream().mapToInt(LuckyPrizeDTO::getWeight).sum();
        if (total <= 0) {
            return prizes.get(0);
        }
        var bound = RANDOM.nextInt(total);
        var cursor = 0;
        for (var prize : prizes) {
            cursor += prize.getWeight();
            if (bound < cursor) {
                return prize;
            }
        }
        return prizes.get(prizes.size() - 1);
    }

    private Mono<LuckyConfigDTO> loadConfig() {
        // 双源兼容：「积分商城」mallConfig 优先（luckyEnabled/luckyCostPoints/luckyPrizes），
        // 未配置时回落旧「积分幸运抽奖」luckyDrawConfig 分组（旧 ConfigMap 数据无需迁移）
        var mall = settingFetcher.get("mallConfig");
        return (mall == null ? Mono.<com.fasterxml.jackson.databind.JsonNode>empty() : mall)
            .flatMap(node -> {
                var config = LuckyDrawConfigHelper.parseMall(node);
                return config == null ? legacyLuckyConfig() : Mono.just(config);
            })
            .switchIfEmpty(legacyLuckyConfig());
    }

    private Mono<LuckyConfigDTO> legacyLuckyConfig() {
        return settingFetcher.get(LuckyDrawConfigHelper.groupName())
            .map(LuckyDrawConfigHelper::parse)
            .defaultIfEmpty(LuckyDrawConfigHelper.defaults());
    }

    private Mono<String> resolveWxUserId(String openid, pro.uhalo.uni.scheme.UserPoints account) {
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

    private static long safeBalance(pro.uhalo.uni.scheme.UserPoints account) {
        return account.getBalance() == null ? 0L : Math.max(0L, account.getBalance());
    }
}
