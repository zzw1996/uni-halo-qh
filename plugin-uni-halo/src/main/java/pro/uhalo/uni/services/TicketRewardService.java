package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.TicketRewardConfigDTO;
import pro.uhalo.uni.infra.PointsPersistence;
import pro.uhalo.uni.scheme.UserPoints;
import pro.uhalo.uni.utils.TicketRewardConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 阅读券免费获取：签到 / 连签 / 新人礼 / 首购 四条赠券路径的统一发放入口。
 *
 * <p>发放顺序与插件内其它权益发放保持一致：<b>先写幂等流水，成功后再改账户</b>。
 * 流水的 {@code metadata.name} 由 {@code earnKey} 派生、全局唯一，因此并发调用时
 * 只有一个 create 能成功，另一个冲突失败即视为「已发放」并跳过账户修改——
 * 这样同一天重复签到、重复触发首购判定都不会重复发券。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketRewardService {

    /** 赠券动作码：与 purchase_/exchange_ 前缀区分，使「积分明细」能如实区分券的来源 */
    public static final String ACTION_SIGN_IN = "ticket_sign_in";
    public static final String ACTION_STREAK = "ticket_streak";
    public static final String ACTION_NEW_USER = "ticket_new_user";
    public static final String ACTION_FIRST_PURCHASE = "ticket_first_purchase";
    public static final String ACTION_MEMBER_BONUS = "ticket_member_bonus";

    private final PointsPersistence pointsPersistence;
    private final ReactiveSettingFetcher settingFetcher;

    /** 读取赠券规则；读取失败按默认值处理（与其它配置读取路径的容错取向一致）。 */
    public Mono<TicketRewardConfigDTO> loadConfig() {
        return settingFetcher.get(TicketRewardConfigHelper.groupName())
            .map(TicketRewardConfigHelper::parse)
            .defaultIfEmpty(TicketRewardConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("读取阅读券获取配置失败（按默认值处理）：{}", ex.getMessage());
                return Mono.just(TicketRewardConfigHelper.defaults());
            });
    }

    /**
     * 发放阅读券。
     *
     * @return 实际发放张数；因幂等流水已存在而跳过时返回 0，调用方可据此决定是否提示用户
     */
    public Mono<Integer> grant(String openid, int count, String action, String description,
        String earnKey, String refName, String wxUserId) {
        if (!StringUtils.hasText(openid) || count <= 0) {
            return Mono.just(0);
        }
        return pointsPersistence.getOrCreateAccount(openid)
            .flatMap(account -> {
                var record = pointsPersistence.newRecord(
                    openid, action, 0, safeBalance(account), description, refName, earnKey, wxUserId);
                // 券变动单独记账：后台看板据此算出真实的券发放/消耗比
                record.setTickets(count);
                return pointsPersistence.appendRecord(record)
                    // 幂等闸门已过（或冲突即已发放），此时才允许修改账户
                    .then(pointsPersistence.getOrCreateAccount(openid))
                    .flatMap(latest -> {
                        latest.setContentTickets(safeTickets(latest.getContentTickets()) + count);
                        if (StringUtils.hasText(wxUserId)
                            && !wxUserId.equals(latest.getWxUserId())) {
                            latest.setWxUserId(wxUserId);
                        }
                        return pointsPersistence.saveAccount(latest).thenReturn(count);
                    })
                    .onErrorResume(ex -> {
                        if (isDuplicateNameError(ex)) {
                            log.info("阅读券赠券流水已存在，跳过重复发放：earnKey={}", earnKey);
                            return Mono.just(0);
                        }
                        return Mono.error(ex);
                    });
            });
    }

    private static long safeBalance(UserPoints account) {
        return account == null || account.getBalance() == null ? 0L
            : Math.max(0L, account.getBalance());
    }

    private static int safeTickets(Integer tickets) {
        return tickets == null ? 0 : Math.max(0, tickets);
    }

    private static boolean isDuplicateNameError(Throwable ex) {
        var message = ex == null ? null : ex.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("already exists")
            || normalized.contains("duplicate")
            || normalized.contains("已存在");
    }
}
