package pro.uhalo.uni.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动打标的规则测试。
 *
 * <p>这里守的是「不需要站长手工配置分类标签」这件事能不能成立，几条关键性质：</p>
 * <ol>
 *   <li><b>扩展名必须合法</b>：Halo 的 {@code metadata.name} 只允许 RFC1123，
 *       中文不能作 name——分类用 AI 的英文 key、中文只能作显示名，否则创建会直接失败；</li>
 *   <li><b>幂等</b>：已有分类/标签要复用而不是重复建，而且<b>不改动</b>已有项
 *       （站长可能已经把显示名改成自己喜欢的说法）；</li>
 *   <li><b>频次门槛按整轮统计</b>：逐篇算的话每个关键词都只出现一次，门槛永远达不到，
 *       标签一个都建不出来；</li>
 *   <li><b>手填项不自动创建</b>：站长手误写错一个名字不该在站点上多出一个分类。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestTaxonomyServiceTest {

    @Mock
    private ReactiveExtensionClient client;

    private DigestTaxonomyService service() {
        return new DigestTaxonomyService(client);
    }

    private void stubExisting(List<Category> categories, List<Tag> tags) {
        when(client.listAll(any(), any(), any())).thenAnswer(inv -> {
            var type = inv.getArgument(0, Class.class);
            if (type == Category.class) {
                return Flux.fromIterable(categories);
            }
            if (type == Tag.class) {
                return Flux.fromIterable(tags);
            }
            return Flux.empty();
        });
    }

    private void stubCreate() {
        when(client.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static Category category(String name, String displayName, String slug) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        var spec = new Category.CategorySpec();
        spec.setDisplayName(displayName);
        spec.setSlug(slug);
        category.setSpec(spec);
        return category;
    }

    private static Tag tag(String name, String displayName) {
        var tag = new Tag();
        var metadata = new Metadata();
        metadata.setName(name);
        tag.setMetadata(metadata);
        var spec = new Tag.TagSpec();
        spec.setDisplayName(displayName);
        spec.setSlug(name);
        tag.setSpec(spec);
        return tag;
    }

    // ==================== 扩展名合法性（Halo 的硬约束） ====================

    @Test
    void extensionNameShouldFollowRfc1123() {
        assertThat(DigestTaxonomyService.isValidExtensionName("ai-ml")).isTrue();
        assertThat(DigestTaxonomyService.isValidExtensionName("engineering")).isTrue();
        assertThat(DigestTaxonomyService.isValidExtensionName("a")).isTrue();
        // 中文不能作扩展名 —— 这正是「中文只能作显示名」的原因
        assertThat(DigestTaxonomyService.isValidExtensionName("安全")).isFalse();
        assertThat(DigestTaxonomyService.isValidExtensionName("AI-ML")).isFalse();
        assertThat(DigestTaxonomyService.isValidExtensionName("-ai")).isFalse();
        assertThat(DigestTaxonomyService.isValidExtensionName("ai-")).isFalse();
        assertThat(DigestTaxonomyService.isValidExtensionName("ai ml")).isFalse();
        assertThat(DigestTaxonomyService.isValidExtensionName("")).isFalse();
    }

    @Test
    void tagNameShouldSlugifyKeyword() {
        assertThat(DigestTaxonomyService.tagNameOf("Rust")).isEqualTo("rust");
        assertThat(DigestTaxonomyService.tagNameOf("LLM")).isEqualTo("llm");
        assertThat(DigestTaxonomyService.tagNameOf("  vectordb  ")).isEqualTo("vectordb");
        assertThat(DigestTaxonomyService.tagNameOf("C++")).isEqualTo("c");
        assertThat(DigestTaxonomyService.tagNameOf("web-dev")).isEqualTo("web-dev");
        // 纯中文关键词一个可用字符都没有时返回空并跳过：不能回落到占位名，
        // 否则一堆互不相干的关键词会挤进同一个标签
        assertThat(DigestTaxonomyService.tagNameOf("中文关键词")).isEmpty();
        assertThat(DigestTaxonomyService.tagNameOf("  ")).isEmpty();
    }

    // ==================== 频次门槛必须按整轮统计 ====================

    /**
     * 门槛只看「本轮里有多少篇提到它」。
     *
     * <p>这是逐篇发布的命门：每篇只有自己那 2-4 个关键词，若按篇统计，每个词次数都是 1，
     * 门槛永远达不到，标签就一个也不会被创建。</p>
     */
    @Test
    void qualifyingKeywordsShouldCountAcrossTheWholeRun() {
        // 两篇都提到 LLM，只有一篇提到 Rust
        var qualified = DigestTaxonomyService.qualifyingKeywords(
            List.of("LLM", "Rust", "llm", "security"), 2);

        assertThat(qualified).containsExactly("llm");
        assertThat(qualified).doesNotContain("rust", "security");
    }

    @Test
    void qualifyingKeywordsShouldRespectThresholdOne() {
        var qualified = DigestTaxonomyService.qualifyingKeywords(List.of("a", "b", "c"), 1);
        assertThat(qualified).hasSize(3);
    }

    @Test
    void qualifyingKeywordsShouldBeEmptyForBlankInput() {
        assertThat(DigestTaxonomyService.qualifyingKeywords(null, 2)).isEmpty();
        assertThat(DigestTaxonomyService.qualifyingKeywords(List.of("  ", ""), 2)).isEmpty();
    }

    // ==================== 分类：复用优先，不存在则创建 ====================

    /** 已有分类（按 name / slug / 显示名任一命中）要复用，不重复创建、也不改动它。 */
    @Test
    void existingCategoryShouldBeReusedWithoutCreating() {
        stubExisting(List.of(category("ai-ml", "AI / ML", "ai-ml")), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateCategories(List.of("ai-ml")))
            .assertNext(resolved -> assertThat(resolved).containsExactly("ai-ml"))
            .verifyComplete();

        verify(client, never()).create(any());
    }

    /** 站长手工建的分类其 name 往往是 UUID，靠显示名或 slug 也要能认出来。 */
    @Test
    void categoryShouldMatchByDisplayNameOrSlug() {
        stubExisting(List.of(category("a1b2c3d4", "AI / ML", "ai-ml")), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateCategories(List.of("ai-ml")))
            .assertNext(resolved -> assertThat(resolved)
                .as("要复用站长那个分类的 name，而不是再建一个同主题的")
                .containsExactly("a1b2c3d4"))
            .verifyComplete();

        verify(client, never()).create(any());
    }

    /** 不存在时创建：name/slug 用 AI 的英文 key，显示名用中文。 */
    @Test
    void missingCategoryShouldBeCreatedWithChineseDisplayName() {
        stubExisting(List.of(), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateCategories(List.of("security", "AI-ML")))
            .assertNext(resolved -> assertThat(resolved).containsExactly("security", "ai-ml"))
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(Category.class);
        verify(client, org.mockito.Mockito.times(2)).create(captor.capture());
        var created = captor.getAllValues();
        assertThat(created.get(0).getMetadata().getName()).isEqualTo("security");
        assertThat(created.get(0).getSpec().getDisplayName()).isEqualTo("安全");
        assertThat(created.get(0).getSpec().getSlug()).isEqualTo("security");
        // 打上标记，站长一眼能看出哪些是机器建的
        assertThat(created.get(0).getMetadata().getLabels())
            .containsEntry(DigestTaxonomyService.AUTO_GENERATED_LABEL, "true");
        assertThat(created.get(1).getMetadata().getName()).as("key 要归一化成小写").isEqualTo("ai-ml");
    }

    /**
     * 自动建出来的分类，字段必须与「后台手工创建的分类」逐项一致。
     *
     * <p>这不是洁癖：小程序分类页按 {@code spec.hideFromList=false} 过滤，
     * 字段缺失的分类在那条查询里匹配不上，等于自动建出来的分类在分类页里根本看不见；
     * 而描述会直接显示在分类卡片上，写成「由某插件创建」对访客就是噪音。</p>
     */
    @Test
    void autoCreatedCategoryShouldLookLikeAConsoleCreatedOne() {
        stubExisting(List.of(), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateCategories(List.of("ai-ml")))
            .expectNextCount(1)
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(Category.class);
        verify(client).create(captor.capture());
        var spec = captor.getValue().getSpec();

        // 缺了这项，分类在小程序分类页里就查不出来
        assertThat(spec.isHideFromList()).isFalse();
        assertThat(spec.getCover()).isEmpty();
        assertThat(spec.getTemplate()).isEmpty();
        assertThat(spec.getPriority()).isZero();
        assertThat(spec.getChildren()).isEmpty();
        assertThat(spec.isPreventParentPostCascadeQuery()).isFalse();
        // 描述是给访客看的主题定义，不是给站长看的内部说明
        assertThat(spec.getDescription()).isEqualTo(DigestRenderer.categoryDescription("ai-ml"));
        assertThat(spec.getDescription()).isNotBlank();
    }

    /** 创建撞名（并发）时回查复用，而不是把异常抛给发布流程。 */
    @Test
    void createConflictShouldFallBackToLookup() {
        // 第一次列表为空（触发创建），失败后的回查能看到已存在的那个
        when(client.listAll(any(), any(), any()))
            .thenReturn(Flux.empty())
            .thenReturn(Flux.just(category("ai-ml", "AI / ML", "ai-ml")));
        when(client.create(any())).thenReturn(Mono.error(
            new IllegalStateException("Category ai-ml already exists")));

        StepVerifier.create(service().resolveOrCreateCategories(List.of("ai-ml")))
            .assertNext(resolved -> assertThat(resolved).containsExactly("ai-ml"))
            .verifyComplete();
    }

    /** 非法 key 直接跳过，不尝试创建。 */
    @Test
    void invalidCategoryKeyShouldBeSkipped() {
        stubExisting(List.of(), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateCategories(List.of("安全", "-bad", "")))
            .assertNext(resolved -> assertThat(resolved).isEmpty())
            .verifyComplete();

        verify(client, never()).create(any());
    }

    // ==================== 标签 ====================

    @Test
    void missingTagShouldBeCreatedAndExistingReused() {
        stubExisting(List.of(), List.of(tag("llm", "LLM")));
        stubCreate();

        StepVerifier.create(service().resolveOrCreateTags(List.of("Rust", "LLM")))
            .assertNext(resolved -> assertThat(resolved).containsExactlyInAnyOrder("rust", "llm"))
            .verifyComplete();

        var captor = ArgumentCaptor.forClass(Tag.class);
        verify(client, org.mockito.Mockito.times(1)).create(captor.capture());
        var created = captor.getValue();
        assertThat(created.getMetadata().getName()).isEqualTo("rust");
        assertThat(created.getSpec().getDisplayName()).as("显示名保留原写法").isEqualTo("Rust");
        assertThat(created.getSpec().getColor()).as("颜色必须是合法色值，否则 Halo 校验不过")
            .matches("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    }

    /** 中文关键词跳过后不应影响同批里其它关键词。 */
    @Test
    void chineseKeywordShouldBeSkippedWithoutAffectingOthers() {
        stubExisting(List.of(), List.of());
        stubCreate();

        StepVerifier.create(service().resolveOrCreateTags(List.of("性能优化", "rust")))
            .assertNext(resolved -> assertThat(resolved).containsExactly("rust"))
            .verifyComplete();
    }

    // ==================== 手填项：只匹配、不创建 ====================

    /**
     * 手填的「额外附加」匹配不到时只忽略，不创建。
     *
     * <p>手误写错一个名字就在站点上多出一个分类，属于意料之外的副作用——
     * 自动创建只留给机器判定的固定枚举。</p>
     */
    @Test
    void manualNamesShouldNeverBeCreated() {
        stubExisting(List.of(category("tech", "技术", "tech")), List.of());

        StepVerifier.create(service().resolveExistingCategories(List.of("技术", "手误的名字")))
            .assertNext(resolved -> assertThat(resolved).containsExactly("tech"))
            .verifyComplete();

        verify(client, never()).create(any());
    }

    /** 读取已有分类失败时按空集合处理，不能因此让发布失败。 */
    @Test
    void readFailureShouldNotBreakPublishing() {
        when(client.listAll(any(), any(), any()))
            .thenReturn(Flux.error(new IllegalStateException("db down")));

        StepVerifier.create(service().resolveExistingCategories(List.of("技术")))
            .assertNext(resolved -> assertThat(resolved).isEmpty())
            .verifyComplete();
    }
}
