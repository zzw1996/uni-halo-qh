package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.AiContentConfigDTO;
import pro.uhalo.uni.dto.AiContentPreferenceDTO;
import pro.uhalo.uni.dto.AiContentPreferenceRequest;
import pro.uhalo.uni.dto.AiContentPreferenceViewDTO;
import pro.uhalo.uni.dto.DigestCategoryDTO;
import pro.uhalo.uni.dto.DigestOptionItemDTO;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.services.impl.AiContentFeedService;
import pro.uhalo.uni.services.impl.AiContentPreferenceService;
import pro.uhalo.uni.services.impl.DigestPublicService;
import pro.uhalo.uni.utils.AiContentConfigHelper;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import pro.uhalo.uni.utils.PushConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「AI 定制内容」页面的读模型与写入闸门。
 *
 * <p>页面要的东西恰好横跨三处：权益状态（{@link AiContentEntitlementService}）、
 * 用户偏好（{@link AiContentPreferenceService}）、可选清单（站长的分类清单 + 最近日报里
 * 实际出现过的来源）。把这些拼装收在这里，端点只做「取 openid → 调服务 → 回 JSON」，
 * 与 {@code DigestPublicEndpoint} 的分工保持一致。</p>
 *
 * <p><b>写入闸门</b>：保存偏好必须先过权益判定。前端虽然只在有效时才显示配置区，
 * 但接口是匿名可写的——不在这里拦，任何人都能往偏好表里写数据。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiContentService {

    private final AiContentEntitlementService entitlementService;
    private final AiContentPreferenceService preferenceService;
    private final AiContentFeedService feedService;
    private final DigestPublicService digestPublicService;
    private final ReactiveSettingFetcher settingFetcher;

    /** 页面一次拉齐：权益状态 + 偏好 + 可选项 + 上限 + 推送设置 */
    public Mono<AiContentPreferenceViewDTO> view(String openid) {
        return Mono.zip(entitlementService.loadConfig(), loadPushConfig()).flatMap(configs ->
            Mono.zip(
                entitlementService.getStatus(openid),
                preferenceService.load(openid),
                sourceOptions(configs.getT1()),
                categoryOptions(),
                // 积分余额：让前端在点击「兑换」时就能判断够不够（服务端仍会权威校验）
                entitlementService.loadPointsBalance(openid).defaultIfEmpty(-1L))
            .map(tuple -> AiContentPreferenceViewDTO.builder()
                .status(tuple.getT1())
                .preference(tuple.getT2())
                .sourceOptions(tuple.getT3())
                .categoryOptions(tuple.getT4())
                .limits(limitsOf(configs.getT1()))
                .presetTopics(AiContentConfigHelper.parseTopics(configs.getT1().getPresetTopics()))
                .redeem(AiContentPreferenceViewDTO.Redeem.builder()
                    .pointsPrice(configs.getT1().getPointsPrice())
                    .pointsDays(configs.getT1().getPointsDays())
                    // -1 表示未登录/读不到：前端据此不显示余额、也不做本地判断
                    .pointsBalance(balanceOf(tuple.getT5()))
                    .build())
                .push(AiContentPreferenceViewDTO.PushSettings.builder()
                    .hourOptions(PushConfigHelper.parsePushHours(
                        configs.getT2().getAiDigestPushHours()))
                    .ready(isPushReady(configs.getT2()))
                    .build())
                .benefitNote(configs.getT1().getBenefitNote())
                .build()));
    }

    /** 未登录或读取失败时用 -1 表示「余额未知」 */
    private static Long balanceOf(Long balance) {
        return balance == null || balance < 0 ? -1L : balance;
    }

    /** 推送配置：读失败按「未配置推送」处理，不给用户露出一个推不出去的开关 */
    private Mono<PushConfigDTO> loadPushConfig() {
        return settingFetcher.get(PushConfigHelper.groupName())
            .map(PushConfigHelper::parse)
            .defaultIfEmpty(PushConfigHelper.defaults())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取推送配置失败，按未开启处理：{}", ex.getMessage());
                return Mono.just(PushConfigHelper.defaults());
            });
    }

    /**
     * 推送是否可用：总开关 + 简报开关 + 模板 + 字段映射，缺一不可。
     *
     * <p>不满足时前端不显示推送入口——让用户开一个永远推不出去的开关，比不给入口更伤。</p>
     */
    public static boolean isPushReady(PushConfigDTO pushConfig) {
        return pushConfig.isEnabled() && pushConfig.isAiDigestEnabled()
            && pushConfig.hasAiDigestTemplate() && pushConfig.aiDigestFieldMap().usable();
    }

    /** 保存偏好（全量替换）：未开通/已过期一律拒绝，并给出可读原因 */
    public Mono<AiContentPreferenceDTO> save(String openid, AiContentPreferenceRequest request) {
        return entitlementService.loadConfig().flatMap(config -> {
            if (!config.isEnabled()) {
                return Mono.error(new WxAuthException("「AI 定制内容」暂未开放"));
            }
            if (!StringUtils.hasText(openid)) {
                return Mono.error(new WxAuthException("缺少 Wechat-Session-Id"));
            }
            return entitlementService.isActive(openid).flatMap(active -> {
                if (!active) {
                    return Mono.error(new WxAuthException(
                        "请先开通「AI 定制内容」再保存关注方向（已过期的配置会保留，续费后继续生效）"));
                }
                return loadPushConfig().flatMap(pushConfig -> settingsCatalog().flatMap(catalog ->
                    preferenceService.load(openid).flatMap(before ->
                        preferenceService.save(openid, request, catalog, config,
                                PushConfigHelper.parsePushHours(pushConfig.getAiDigestPushHours()))
                            .flatMap(saved -> samePreferences(before, saved)
                                // 没变就不动当天那份：否则反复提交同一份偏好就能反复消耗 AI 额度
                                ? Mono.just(saved)
                                : feedService.invalidateToday(openid).thenReturn(saved)))));
            });
        });
    }

    /** 是否已配过偏好（生成侧之外的地方也要判断，例如页面提示） */
    public Mono<Boolean> configured(String openid) {
        return preferenceService.load(openid).map(AiContentPreferenceDTO::isConfigured);
    }

    /**
     * 主题分类候选：直接取站长的「日报分类」清单。
     *
     * <p>用户只能勾 AI 真正会用的分类——让他自由填写等于允许配出一个永远没有内容的偏好。</p>
     */
    private Mono<List<DigestCategoryDTO>> categoryOptions() {
        return settingsCatalog().map(catalog -> catalog.categories().stream()
            .map(entry -> DigestCategoryDTO.builder()
                .key(entry.key())
                .label(entry.label())
                .emoji(entry.emoji())
                .build())
            .toList());
    }

    /** 来源候选：最近日报里实际出现过的来源（用户不会配到一个从来没有内容的来源） */
    private Mono<List<DigestOptionItemDTO>> sourceOptions(
        AiContentConfigDTO config) {
        return digestPublicService.sourceOptions(config.getWindowDays())
            .onErrorResume(ex -> {
                log.warn("[UniHalo][AiContent] 读取来源候选失败，按空列表处理：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    private Mono<DigestCategoryCatalog> settingsCatalog() {
        return settingFetcher.get(DigestConfigHelper.groupName())
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults())
            .map(config -> DigestCategoryCatalog.from(config.getDigestCategories()));
    }

    /** 三个维度是否完全一致（顺序无关）：用来判断这次保存是否值得重算当天的简报 */
    private static boolean samePreferences(AiContentPreferenceDTO before,
        AiContentPreferenceDTO after) {
        return sameSet(before.getCategories(), after.getCategories())
            && sameSet(before.getSources(), after.getSources())
            && sameSet(before.getKeywords(), after.getKeywords())
            && sameSet(before.getCustomTopics(), after.getCustomTopics());
    }

    private static boolean sameSet(List<String> left, List<String> right) {
        var a = left == null ? Set.<String>of() : new HashSet<>(left);
        var b = right == null ? Set.<String>of() : new HashSet<>(right);
        return a.equals(b);
    }

    private static AiContentPreferenceViewDTO.Limits limitsOf(AiContentConfigDTO config) {
        return AiContentPreferenceViewDTO.Limits.builder()
            .maxCategories(config.getMaxCategories())
            .maxSources(config.getMaxSources())
            .maxKeywords(config.getMaxKeywords())
            .maxTopics(config.getMaxTopics())
            .maxItems(config.getMaxItems())
            .windowDays(config.getWindowDays())
            .aiIntroEnabled(config.isAiIntroEnabled())
            .build();
    }
}
