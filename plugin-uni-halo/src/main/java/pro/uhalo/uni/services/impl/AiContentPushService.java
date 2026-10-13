package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.AiContentPushResultDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.scheme.AiContentIssue;
import pro.uhalo.uni.services.AiContentEntitlementService;
import pro.uhalo.uni.services.AiContentService;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Mono;

/**
 * 「立即生成并推送」：用户在 AI 定制内容页点一下，当场把今天的简报推给自己。
 *
 * <p><b>零额外 AI 成本的关键</b>：先确保今天的简报已生成（已生成就直接复用），再推。
 * 用户在页面上本来就会触发一次生成，这里复用的正是同一份结果——不会因为「点一下推送」
 * 多烧一次 AI。</p>
 *
 * <p><b>失败不抛错</b>：没有推送额度（一次性订阅没授权过 / 用完了）是正常业务状态，
 * 接口如实返回「未推送 + 原因」，让前端引导用户去授权，而不是报一个 500。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentPushService {

    private final AiContentFeedService feedService;
    private final AiContentPreferenceService preferenceService;
    private final AiContentEntitlementService entitlementService;
    private final PushDispatchService pushDispatchService;

    /** 立即生成并推送今天那份简报给自己 */
    public Mono<AiContentPushResultDTO> pushNow(String openid) {
        if (!StringUtils.hasText(openid)) {
            return Mono.error(new pro.uhalo.uni.exception.WxAuthException("请先完成微信登录"));
        }
        var normalized = openid.trim();
        return entitlementService.loadConfig()
            .flatMap(aiConfig -> {
                if (!aiConfig.isEnabled()) {
                    return Mono.error(new pro.uhalo.uni.exception.WxAuthException(
                        "「AI 定制内容」暂未开放"));
                }
                return entitlementService.isActive(normalized).flatMap(active -> {
                    if (!active) {
                        return Mono.error(new pro.uhalo.uni.exception.WxAuthException(
                            "请先开通「AI 定制内容」再推送"));
                    }
                    return loadPushConfig().flatMap(pushConfig -> {
                        if (!AiContentService.isPushReady(pushConfig)) {
                            return Mono.error(new pro.uhalo.uni.exception.WxAuthException(
                                "站长还没有配好推送模板，暂时推不了"));
                        }
                        // 用 push 触发：配额不足时把原因回给用户（定时线则安静跳过）
                        return feedService.ensureToday(normalized, "push")
                            .switchIfEmpty(Mono.error(new pro.uhalo.uni.exception.WxAuthException(
                                "今天的简报还没生成好，请稍后再试")))
                            .flatMap(issue -> preferenceService.load(normalized)
                                .flatMap(preference -> push(normalized, pushConfig, issue,
                                    preference, true)));
                    });
                });
            });
    }

    /**
     * 生成结果 → 推送；把「有没有发出去、为什么没发出去」如实回给前端（定时线也复用它）。
     *
     * @param manual true = 用户在页面里点的「立即推送」。手动推送只看额度：
     *               没打开「每天定时推送」的用户照样能推（早前会被误报成「额度不足」）
     */
    public Mono<AiContentPushResultDTO> push(String openid, PushConfigDTO pushConfig,
        AiContentIssue issue, AiContentPreferenceDTO preference, boolean manual) {
        return pushDispatchService.pushAiContentReady(openid, pushConfig, issue, preference,
                manual)
            .map(attempt -> AiContentPushResultDTO.builder()
                .pushed(attempt.sent())
                .date(issue.getDate())
                .needQuota(attempt.outcome() == PushDispatchService.PushOutcome.NO_QUOTA)
                .message(messageOf(attempt))
                .build());
    }

    /** 定时生成线：不是用户当场点的，因此尊重用户总开关 */
    public Mono<AiContentPushResultDTO> push(String openid, PushConfigDTO pushConfig,
        AiContentIssue issue, AiContentPreferenceDTO preference) {
        return push(openid, pushConfig, issue, preference, false);
    }

    /**
     * 失败原因分别给话。
     *
     * <p>早前无论哪种情况都说「推送额度不足」，于是「下面明明写着还有 3 条额度」的用户
     * 会以为功能坏了——每种结局都要能对上一个可操作的动作。</p>
     */
    private static String messageOf(PushDispatchService.PushAttempt attempt) {
        return switch (attempt.outcome()) {
            case SENT -> "已推送到你的微信，去微信里看看";
            case NO_QUOTA -> "没有可用的推送额度：点「立即推送」时微信会弹一次授权，"
                + "同意后即可收到（一次授权对应一条）";
            case ALREADY_PUSHED -> "今天这份简报已经推送过了，明天到点会自动再推";
            case DAILY_LIMIT -> "站点今天的推送量已达上限，请明天再试";
            case DISABLED -> "你已关闭推送开关，重新打开后即可接收";
            case SEND_FAILED -> "微信没有接受这条消息"
                + (attempt.detail().isEmpty() ? "，请稍后重试" : "：" + attempt.detail());
        };
    }

    private Mono<PushConfigDTO> loadPushConfig() {
        return pushDispatchService.loadPushConfigSafely();
    }
}
