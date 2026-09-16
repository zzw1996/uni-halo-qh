package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.services.DigestRenderer;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日报的「分类 / 标签」归类：把 AI 产出的分类与关键词落到站点真实存在的分类与标签上，
 * <b>不存在就自动创建</b>，因此站长不需要在设置里手工填分类标签名。
 *
 * <p>为什么要自动创建：早前发布链路只做「按名字匹配已有」，匹配不到就静默忽略。
 * 结果是站长必须先去 Halo 后台把分类标签建好、再把名字一字不差地填进插件设置——
 * 而这个插件里的主题分类（AI / ML、安全、工程…）本来就是机器判定的固定六类，
 * 让人手抄一遍既没必要也必然出错。</p>
 *
 * <p><b>走扩展客户端而不是 HTTP</b>：创建分类/标签不需要个人令牌，也不涉及任何 RBAC 改动，
 * 与插件里创建 Post / Snapshot 用的是同一条路（{@code DigestPostWriter}）。</p>
 *
 * <p><b>Halo 的硬约束</b>：扩展的 {@code metadata.name} 必须匹配 RFC1123
 * （只能小写字母、数字、{@code -} 与 {@code .}），因此<b>中文只能作显示名</b>：
 * 分类的 name/slug 用 AI 的英文分类 key（{@code ai-ml}），显示名用中文（AI / ML）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DigestTaxonomyService {

    /**
     * 自动创建的分类/标签会打上这个标签。
     *
     * <p>让站长一眼看出哪些是机器建的：他可能不想让这些混进自己的分类体系，
     * 也可能想改显示名甚至隐藏它们——都能按这个标签筛。</p>
     */
    public static final String AUTO_GENERATED_LABEL = "uni.uhalo.pro/auto-generated";

    /** 标签颜色（Halo 校验必须是合法十六进制色值） */
    private static final String TAG_COLOR = "#667eea";

    /** 自动标签名的长度上限，超长的关键词截断后仍然可用 */
    private static final int MAX_NAME_LENGTH = 40;

    private final ReactiveExtensionClient client;

    /**
     * 解析「额外附加」的分类名：只匹配已有，<b>不创建</b>。
     *
     * <p>这是站长手填的那一项。手填错一个名字就悄悄多出一个分类，属于意料之外的副作用，
     * 所以这里仍然只匹配；自动创建只发生在 AI 分类上（那是机器判定的固定枚举，不会写错）。</p>
     */
    public Mono<List<String>> resolveExistingCategories(List<String> names) {
        if (isEmpty(names)) {
            return Mono.just(List.of());
        }
        return loadCandidates(Category.class)
            .map(candidates -> matchAll(names, candidates));
    }

    /** 解析「额外附加」的标签名：只匹配已有，不创建。 */
    public Mono<List<String>> resolveExistingTags(List<String> names) {
        if (isEmpty(names)) {
            return Mono.just(List.of());
        }
        return loadCandidates(Tag.class)
            .map(candidates -> matchAll(names, candidates));
    }

    /** 已有分类的「metadata.name → 显示名」，用于后台预览表里显示人看得懂的分类名。 */
    public Mono<Map<String, String>> categoryLabels() {
        return labels(Category.class);
    }

    /** 已有标签的「metadata.name → 显示名」。 */
    public Mono<Map<String, String>> tagLabels() {
        return labels(Tag.class);
    }

    private Mono<Map<String, String>> labels(
        Class<? extends run.halo.app.extension.Extension> type) {
        return client.listAll(type, new run.halo.app.extension.ListOptions(), null)
            .map(extension -> {
                var metadata = extension.getMetadata();
                var name = metadata == null || metadata.getName() == null ? "" : metadata.getName();
                String displayName = "";
                if (extension instanceof Category category && category.getSpec() != null) {
                    displayName = nullToEmpty(category.getSpec().getDisplayName());
                } else if (extension instanceof Tag tag && tag.getSpec() != null) {
                    displayName = nullToEmpty(tag.getSpec().getDisplayName());
                }
                return Map.entry(name, StringUtils.hasText(displayName) ? displayName : name);
            })
            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取已有 {} 的显示名失败，按原始名展示：{}",
                    type.getSimpleName(), ex.getMessage());
                return Mono.just(Map.of());
            });
    }

    /**
     * 解析 AI 分类 key：命中已有分类就复用，没有就按 key 创建。
     *
     * <p>复用优先于创建，且**命中后不改动已有分类的任何字段**——
     * 站长可能已经把显示名改成了自己喜欢的说法，插件不该覆盖它。</p>
     */
    public Mono<List<String>> resolveOrCreateCategories(List<String> aiCategories) {
        var wanted = normalizeKeys(aiCategories);
        if (wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        return loadCandidates(Category.class)
            .flatMap(existing -> Flux.fromIterable(wanted)
                .concatMap(key -> {
                    var hit = matchOne(key, existing);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                    return createCategory(key, existing);
                })
                .collectList());
    }

    /**
     * 计算本轮「够格成为标签」的关键词集合。
     *
     * <p><b>必须按整轮统计，不能逐篇算</b>：逐篇发布时每篇只有自己的 2-4 个关键词，
     * 每个词的出现次数都是 1，门槛永远达不到，标签就一个也建不出来。</p>
     *
     * <p>门槛本身也是必须的：日报一轮产出 5 篇，若关键词全建成标签，
     * 一次运行就能给站点造出十几个只出现一次的标签，几次下来标签墙就没法看了。
     * 默认门槛 2，即「至少两篇文章都在讲这个词」才值得成为一个标签。</p>
     *
     * @return 达标关键词的小写形式，供逐篇筛选自己的关键词用
     */
    public static java.util.Set<String> qualifyingKeywords(Collection<String> allKeywords,
        int minCount) {
        var threshold = Math.max(1, minCount);
        var qualified = new LinkedHashSet<String>();
        countKeywords(allKeywords).forEach((keyword, count) -> {
            if (count >= threshold) {
                qualified.add(keyword);
            }
        });
        return qualified;
    }

    /** 解析并创建标签：入参应当是已按门槛筛过的关键词。 */
    public Mono<List<String>> resolveOrCreateTags(Collection<String> keywords) {
        var wanted = new ArrayList<String>();
        if (keywords != null) {
            keywords.forEach(keyword -> {
                if (StringUtils.hasText(keyword)) {
                    wanted.add(keyword.trim());
                }
            });
        }
        if (wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        return loadCandidates(Tag.class)
            .flatMap(existing -> Flux.fromIterable(wanted)
                .concatMap(keyword -> {
                    var slug = tagNameOf(keyword);
                    if (!StringUtils.hasText(slug)) {
                        // 纯中文等无法构成合法扩展名的关键词：跳过并留下日志，
                        // 否则站长会以为标签建好了，回头在站点上找不到
                        log.info("[UniHalo][Digest] 关键词「{}」无法构成合法标签名（Halo 的标签名"
                            + "只允许小写字母、数字、- 与 .），已跳过", keyword);
                        return Mono.<String>empty();
                    }
                    var hit = matchOne(keyword, existing);
                    if (hit == null) {
                        hit = matchOne(slug, existing);
                    }
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                    return createTag(slug, keyword.trim(), existing);
                })
                .collectList());
    }

    // ── 创建 ──

    private Mono<String> createCategory(String name, List<NameCandidate> existing) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(new LinkedHashMap<>(Map.of(AUTO_GENERATED_LABEL, "true")));
        metadata.setCreationTimestamp(Instant.now());
        category.setMetadata(metadata);

        var spec = new Category.CategorySpec();
        // 显示名用中文（Halo 的 name 不允许中文，所以只能靠显示名让人看懂）
        spec.setDisplayName(DigestRenderer.categoryLabel(name));
        spec.setSlug(name);
        // 说明写主题定义而不是「由插件创建」：站点分类页的卡片会把描述显示给访客，
        // 一句「AI、机器学习、大模型相关」才是有效信息
        spec.setDescription(DigestRenderer.categoryDescription(name));
        // 下面这几项必须显式写成与「后台手工创建的分类」一模一样：
        //
        // hideFromList 尤其关键——小程序分类页按 spec.hideFromList=false 过滤，
        // 字段缺失（null）的分类在那条查询里匹配不上，等于自动建出来的分类
        // 在分类页里根本看不见。其余几项同理：官方控制台建分类时都会写全，
        // 缺字段虽然还能跑，但控制台的分类面板与筛选行为会与手工分类不一致。
        spec.setCover("");
        spec.setTemplate("");
        spec.setPriority(0);
        spec.setChildren(List.of());
        spec.setPreventParentPostCascadeQuery(false);
        spec.setHideFromList(false);
        category.setSpec(spec);

        return client.create(category)
            .map(created -> {
                log.info("[UniHalo][Digest] 已自动创建分类：{}（{}）", name, spec.getDisplayName());
                return name;
            })
            .onErrorResume(ex -> {
                // 并发下可能已被另一个请求创建：回查一次而不是报错
                log.info("[UniHalo][Digest] 分类 {} 创建未成功（可能已存在），改为复用：{}",
                    name, ex.getMessage());
                return loadCandidates(Category.class)
                    .map(candidates -> {
                        var hit = matchOne(name, candidates);
                        return hit == null ? name : hit;
                    });
            });
    }

    private Mono<String> createTag(String name, String displayName, List<NameCandidate> existing) {
        var tag = new Tag();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(new LinkedHashMap<>(Map.of(AUTO_GENERATED_LABEL, "true")));
        metadata.setCreationTimestamp(Instant.now());
        tag.setMetadata(metadata);

        var spec = new Tag.TagSpec();
        spec.setDisplayName(displayName);
        spec.setSlug(name);
        // 官方控制台建标签时不给颜色，但 spec.color 是 Halo 的正式字段：
        // 给了颜色，文章页的标签胶囊才不是一片没有底色的灰字
        spec.setColor(TAG_COLOR);
        spec.setCover("");
        tag.setSpec(spec);

        return client.create(tag)
            .map(created -> {
                log.info("[UniHalo][Digest] 已自动创建标签：{}（{}）", name, displayName);
                return name;
            })
            .onErrorResume(ex -> {
                log.info("[UniHalo][Digest] 标签 {} 创建未成功（可能已存在），改为复用：{}",
                    name, ex.getMessage());
                return loadCandidates(Tag.class)
                    .map(candidates -> {
                        var hit = matchOne(name, candidates);
                        return hit == null ? name : hit;
                    });
            });
    }

    // ── 候选匹配 ──

    /**
     * 读取已有的分类/标签作为匹配候选。
     *
     * <p>一次读全量再在内存里匹配，而不是逐个 {@code fetch}：分类标签是低频变更的小集合，
     * 一次列表比按名字逐个查更省往返，而且能同时按 name / slug / 显示名匹配——
     * 站长手工建的分类，其 name 往往是 UUID，只有靠显示名或 slug 才认得出来。</p>
     */
    private Mono<List<NameCandidate>> loadCandidates(
        Class<? extends run.halo.app.extension.Extension> type) {
        return client.listAll(type, new run.halo.app.extension.ListOptions(), null)
            .map(extension -> {
                var metadata = extension.getMetadata();
                var name = metadata == null || metadata.getName() == null ? "" : metadata.getName();
                String displayName = "";
                String slug = "";
                if (extension instanceof Category category && category.getSpec() != null) {
                    displayName = nullToEmpty(category.getSpec().getDisplayName());
                    slug = nullToEmpty(category.getSpec().getSlug());
                } else if (extension instanceof Tag tag && tag.getSpec() != null) {
                    displayName = nullToEmpty(tag.getSpec().getDisplayName());
                    slug = nullToEmpty(tag.getSpec().getSlug());
                }
                return new NameCandidate(name, displayName, slug);
            })
            .collectList()
            .onErrorResume(ex -> {
                // 读不到已有分类/标签时不要让发布失败：按「一个都没有」处理，
                // 后续创建若撞名会自动回查复用
                log.warn("[UniHalo][Digest] 读取已有 {} 失败，按空集合处理：{}",
                    type.getSimpleName(), ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 任意一个名字命中即可（name / slug / 显示名，忽略大小写与首尾空格）。 */
    private static String matchOne(String wanted, List<NameCandidate> candidates) {
        if (!StringUtils.hasText(wanted)) {
            return null;
        }
        var target = wanted.trim();
        for (var candidate : candidates) {
            if (candidate.matches(target)) {
                return candidate.name();
            }
        }
        return null;
    }

    private static List<String> matchAll(List<String> wanted, List<NameCandidate> candidates) {
        var resolved = new LinkedHashSet<String>();
        for (var name : wanted) {
            var hit = matchOne(name, candidates);
            if (hit == null) {
                log.warn("[UniHalo][Digest] 「额外附加」里填的 {} 在站点上不存在，已忽略"
                    + "（自动分类由 AI 判定，不需要手工填）", name);
                continue;
            }
            resolved.add(hit);
        }
        return List.copyOf(resolved);
    }

    // ── 工具 ──

    /** 归一化 AI 分类 key：去空、去重、转小写，并挡掉不合法的扩展名。 */
    private static List<String> normalizeKeys(List<String> values) {
        var keys = new LinkedHashSet<String>();
        if (values != null) {
            for (var value : values) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                var key = value.trim().toLowerCase(Locale.ROOT);
                if (isValidExtensionName(key)) {
                    keys.add(key);
                } else {
                    log.warn("[UniHalo][Digest] 分类 key {} 不合法（Halo 的扩展名只允许小写字母、"
                        + "数字、- 与 .），已跳过", value);
                }
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 关键词 → 标签名。
     *
     * <p>与图片文件名那种「非法字符一律换成连字符」的做法不同：关键词里若一个可用字符都没有
     * （例如纯中文词），这里直接返回空并跳过，而不是回落到一个占位名——
     * 那样会把一堆互不相干的关键词塞进同一个标签。</p>
     */
    static String tagNameOf(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "";
        }
        var name = keyword.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).replaceAll("-+$", "");
        }
        return name;
    }

    /** 关键词频次统计：忽略大小写与首尾空格，保留原始写法作为显示名。 */
    public static Map<String, Integer> countKeywords(Collection<String> keywords) {
        var counts = new LinkedHashMap<String, Integer>();
        if (keywords == null) {
            return counts;
        }
        for (var keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            var display = keyword.trim();
            var key = display.toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /** Halo 扩展名规则：RFC1123 子域（小写字母、数字、-、.） */
    static boolean isValidExtensionName(String name) {
        return StringUtils.hasText(name)
            && name.length() <= MAX_NAME_LENGTH
            && name.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$");
    }

    private static boolean isEmpty(Collection<String> values) {
        return values == null || values.stream().noneMatch(StringUtils::hasText);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 已有的分类/标签：三个可比对的名字。 */
    private record NameCandidate(String name, String displayName, String slug) {
        boolean matches(String target) {
            return target.equalsIgnoreCase(name)
                || target.equalsIgnoreCase(displayName)
                || target.equalsIgnoreCase(slug);
        }
    }
}
