package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAiBalanceDTO;
import pro.uhalo.uni.dto.DigestAiModelViewDTO;
import pro.uhalo.uni.dto.DigestAiPriceDTO;
import pro.uhalo.uni.dto.DigestAiProbeResultDTO;
import pro.uhalo.uni.dto.DigestAiProviderDTO;
import pro.uhalo.uni.dto.DigestConfigDTO;
import pro.uhalo.uni.enums.DigestAiTask;
import pro.uhalo.uni.services.DigestAiClient;
import pro.uhalo.uni.utils.DigestAiChannelResolver;
import pro.uhalo.uni.utils.DigestAiPricing;
import pro.uhalo.uni.utils.DigestAiUsageLog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「大模型通道」面板的后端：读清单、保存清单、测试连通性。
 *
 * <p>保存走的是<b>读-改-写</b>：只替换 {@code aiDigestConfig} 分组里的 {@code aiProviders}
 * 一个字段，其余配置（时间窗、精选篇数、分类清单…）与页面不认识的字段全部原样保留。
 * 抢并发（Halo 用乐观锁）时重试若干次，避免一次保存把站长的其它改动覆盖掉。</p>
 *
 * <p>Key 的处理有一条硬规则：提交上来的 Key 是空串或掩码串时，按<b>已保存的那把</b>保留。
 * 后台面板因此可以只展示掩码、站长也不用为了改一个模型名而重新粘贴一遍密钥。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestAiModelService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String PLUGIN_NAME = "plugin-uni-halo";
    private static final String FALLBACK_CONFIG_MAP_NAME = "plugin-uni-halo-configMap";
    private static final String GROUP = DigestConfigHelper.groupName();

    /** 并发更新配置存储时的重试次数（与「商业化配置」页同一套做法） */
    private static final int SAVE_MAX_ATTEMPTS = 3;

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final DigestAiClient aiClient;
    private final DigestAiPriceFetcher priceFetcher;

    /** 通道清单 + 当前生效通道（Key 掩码） */
    public Mono<DigestAiModelViewDTO> load() {
        return loadConfig().map(DigestAiModelViewDTO::of);
    }

    /**
     * 保存清单。
     *
     * <p>超过上限直接报错而不是静默截断：截断等于悄悄丢掉站长刚填的通道，
     * 而且他会在下次日报跑失败时才发现。</p>
     */
    public Mono<DigestAiModelViewDTO> save(List<DigestAiProviderDTO> providers) {
        var incoming = providers == null ? List.<DigestAiProviderDTO>of() : providers;
        if (incoming.size() > DigestConfigHelper.MAX_AI_PROVIDERS) {
            return Mono.error(new IllegalArgumentException(
                "最多保存 " + DigestConfigHelper.MAX_AI_PROVIDERS + " 条通道，当前提交了 " + incoming.size() + " 条"));
        }
        return saveAttempt(incoming, 0);
    }

    /**
     * 测试一条通道。
     *
     * <p>支持「还没保存的草稿行」：请求带上完整的行即可；Key 为空或掩码时按行标识
     * 回查已保存的那把，因此页面不需要为了测试而先把明文密钥回传上来。</p>
     */
    public Mono<DigestAiProbeResultDTO> test(DigestAiProviderDTO request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("请求体里没有可测试的通道"));
        }
        return loadConfig().flatMap(config -> {
            var target = resolveTestTarget(config, request);
            if (target.channel() == null) {
                return Mono.just(DigestAiProbeResultDTO.builder()
                    .ok(false)
                    .key(nullToEmpty(request.key()))
                    .label(nullToEmpty(request.label()))
                    .apiBase(DigestAiChannelResolver.normalizeBase(request.apiBase()))
                    .model(nullToEmpty(request.model()))
                    .latencyMs(0L)
                    .message(target.problem())
                    .build());
            }
            return aiClient.probe(target.channel());
        });
    }

    /**
     * 查各通道的账户余额（面板上那个「查余额」按钮）。
     *
     * <p>只查可用通道、最多几条：余额是账号级数据，通道再多也不会有额外信息。
     * 任何一条查不到都不影响其它条，各自把原因写在结果里（不支持的端点属于正常现象）。</p>
     */
    public Mono<List<DigestAiBalanceDTO>> balances() {
        return loadConfig().flatMap(config -> {
            var channels = DigestAiChannelResolver.usableChannels(config).stream()
                .limit(DigestAiUsageLog.MAX_BALANCE_CHANNELS)
                .toList();
            if (channels.isEmpty()) {
                return Mono.just(List.<DigestAiBalanceDTO>of());
            }
            return Flux.fromIterable(channels)
                .flatMap(channel -> aiClient.queryBalance(channel)
                    .onErrorResume(ex -> Mono.just(DigestAiBalanceDTO.failure(channel.key(),
                        channel.label(), channel.apiBase(),
                        "余额查询失败：" + ex.getMessage()))))
                .collectList();
        });
    }

    /**
     * 从官网读一次单价并写入配置（面板「从官网刷新单价」按钮）。
     *
     * <p>同名（模型匹配串相同）覆盖，其它条目保留——站长的价格表里可能还有
     * 别的供应商，刷 DeepSeek 的价不该把它们清掉。</p>
     */
    public Mono<DigestAiModelViewDTO> refreshPrices() {
        return priceFetcher.fetchOfficial()
            .flatMap(fetched -> savePrices(fetched, true))
            .onErrorResume(ex -> Mono.error(new IllegalStateException(
                ex.getMessage() + "（可继续使用内置价目表，不影响运行）", ex)));
    }

    /**
     * 价格表为空时自动去官网读一次。
     *
     * <p>目标是「站长什么都不用填」：内置价目表先顶上，官网读到之后按官网的算。
     * 因此在流水线开始时后台跑一次，不阻塞运行——这一次运行仍用内置表（数值同源）。</p>
     */
    public Mono<Void> fillPricesIfEmpty() {
        return loadConfig().flatMap(config -> {
            if (!config.isPriceAutoRefreshEnabled()) {
                return Mono.empty();
            }
            if (config.getAiPrices() != null && !config.getAiPrices().isEmpty()) {
                return Mono.empty();
            }
            return priceFetcher.fetchOfficial()
                .flatMap(fetched -> savePrices(fetched, false))
                .then()
                .onErrorResume(ex -> {
                    log.info("[UniHalo][Digest] 未能自动读取官网单价，继续使用内置价目表：{}",
                        ex.getMessage());
                    return Mono.empty();
                });
        });
    }

    /** 写入价格表：读-改-写，只动 aiPrices 与 aiPriceFetchedAt 两个字段 */
    private Mono<DigestAiModelViewDTO> savePrices(List<DigestAiPriceDTO> fetched,
        boolean replaceSameModel) {
        return resolveConfigMapName()
            .flatMap(configMapName -> client.fetch(ConfigMap.class, configMapName)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "插件配置存储不存在，请先在「应用市场 → 已安装」里重新应用本插件")))
                .flatMap(configMap -> {
                    var data = configMap.getData() == null
                        ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(configMap.getData());
                    var groupNode = readGroup(data.get(GROUP));
                    var existing = DigestConfigHelper.parse(groupNode).getAiPrices();
                    var merged = replaceSameModel
                        ? DigestAiPricing.mergeFetched(existing, fetched)
                        : fetched;
                    groupNode.set("aiPrices", OBJECT_MAPPER.valueToTree(merged));
                    groupNode.put("aiPriceFetchedAt", java.time.Instant.now().toString());
                    data.put(GROUP, writeJson(groupNode));
                    configMap.setData(data);
                    return client.update(configMap);
                }))
            .map(saved -> DigestAiModelViewDTO.of(
                DigestConfigHelper.parse(readGroup(saved.getData().get(GROUP)))))
            .doOnNext(view -> log.info("[UniHalo][Digest] 单价表已更新：来源={}，共 {} 条",
                view.prices().source(), view.prices().items().size()))
            .onErrorResume(ex -> {
                if (ex instanceof IllegalStateException) {
                    return Mono.error(ex);
                }
                return Mono.error(new IllegalStateException("写入单价失败：" + ex.getMessage(), ex));
            });
    }

    // ── 保存 ──

    private Mono<DigestAiModelViewDTO> saveAttempt(List<DigestAiProviderDTO> incoming, int attempt) {
        return resolveConfigMapName()
            .flatMap(configMapName -> client.fetch(ConfigMap.class, configMapName)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "插件配置存储不存在，请先在「应用市场 → 已安装」里重新应用本插件")))
                .flatMap(configMap -> {
                    var data = configMap.getData() == null
                        ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(configMap.getData());
                    var groupNode = readGroup(data.get(GROUP));
                    var stored = DigestConfigHelper.parse(groupNode).getAiProviders();
                    var merged = mergeProviders(incoming, stored);
                    groupNode.set("aiProviders", OBJECT_MAPPER.valueToTree(merged));
                    data.put(GROUP, writeJson(groupNode));
                    configMap.setData(data);
                    return client.update(configMap);
                }))
            // 用刚写进去的那份 JSON 生成返回值：既确定是「真实落库的内容」，
            // 也不依赖设置读取的缓存新鲜度
            .map(saved -> DigestAiModelViewDTO.of(
                DigestConfigHelper.parse(readGroup(saved.getData().get(GROUP)))))
            .doOnNext(view -> log.info("[UniHalo][Digest] 大模型通道已保存：{} 条（{}）",
                view.providers().size(), view.summary()))
            .onErrorResume(ex -> {
                if (isConcurrentModification(ex) && attempt + 1 < SAVE_MAX_ATTEMPTS) {
                    log.warn("[UniHalo][Digest] 配置存储被并发修改，重试保存大模型通道（第 {} 次）",
                        attempt + 1);
                    return saveAttempt(incoming, attempt + 1);
                }
                return Mono.error(ex);
            });
    }

    /**
     * 合并提交的清单与已保存的清单。
     *
     * <p>逐行的 Key 保留规则：提交空值或掩码 → 沿用已保存的那把。
     * 定位「已保存的那把」优先按通道标识匹配，标识为空时按位置匹配——
     * 插件设置页保存过的清单可能没有标识（那是给后台面板用的字段）。</p>
     *
     * <p>用途（tasks）则相反：<b>直接取提交值</b>。它是站长这次编辑的内容，
     * 像 Key 那样「留空保留旧值」会让「把勾去掉」变成一个做不到的操作。</p>
     */
    private static List<DigestAiProviderDTO> mergeProviders(List<DigestAiProviderDTO> incoming,
        List<DigestAiProviderDTO> stored) {
        var result = new ArrayList<DigestAiProviderDTO>();
        var usedKeys = new HashSet<String>();
        for (var index = 0; index < incoming.size(); index++) {
            var row = incoming.get(index);
            if (row == null) {
                continue;
            }
            var apiBase = DigestAiChannelResolver.normalizeBase(row.apiBase());
            var label = trim(row.label());
            var apiKey = trim(row.apiKey());
            // 整行空白的（页面上点了「添加通道」没填）不落库，免得留下占位行
            if (apiBase.isEmpty() && apiKey.isEmpty() && label.isEmpty()) {
                continue;
            }
            var storedRow = findStored(stored, row.key(), index);
            var key = DigestConfigHelper.uniqueProviderKey(row.key(), usedKeys, result.size() + 1);
            usedKeys.add(key);
            result.add(new DigestAiProviderDTO(key, label, apiBase,
                resolveKey(apiKey, storedRow), trim(row.model()), row.enabled(),
                // 非法/未知用途在解析层就被丢掉了，这里存的是归一化后的结果
                normalizeTasks(row.tasks())));
        }
        return List.copyOf(result);
    }

    /** 用途归一化：去空、去重、丢弃未知标识（与 DigestConfigHelper#parseTasks 同一口径） */
    private static List<String> normalizeTasks(List<String> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        var used = new LinkedHashSet<String>();
        for (var raw : tasks) {
            DigestAiTask.fromKey(raw).ifPresent(task -> used.add(task.getKey()));
        }
        return List.copyOf(used);
    }

    // ── 测试 ──

    /** 测试目标：要么是一条可发请求的通道，要么是一句「为什么测不了」 */
    private record TestTarget(DigestAiProviderDTO channel, String problem) {
    }

    private static TestTarget resolveTestTarget(DigestConfigDTO config, DigestAiProviderDTO request) {
        var stored = findStored(DigestAiChannelResolver.channels(config), request.key(), -1);
        var apiBase = DigestAiChannelResolver.normalizeBase(request.apiBase());
        if (apiBase.isEmpty() && stored != null) {
            apiBase = stored.apiBase();
        }
        var apiKey = resolveKey(trim(request.apiKey()), stored);
        if (apiBase.isEmpty() && apiKey.isEmpty()) {
            return new TestTarget(null, "这条通道还没填端点与 API Key，先填好再测");
        }
        if (apiBase.isEmpty()) {
            return new TestTarget(null, "缺 API Base：填上端点（OpenAI 兼容地址一般以 /v1 结尾）再测");
        }
        if (apiKey.isEmpty()) {
            return new TestTarget(null, "缺 API Key：填上密钥再测（已填过的话，刷新页面重试）");
        }
        var model = StringUtils.hasText(request.model())
            ? request.model().trim() : (stored != null ? stored.model()
                : DigestAiChannelResolver.inferModel(apiBase));
        var label = StringUtils.hasText(request.label())
            ? request.label().trim() : (stored != null ? stored.label()
                : DigestAiChannelResolver.hostOf(apiBase));
        return new TestTarget(new DigestAiProviderDTO(nullToEmpty(request.key()), label, apiBase,
            apiKey, model, true), null);
    }

    /** 掩码 / 空值 = 没改过，用已保存的 Key；其余按新 Key 处理 */
    private static String resolveKey(String incoming, DigestAiProviderDTO stored) {
        if (!StringUtils.hasText(incoming) || DigestAiProviderDTO.isMasked(incoming)) {
            return stored == null ? "" : nullToEmpty(stored.apiKey());
        }
        return incoming.trim();
    }

    private static DigestAiProviderDTO findStored(List<DigestAiProviderDTO> stored, String key,
        int fallbackIndex) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(key)) {
            for (var item : stored) {
                if (item != null && key.trim().equals(item.key())) {
                    return item;
                }
            }
        }
        if (fallbackIndex >= 0 && fallbackIndex < stored.size()) {
            return stored.get(fallbackIndex);
        }
        return null;
    }

    // ── 配置读写 ──

    private Mono<DigestConfigDTO> loadConfig() {
        return settingFetcher.get(GROUP)
            .map(DigestConfigHelper::parse)
            .defaultIfEmpty(DigestConfigHelper.defaults());
    }

    /**
     * 配置存储名以插件声明为准：站长可以在 {@code plugin.yaml} 里改这两个名字，
     * 硬编码就会写错地方（与「商业化配置」页同一考虑）。
     */
    private Mono<String> resolveConfigMapName() {
        return client.fetch(Plugin.class, PLUGIN_NAME)
            .map(plugin -> plugin.getSpec() == null ? null : plugin.getSpec().getConfigMapName())
            .filter(StringUtils::hasText)
            .defaultIfEmpty(FALLBACK_CONFIG_MAP_NAME)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取插件声明失败，按默认配置存储名处理：{}", ex.getMessage());
                return Mono.just(FALLBACK_CONFIG_MAP_NAME);
            });
    }

    private static ObjectNode readGroup(String json) {
        if (StringUtils.hasText(json)) {
            try {
                var parsed = OBJECT_MAPPER.readTree(json);
                if (parsed instanceof ObjectNode objectNode) {
                    return objectNode;
                }
            } catch (Exception ex) {
                // 原值不是合法 JSON（人工改过配置存储）：以本次提交的字段为准，
                // 不因为一个坏掉的分组让保存失败
                log.warn("[UniHalo][Digest] aiDigestConfig 不是合法 JSON，将以本次提交覆盖它：{}",
                    ex.getMessage());
            }
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private static String writeJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("配置序列化失败：" + ex.getMessage(), ex);
        }
    }

    /** Halo 的乐观锁冲突信息里带 concurrently，据此决定要不要重试 */
    private static boolean isConcurrentModification(Throwable ex) {
        var chain = ex;
        while (chain != null) {
            var message = chain.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("concurrently")) {
                return true;
            }
            chain = chain.getCause() == chain ? null : chain.getCause();
        }
        return false;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
