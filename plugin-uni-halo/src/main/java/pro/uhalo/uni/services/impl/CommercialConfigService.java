package pro.uhalo.uni.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.CommercialConfigDTO;
import pro.uhalo.uni.dto.CommercialConfigSaveRequest;
import pro.uhalo.uni.dto.ConfigGroupDTO;
import pro.uhalo.uni.exception.WxAuthException;
import pro.uhalo.uni.utils.CommercialConfigChecks;
import pro.uhalo.uni.utils.ContentTicketConfigHelper;
import pro.uhalo.uni.utils.DailyTaskConfigHelper;
import pro.uhalo.uni.utils.LevelConfigHelper;
import pro.uhalo.uni.utils.MemberConfigHelper;
import pro.uhalo.uni.utils.PointsConfigHelper;
import pro.uhalo.uni.utils.TicketRewardConfigHelper;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Setting;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「商业化配置」页面服务：把互相有关联的配置分组聚合到一处读写。
 *
 * <p><b>字段定义不重抄</b>：表单结构直接读 Halo 已解析好的 {@code Setting.spec.forms}，
 * 唯一来源仍是 {@code setting.yaml}。以后加字段只改 YAML，这个页面自动就有；
 * 若在这里另写一份字段清单，两边迟早会不一致（少一个开关、多一个已删除的选项）。</p>
 *
 * <p><b>值读写走同一个 ConfigMap</b>：Halo 的设置值就存在 ConfigMap 里
 * （{@code data[分组名] = 该分组 JSON}），因此本页面与插件设置页写的是同一份数据，
 * 两个入口天然同步，不存在「两套配置」。保存时做<b>读-改-写合并</b>：
 * 只覆盖页面接管的分组，其它分组与页面不认识的字段原样保留。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommercialConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String PLUGIN_NAME = "plugin-uni-halo";
    private static final String FALLBACK_SETTING_NAME = "plugin-uni-halo-settings";
    private static final String FALLBACK_CONFIG_MAP_NAME = "plugin-uni-halo-configMap";

    /** 并发更新配置存储时的重试次数（Halo 用乐观锁，并发改配置会抛 concurrent） */
    private static final int SAVE_MAX_ATTEMPTS = 3;

    private final ReactiveExtensionClient client;

    /** 读取页面数据：分组表单定义 + 当前值 + 关联检查结果。 */
    public Mono<CommercialConfigDTO> load() {
        return resolveNames().flatMap(names -> Mono.zip(
                loadForms(names.settingName()),
                loadGroupValues(names.configMapName()),
                loadTagSlugs())
            .map(tuple -> build(names, tuple.getT1(), tuple.getT2(), tuple.getT3())));
    }

    /**
     * 保存配置。
     *
     * <p>只接受白名单内的分组：ConfigMap 里还有应用配置、Redis 密码等与本页面无关的内容，
     * 放任意 key 进来会变成「通过某个接口改写插件任意配置」的隐患。</p>
     */
    public Mono<CommercialConfigDTO> save(CommercialConfigSaveRequest request) {
        var incoming = request == null || request.getGroups() == null
            ? Map.<String, Map<String, Object>>of() : request.getGroups();
        return saveAttempt(incoming, 0);
    }

    private Mono<CommercialConfigDTO> saveAttempt(Map<String, Map<String, Object>> incoming,
        int attempt) {
        return resolveNames()
            .flatMap(names -> client.fetch(ConfigMap.class, names.configMapName())
                .switchIfEmpty(Mono.error(new WxAuthException(
                    "插件配置存储不存在，请在「应用市场 → 已安装」重新应用本插件")))
                .flatMap(configMap -> {
                    // 读-改-写：以现有数据为基底，只覆盖页面接管的字段，
                    // 绝不整份替换——否则会清掉其它分组与页面不认识的高级字段
                    var merged = new LinkedHashMap<String, String>();
                    if (configMap.getData() != null) {
                        merged.putAll(configMap.getData());
                    }
                    var applied = new LinkedHashSet<String>();
                    for (var entry : incoming.entrySet()) {
                        var group = entry.getKey();
                        if (!CommercialConfigChecks.MANAGED_GROUPS.contains(group)) {
                            log.warn("忽略未接管的配置分组，未写入：{}", group);
                            continue;
                        }
                        if (entry.getValue() == null) {
                            continue;
                        }
                        // 分组内做字段级合并：页面没提交的字段（例如已从表单移除的历史字段、
                        // 或页面因条件隐藏而没渲染的字段）一律保留，不做整组替换
                        merged.put(group, mergeGroupJson(merged.get(group), entry.getValue()));
                        applied.add(group);
                    }
                    if (applied.isEmpty()) {
                        return Mono.error(new WxAuthException("没有需要保存的配置"));
                    }
                    configMap.setData(merged);
                    return client.update(configMap)
                        .doOnNext(saved -> log.info("商业化配置已保存：groups={}", applied));
                }))
            // 回读 ConfigMap 而不是复用刚写的数据：这样返回的检查结果一定基于真实落库的内容
            .flatMap(saved -> load())
            .onErrorResume(ex -> {
                if (isConcurrentModification(ex) && attempt + 1 < SAVE_MAX_ATTEMPTS) {
                    log.warn("配置存储被并发修改，重试第 {} 次", attempt + 1);
                    return saveAttempt(incoming, attempt + 1);
                }
                return Mono.error(ex);
            });
    }

    private record Names(String settingName, String configMapName) {
    }

    /**
     * 解析设置名与配置存储名：以 Plugin 扩展上的声明为准，读不到时回落到内置默认。
     *
     * <p>不硬编码的原因：这两个名字由 {@code plugin.yaml} 的
     * {@code settingName} / {@code configMapName} 决定，站长改过配置时硬编码就会写错地方。</p>
     */
    private Mono<Names> resolveNames() {
        return client.fetch(Plugin.class, PLUGIN_NAME)
            .map(plugin -> {
                var spec = plugin.getSpec();
                return new Names(
                    textOr(spec == null ? null : spec.getSettingName(), FALLBACK_SETTING_NAME),
                    textOr(spec == null ? null : spec.getConfigMapName(), FALLBACK_CONFIG_MAP_NAME));
            })
            .defaultIfEmpty(new Names(FALLBACK_SETTING_NAME, FALLBACK_CONFIG_MAP_NAME))
            .onErrorResume(ex -> {
                log.warn("读取插件声明失败，使用内置默认的设置名与配置存储名：{}", ex.getMessage());
                return Mono.just(new Names(FALLBACK_SETTING_NAME, FALLBACK_CONFIG_MAP_NAME));
            });
    }

    /** 读取表单定义，只保留页面接管的分组，并按业务顺序排列。 */
    private Mono<Map<String, ConfigGroupDTO>> loadForms(String settingName) {
        return client.fetch(Setting.class, settingName)
            .map(setting -> {
                var spec = setting.getSpec();
                var byGroup = new HashMap<String, ConfigGroupDTO>();
                if (spec == null || spec.getForms() == null) {
                    return Map.<String, ConfigGroupDTO>of();
                }
                for (var form : spec.getForms()) {
                    if (form == null || !CommercialConfigChecks.MANAGED_GROUPS.contains(form.getGroup())) {
                        continue;
                    }
                    byGroup.put(form.getGroup(), ConfigGroupDTO.builder()
                        .group(form.getGroup())
                        .label(form.getLabel())
                        .formSchema(form.getFormSchema() == null ? List.of() : form.getFormSchema())
                        .values(JsonNodeFactory.instance.objectNode())
                        .build());
                }
                // 按白名单顺序重排：分组的 YAML 顺序是按历史堆叠的，看板要有稳定且符合业务逻辑的顺序
                var ordered = new LinkedHashMap<String, ConfigGroupDTO>();
                for (var group : CommercialConfigChecks.MANAGED_GROUPS) {
                    var dto = byGroup.get(group);
                    if (dto != null) {
                        ordered.put(group, dto);
                    }
                }
                return ordered;
            })
            .defaultIfEmpty(Map.<String, ConfigGroupDTO>of())
            .onErrorResume(ex -> {
                log.warn("读取配置表单定义失败：{}", ex.getMessage());
                return Mono.just(Map.<String, ConfigGroupDTO>of());
            });
    }

    /** 读取配置值（ConfigMap.data[分组名] 是该分组的 JSON 字符串）。 */
    private Mono<Map<String, JsonNode>> loadGroupValues(String configMapName) {
        return client.fetch(ConfigMap.class, configMapName)
            .map(configMap -> {
                var data = configMap.getData();
                Map<String, JsonNode> values = new HashMap<>();
                if (data == null) {
                    return values;
                }
                for (var group : CommercialConfigChecks.MANAGED_GROUPS) {
                    var raw = data.get(group);
                    if (!StringUtils.hasText(raw)) {
                        continue;
                    }
                    try {
                        values.put(group, OBJECT_MAPPER.readTree(raw));
                    } catch (Exception ex) {
                        // 单个分组解析失败不能拖垮整个页面：其余分组仍应可编辑
                        log.warn("配置分组 {} 的值解析失败，按未配置处理：{}", group, ex.getMessage());
                    }
                }
                return values;
            })
            .map(values -> values)
            .defaultIfEmpty(Map.<String, JsonNode>of())
            .onErrorResume(ex -> {
                log.warn("读取配置值失败：{}", ex.getMessage());
                return Mono.just(Map.<String, JsonNode>of());
            });
    }

    /**
     * 读取 Halo 中实际存在的标签 slug，用于校验「付费标记标签」是否真的存在。
     *
     * <p>读不到时返回 null 而不是空集合：空集合会让检查把「标签全都不存在」误报成配置错误。</p>
     */
    private Mono<Set<String>> loadTagSlugs() {
        return client.listAll(Tag.class, new ListOptions(), null)
            .map(tag -> tag.getSpec() == null || tag.getSpec().getSlug() == null
                ? "" : tag.getSpec().getSlug().trim().toLowerCase(Locale.ROOT))
            .filter(slug -> !slug.isEmpty())
            .collectList()
            .map(list -> (Set<String>) new LinkedHashSet<>(list))
            .onErrorResume(ex -> {
                log.warn("读取标签列表失败，跳过付费标记标签校验：{}", ex.getMessage());
                return Mono.empty();
            });
    }

    private CommercialConfigDTO build(Names names, Map<String, ConfigGroupDTO> forms,
        Map<String, JsonNode> values, Set<String> tagSlugs) {
        var effective = effectiveConfigs(values);
        var groups = new ArrayList<ConfigGroupDTO>();
        for (var entry : forms.entrySet()) {
            var dto = entry.getValue();
            groups.add(ConfigGroupDTO.builder()
                .group(dto.getGroup())
                .label(dto.getLabel())
                .formSchema(dto.getFormSchema())
                .values(values.getOrDefault(entry.getKey(),
                    JsonNodeFactory.instance.objectNode()))
                .effective(effective.get(entry.getKey()))
                .build());
        }
        return CommercialConfigDTO.builder()
            .settingName(names.settingName())
            .configMapName(names.configMapName())
            .groups(List.copyOf(groups))
            // 检查基于「实际生效的配置」而不是原始存档：
            // 原始存档里缺失的字段在运行时会被解析器补上默认值，
            // 直接按原始值检查会把「未配置」误判成「全是 0」，报出一堆实际不存在的故障
            .checks(CommercialConfigChecks.check(effective, tagSlugs))
            .build();
    }

    /**
     * 计算每个分组实际生效的配置：把存档值交给运行时解析器补齐默认值。
     *
     * <p>这里是「同一份真相」的关键：检查与页面展示都必须基于<b>运行时解析器</b>的结果，
     * 否则会出现两种偏差——</p>
     * <ul>
     *   <li>报出实际不存在的故障：未配置的分组被当成「值都是 0」，
     *       于是明明正常工作的每日任务被标成「永远无法完成」，站长怎么配都消不掉；</li>
     *   <li>页面显示的值与系统实际使用的值不一致，一保存就把表单默认值固化下来、悄悄改了行为。</li>
     * </ul>
     *
     * <p>没有独立解析器的分组（虚拟支付、积分商城）沿用存档值：
     * 它们的语义主要由显式列表决定，缺省即「没有配置」。</p>
     */
    private static Map<String, JsonNode> effectiveConfigs(Map<String, JsonNode> values) {
        var result = new HashMap<String, JsonNode>();
        for (var group : CommercialConfigChecks.MANAGED_GROUPS) {
            result.put(group, values.getOrDefault(group, JsonNodeFactory.instance.objectNode()));
        }
        toEffective(result, CommercialConfigChecks.GROUP_POINTS,
            PointsConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_POINTS)));
        toEffective(result, CommercialConfigChecks.GROUP_DAILY_TASK,
            DailyTaskConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_DAILY_TASK)));
        toEffective(result, CommercialConfigChecks.GROUP_TICKET,
            TicketRewardConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_TICKET)));
        toEffective(result, CommercialConfigChecks.GROUP_MEMBER,
            MemberConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_MEMBER)));
        toEffective(result, CommercialConfigChecks.GROUP_CONTENT,
            ContentTicketConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_CONTENT)));
        toEffective(result, CommercialConfigChecks.GROUP_LEVEL,
            LevelConfigHelper.parse(result.get(CommercialConfigChecks.GROUP_LEVEL)));
        return result;
    }

    private static void toEffective(Map<String, JsonNode> sink, String group, Object parsedConfig) {
        try {
            sink.put(group, OBJECT_MAPPER.valueToTree(parsedConfig));
        } catch (Exception ex) {
            // 序列化失败时保留存档值：检查会偏保守（可能多报），但页面不会打不开
            log.warn("生效配置序列化失败，该分组退回存档值：group={}, {}", group, ex.getMessage());
        }
    }

    /**
     * 把提交的分组值合并进已有分组 JSON。
     *
     * <p>只覆盖提交过的字段，其余原样保留。做成字段级而不是整组替换，
     * 是为了让「保存不丢配置」这条承诺真正成立：历史遗留字段、页面未渲染的字段
     * 都不会因为一次保存而消失。</p>
     */
    private static String mergeGroupJson(String existingJson, Map<String, Object> incoming) {
        var node = JsonNodeFactory.instance.objectNode();
        if (StringUtils.hasText(existingJson)) {
            try {
                var parsed = OBJECT_MAPPER.readTree(existingJson);
                if (parsed != null && parsed.isObject()) {
                    node = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
                }
            } catch (Exception ex) {
                // 原值不是合法 JSON（人工改过配置存储）：以本次提交为准并留下告警，
                // 不因为一个坏掉的分组让整次保存失败
                log.warn("分组既有配置不是合法 JSON，将以本次提交覆盖：{}", ex.getMessage());
            }
        }
        for (var entry : incoming.entrySet()) {
            node.set(entry.getKey(), OBJECT_MAPPER.valueToTree(entry.getValue()));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            throw new WxAuthException("配置序列化失败：" + ex.getMessage());
        }
    }

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

    private static String textOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
