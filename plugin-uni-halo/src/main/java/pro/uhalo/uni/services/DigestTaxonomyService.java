package pro.uhalo.uni.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestAutoCategoryDTO;
import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.LabelSelector;
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
        return resolveExisting(Category.class, names, "「额外附加」里填的分类");
    }

    /**
     * 解析 AI 分类 key：按站长的 {@code categoryMode} 决定「不存在时怎么办」。
     *
     * <ul>
     *   <li>{@code create}（默认，与升级前一致）：所有 key 命中已有分类就复用，没有就按目录创建；</li>
     *   <li>{@code reuse}：只复用已有分类（按 key / slug / 显示名匹配），匹配不到就不打分类；</li>
     *   <li>{@code map}：按目录里每行的「映射到已有分类」把 key 换成站点分类名，同样只匹配不创建。</li>
     * </ul>
     *
     * <p>后两种模式匹配失败是<b>静默跳过</b>（只留日志）：站长选了「不自动创建」，
     * 那么笔误就不该造出分类——但后台自检会把匹配不上的行列出来，避免他以为配置没生效。</p>
     *
     * @param catalog      分类目录；null 表示内置六类
     * @param categoryMode {@code create} / {@code reuse} / {@code map}
     */
    public Mono<List<String>> resolveAiCategories(List<String> aiCategories,
        DigestCategoryCatalog catalog, String categoryMode) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var wanted = normalizeKeys(aiCategories);
        if (wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        if (DigestConfigHelper.CATEGORY_MODE_MAP.equals(categoryMode)) {
            return resolveExisting(Category.class, mappedTargets(wanted, directory),
                "分类映射里指向的");
        }
        if (DigestConfigHelper.CATEGORY_MODE_REUSE.equals(categoryMode)) {
            return resolveExisting(Category.class, reuseCandidates(wanted, directory),
                "AI 判定出的分类");
        }
        return resolveOrCreateCategories(wanted, directory);
    }

    /**
     * {@code reuse} 模式的候选名字：英文 key 与清单里的中文显示名都拿去匹配。
     *
     * <p>站长的分类体系里往往是中文名（「技术」「微信生态」），而 AI 只会给出英文 key；
     * 只拿 key 去匹配的话，明明有对应分类也会被判为「不存在」而不打分类。</p>
     */
    private static List<String> reuseCandidates(List<String> keys, DigestCategoryCatalog catalog) {
        var candidates = new ArrayList<String>();
        for (var key : keys) {
            candidates.add(key);
            var entry = catalog.find(key);
            if (entry != null && StringUtils.hasText(entry.label())
                && !entry.label().equals(key)) {
                candidates.add(entry.label().trim());
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 把 AI 分类 key 换成站长指定的站点分类名（{@code map} 模式）。
     *
     * <p>清单里没配映射的行会被跳过并留日志：这是站长最容易漏配的地方。</p>
     */
    private static List<String> mappedTargets(List<String> keys, DigestCategoryCatalog catalog) {
        var targets = new ArrayList<String>();
        for (var key : keys) {
            var entry = catalog.find(key);
            var target = entry == null ? "" : entry.targetCategory();
            if (StringUtils.hasText(target)) {
                targets.add(target.trim());
            } else {
                log.warn("[UniHalo][Digest] 分类 {} 没有配置「映射到已有分类」，本次不打该分类"
                    + "（模式为 map，不会自动创建）", key);
            }
        }
        return List.copyOf(targets);
    }

    /** 只匹配已有的通用实现：{@code source} 只用于日志里说明这些名字是哪来的 */
    private Mono<List<String>> resolveExisting(
        Class<? extends run.halo.app.extension.Extension> type, List<String> names,
        String source) {
        if (isEmpty(names)) {
            return Mono.just(List.of());
        }
        return loadCandidates(type).map(candidates -> matchAll(names, candidates, source));
    }

    /** 解析「额外附加」的标签名：只匹配已有，不创建。 */
    public Mono<List<String>> resolveExistingTags(List<String> names) {
        return resolveExisting(Tag.class, names, "「额外附加」里填的标签");
    }

    /** 已有分类的「metadata.name → 显示名」，用于后台预览表里显示人看得懂的分类名。 */
    public Mono<Map<String, String>> categoryLabels() {
        return labels(Category.class);
    }

    /**
     * 已有分类的「可匹配名字」集合（name / slug / 显示名，小写）。
     *
     * <p>给后台自检用：站长在分类清单里填的名字能不能落到站点分类上，
     * 判断口径必须与发布时一致（{@link #matchOne}），否则自检说「没问题」而发布却静默跳过。</p>
     */
    public Mono<java.util.Set<String>> categoryMatchNames() {
        return loadCandidates(Category.class).map(candidates -> {
            var names = new LinkedHashSet<String>();
            for (var candidate : candidates) {
                candidate.matchableNames().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
            }
            return java.util.Set.copyOf(names);
        });
    }

    /**
     * 列出「插件自动创建、且没有任何文章引用」的分类。
     *
     * <p>换掉分类清单之后，老的自动分类（ai-ml / security 这些）会留在站点上：
     * 它们已经不参与日报，却又混在站长的分类体系里。这里把「可以安全删掉的那部分」列出来，
     * 被任何文章引用过的一律不算——删了会让文章的分类指向空。</p>
     */
    public Mono<List<DigestAutoCategoryDTO>> listAutoCreatedCategories() {
        return Mono.zip(autoCreatedCategories(), categoryPostCounts())
            .map(tuple -> tuple.getT1().stream()
                .map(category -> toAutoCategory(category, tuple.getT2(), false))
                .toList());
    }

    /**
     * 删除没有任何文章引用的自动分类；被引用的跳过并如实回报原因。
     *
     * <p>只删带 {@value #AUTO_GENERATED_LABEL} 标签的分类：站长自己建的分类哪怕没文章，
     * 也绝不替他处理。</p>
     */
    public Mono<List<DigestAutoCategoryDTO>> purgeUnusedAutoCreatedCategories() {
        return listAutoCreatedCategories()
            .flatMapMany(Flux::fromIterable)
            .filter(category -> category.getPostCount() == 0)
            .concatMap(category -> client.fetch(Category.class, category.getName())
                .flatMap(client::delete)
                .thenReturn(withDeleted(category))
                .onErrorResume(ex -> {
                    log.warn("[UniHalo][Digest] 清理自动分类失败 name={}：{}", category.getName(),
                        ex.getMessage());
                    return Mono.just(DigestAutoCategoryDTO.builder()
                        .name(category.getName())
                        .displayName(category.getDisplayName())
                        .postCount(0)
                        .deleted(false)
                        .message("删除失败：" + ex.getMessage())
                        .build());
                }))
            .collectList();
    }

    /** 带自动创建标签的分类 */
    private Mono<List<Category>> autoCreatedCategories() {
        var options = new run.halo.app.extension.ListOptions();
        options.setLabelSelector(
            LabelSelector.builder().eq(AUTO_GENERATED_LABEL, "true").build());
        return client.listAll(Category.class, options, null)
            .collectList()
            .onErrorResume(ex -> {
                log.warn("[UniHalo][Digest] 读取自动创建的分类失败，按空列表处理：{}", ex.getMessage());
                return Mono.just(List.of());
            });
    }

    /** 「分类名 → 引用它的文章数」：按全站文章统计（草稿也算，删分类同样会让草稿的分类指向空） */
    private Mono<Map<String, Integer>> categoryPostCounts() {
        Map<String, Integer> empty = new LinkedHashMap<>();
        return client.listAll(run.halo.app.core.extension.content.Post.class,
                new run.halo.app.extension.ListOptions(), null)
            .reduce(empty, (counts, post) -> {
                if (post.getSpec() == null || post.getSpec().getCategories() == null) {
                    return counts;
                }
                for (var name : post.getSpec().getCategories()) {
                    if (StringUtils.hasText(name)) {
                        counts.merge(name, 1, Integer::sum);
                    }
                }
                return counts;
            })
            .onErrorResume(ex -> {
                // 统计不出来时按「都有引用」处理：宁可少删，也不能误删掉还在用的分类
                log.warn("[UniHalo][Digest] 统计分类引用失败，本次不清理任何分类：{}", ex.getMessage());
                return Mono.just(Map.of());
            });
    }

    private static DigestAutoCategoryDTO toAutoCategory(Category category,
        Map<String, Integer> counts, boolean deleted) {
        var name = category.getMetadata() == null ? "" : category.getMetadata().getName();
        var spec = category.getSpec();
        var postCount = counts.getOrDefault(name, 0);
        return DigestAutoCategoryDTO.builder()
            .name(name)
            .displayName(spec == null ? "" : nullToEmpty(spec.getDisplayName()))
            .description(spec == null ? "" : nullToEmpty(spec.getDescription()))
            .createdAt(category.getMetadata() == null
                ? null : category.getMetadata().getCreationTimestamp())
            .postCount(postCount)
            .deleted(deleted)
            .message(postCount > 0
                ? "有 " + postCount + " 篇文章在用，已跳过（如需删除请先在后台改掉这些文章的分类）"
                : (deleted ? "已删除" : "没有文章使用，可以安全删除"))
            .build();
    }

    private static DigestAutoCategoryDTO withDeleted(DigestAutoCategoryDTO category) {
        return DigestAutoCategoryDTO.builder()
            .name(category.getName())
            .displayName(category.getDisplayName())
            .description(category.getDescription())
            .createdAt(category.getCreatedAt())
            .postCount(0)
            .deleted(true)
            .message("已删除")
            .build();
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
        return resolveOrCreateCategories(aiCategories, DigestCategoryCatalog.defaultCatalog());
    }

    /**
     * 解析 AI 分类 key（按站长配置的分类清单）：没有的按清单里的中文名/说明创建。
     *
     * @param catalog 分类目录：创建分类时的显示名与描述取自它
     */
    public Mono<List<String>> resolveOrCreateCategories(List<String> aiCategories,
        DigestCategoryCatalog catalog) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
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
                    return createCategory(key, directory, existing);
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

    private Mono<String> createCategory(String name, DigestCategoryCatalog catalog,
        List<NameCandidate> existing) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(new LinkedHashMap<>(Map.of(AUTO_GENERATED_LABEL, "true")));
        metadata.setCreationTimestamp(Instant.now());
        category.setMetadata(metadata);

        var spec = new Category.CategorySpec();
        // 显示名用中文（Halo 的 name 不允许中文，所以只能靠显示名让人看懂）
        spec.setDisplayName(catalog.labelOf(name));
        spec.setSlug(name);
        // 说明写主题定义而不是「由插件创建」：站点分类页的卡片会把描述显示给访客，
        // 一句「AI、机器学习、大模型相关」才是有效信息
        spec.setDescription(catalog.descriptionOf(name));
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

    private static List<String> matchAll(List<String> wanted, List<NameCandidate> candidates,
        String source) {
        var resolved = new LinkedHashSet<String>();
        for (var name : wanted) {
            var hit = matchOne(name, candidates);
            if (hit == null) {
                log.warn("[UniHalo][Digest] {}「{}」在站点上不存在，已忽略（匹配不到不会自动创建）",
                    source, name);
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

        /** 可用于匹配的名字（去空、去重由调用方处理） */
        List<String> matchableNames() {
            return java.util.stream.Stream.of(name, displayName, slug)
                .filter(StringUtils::hasText)
                .toList();
        }
    }
}
