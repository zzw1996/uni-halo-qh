package pro.uhalo.uni.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.uhalo.uni.dto.PushConfigDTO;
import pro.uhalo.uni.dto.PushDiagnoseItemDTO;
import pro.uhalo.uni.scheme.PushRecord;
import pro.uhalo.uni.scheme.PushState;
import pro.uhalo.uni.scheme.PushSubscription;
import pro.uhalo.uni.utils.PushConfigHelper;
import pro.uhalo.uni.utils.RateLimiter;
import pro.uhalo.uni.utils.WxAccessTokenProvider;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.List;

import static run.halo.app.extension.index.query.QueryFactory.equal;

/**
 * 推送自检：一步一问，把「500 但不知道哪一步坏了」变成可读结论。
 *
 * <p>为什么需要它：推送失败几乎都是环境问题（新扩展的索引还没建完、模板没绑定服务类目、
 * token 取不到），而这些原因从接口返回的一句「请稍后重试」里根本看不出来。
 * 这个自检把整条链路的每一步单独跑一遍，失败的那一步带上原始错误消息。</p>
 *
 * <p>检查项（顺序执行，前面失败不影响后面）：</p>
 * <ol>
 *   <li><b>配置</b>：总开关 / 模板 ID / 两个分开关；</li>
 *   <li><b>读订阅记录</b>：按 {@code wxOpenid} 索引查询（等价于小程序读取额度那一步）；</li>
 *   <li><b>写入探针</b>：建一条临时记录再删掉（这一步失败＝用户点「同意」时必然 500）；</li>
 *   <li><b>读调度状态</b>：单例 {@code push-state} 是否可读写；</li>
 *   <li><b>微信令牌</b>：能否取到 access_token（配错 appId/appSecret 时在这里暴露）。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushDiagnoseService {

    /** 探针记录名固定，跑完即删；万一删失败也不会污染用户数据 */
    private static final String PROBE_NAME = "ps-diagnose-probe";

    /** 自检本身很轻，但没必要被反复点：每分钟最多 10 次 */
    private static final String RATE_KEY = "push:diagnose";
    private static final int RATE_LIMIT = 10;

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final WxAccessTokenProvider tokenProvider;
    private final RateLimiter rateLimiter;

    public Mono<List<PushDiagnoseItemDTO>> diagnose() {
        return config().flatMap(config -> Mono.zip(
                configItem(config),
                readItem(),
                writeProbeItem(),
                stateItem(),
                tokenItem())
            .map(tuple -> List.of(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(),
                tuple.getT5())));
    }

    private Mono<PushDiagnoseItemDTO> configItem(PushConfigDTO config) {
        var detail = new StringBuilder();
        detail.append(config.isEnabled() ? "已开启" : "未开启（总开关关闭时一条都不会发）");
        detail.append("；新文章提醒=").append(config.isNewPostEnabled() ? "开" : "关");
        detail.append("；简报提醒=").append(config.isAiDigestEnabled() ? "开" : "关");
        detail.append(config.hasNewPostTemplate()
            ? "；模板=" + shortId(config.getTemplateId())
            : "；模板未配置（下发必然报 40037）");
        detail.append("；跳转版本=").append(config.getMiniprogramState());
        return Mono.just(item("配置", config.isEnabled() && config.hasNewPostTemplate(),
            detail.toString()));
    }

    /** 读一步：与小程序「读我的额度」完全同一条件（按 wxOpenid 走索引） */
    private Mono<PushDiagnoseItemDTO> readItem() {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(equal("wxOpenid", "__diagnose__")));
        return client.listAll(PushSubscription.class, options, null)
            .count()
            .map(count -> item("读取订阅记录", true, "索引可用（命中 " + count + " 条）"))
            .onErrorResume(ex -> Mono.just(item("读取订阅记录", false, describe(ex))));
    }

    /**
     * 写一步：这才是「用户点同意时报 500」的真正考点。
     *
     * <p>新扩展类型在插件启动时会现建索引，建完之前一切写入都会被拒绝——这里直接建一条
     * 探针记录来验证写入通道，建完立刻删掉。</p>
     */
    private Mono<PushDiagnoseItemDTO> writeProbeItem() {
        return client.fetch(PushSubscription.class, PROBE_NAME)
            .onErrorResume(ex -> Mono.empty())
            .flatMap(existing -> client.delete(existing).onErrorResume(ex -> Mono.empty()))
            .then(Mono.defer(() -> client.create(probe())))
            .flatMap(created -> client.delete(created)
                .doOnNext(deleted -> log.info("[UniHalo][Push] 自检探针已清理"))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Push] 自检探针清理失败（不影响功能）：{}", ex.getMessage());
                    return Mono.just(created);
                })
                .thenReturn(item("写入订阅记录", true, "可写（探针已清理）")))
            .onErrorResume(ex -> Mono.just(item("写入订阅记录", false, describe(ex))));
    }

    private Mono<PushDiagnoseItemDTO> stateItem() {
        return client.fetch(PushState.class, PushState.RESOURCE_NAME)
            .map(state -> item("读取调度状态", true,
                "水位=" + (state.getLastCheckedPublishTime() == null ? "未开始"
                    : state.getLastCheckedPublishTime().toString())
                    + "；今日已发=" + orZero(state.getDailySent())
                    + "；今日失败=" + orZero(state.getDailySkipped())
                    + "；日期=" + (state.getDailyDate() == null ? "未记录" : state.getDailyDate())))
            .defaultIfEmpty(item("读取调度状态", true, "尚未创建（调度器第一轮会自动建）"))
            .onErrorResume(ex -> Mono.just(item("读取调度状态", false, describe(ex))));
    }

    private Mono<PushDiagnoseItemDTO> tokenItem() {
        if (!rateLimiter.tryAcquire(RATE_KEY, RATE_LIMIT, java.time.Duration.ofMinutes(1))) {
            return Mono.just(item("微信令牌", true, "本次跳过（自检过于频繁）"));
        }
        return tokenProvider.token()
            .map(token -> item("微信令牌", true, "已取到（长度 " + token.length() + "）"))
            .onErrorResume(ex -> Mono.just(item("微信令牌", false, describe(ex))));
    }

    /** 最近推送记录的失败样本（有失败时最有信息量） */
    public Mono<List<PushRecord>> recentFailures() {
        return client.listAll(PushRecord.class, new ListOptions(), null)
            .filter(record -> !"SENT".equals(record.getStatus()))
            .take(5)
            .collectList()
            .onErrorResume(ex -> Mono.just(List.of()));
    }

    private Mono<PushConfigDTO> config() {
        return settingFetcher.get(PushConfigHelper.groupName())
            .map(PushConfigHelper::parse)
            .defaultIfEmpty(PushConfigHelper.defaults())
            .onErrorResume(ex -> Mono.just(PushConfigHelper.defaults()));
    }

    private static PushSubscription probe() {
        var probe = new PushSubscription();
        probe.setWxOpenid("__diagnose__");
        probe.setEnabled(true);
        probe.setNewPostQuota(0);
        probe.setAiDigestQuota(0);
        var metadata = new Metadata();
        metadata.setName(PROBE_NAME);
        metadata.setCreationTimestamp(Instant.now());
        probe.setMetadata(metadata);
        return probe;
    }

    private static PushDiagnoseItemDTO item(String step, boolean ok, String message) {
        return PushDiagnoseItemDTO.builder().step(step).ok(ok).message(message).build();
    }

    /** 原始错误消息（自检的全部意义就在这行）：太长就截断，别把页面撑爆 */
    private static String describe(Throwable ex) {
        var text = ex.getClass().getSimpleName() + "：" + ex.getMessage();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static String shortId(String templateId) {
        if (templateId == null || templateId.length() <= 10) {
            return templateId;
        }
        return templateId.substring(0, 10) + "…";
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
