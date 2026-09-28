package pro.uhalo.uni.dto;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.utils.DigestCategoryCatalog;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台「日报分类」面板的结论计算。
 *
 * <p>这块的价值全在「说真话」：reuse / map 模式下匹配不上的行是静默跳过的，
 * 面板要是也说「没问题」，站长就会以为配置生效了，直到发现文章没有分类。</p>
 */
class DigestCategoryViewDTOTest {

    private static DigestCategoryConfigDTO row(String key, String label, String target) {
        return new DigestCategoryConfigDTO(key, label, "🏷", label + "相关", target);
    }

    @Test
    void createModeShouldOnlyWarnNothing() {
        var catalog = DigestCategoryCatalog.from(List.of(
            row("wechat", "微信生态", ""),
            row("engineering", "工程", "")));

        var view = DigestCategoryViewDTO.of(catalog, "create", "other", Set.of("engineering"));

        assertThat(view.fromConfig()).isTrue();
        assertThat(view.problemCount()).isZero();
        assertThat(view.items()).hasSize(2);
        // 已有同名分类 → 复用；没有 → 发布时创建
        assertThat(view.items().get(0).exists()).isFalse();
        assertThat(view.items().get(0).message()).contains("自动创建");
        assertThat(view.items().get(1).exists()).isTrue();
        assertThat(view.items().get(1).message()).contains("复用");
    }

    @Test
    void reuseModeShouldFlagRowsThatCannotBeMatched() {
        var catalog = DigestCategoryCatalog.from(List.of(
            row("wechat", "微信生态", ""),
            row("engineering", "工程", "")));

        var view = DigestCategoryViewDTO.of(catalog, "reuse", "drop", Set.of("engineering"));

        assertThat(view.problemCount()).isEqualTo(1);
        assertThat(view.items().get(0).problem()).isTrue();
        assertThat(view.items().get(0).message()).contains("不会自动创建");
        assertThat(view.items().get(1).problem()).isFalse();
        assertThat(view.message()).contains("1 行有问题");
    }

    @Test
    void mapModeShouldCheckTheMappedTarget() {
        var catalog = DigestCategoryCatalog.from(List.of(
            row("wechat", "微信生态", "微信"),
            row("docs", "文档", "技术文档"),
            row("news", "资讯", "")));

        var view = DigestCategoryViewDTO.of(catalog, "map", "other", Set.of("微信"));

        // 目标存在 → 正常；目标不存在 → 跳过；没填映射 → 跳过
        assertThat(view.items().get(0).problem()).isFalse();
        assertThat(view.items().get(0).targetCategory()).isEqualTo("微信");
        assertThat(view.items().get(0).message()).contains("映射到站点分类「微信」");
        assertThat(view.items().get(1).problem()).isTrue();
        assertThat(view.items().get(1).message()).contains("不存在");
        assertThat(view.items().get(2).problem()).isTrue();
        assertThat(view.items().get(2).message()).contains("没填");
        assertThat(view.problemCount()).isEqualTo(2);
    }

    @Test
    void matchingShouldIgnoreCaseLikePublishing() {
        var catalog = DigestCategoryCatalog.from(List.of(row("wechat", "微信生态", "")));

        var view = DigestCategoryViewDTO.of(catalog, "reuse", "other", Set.of("wechat"));

        // 发布时按 name / slug / 显示名做大小写不敏感匹配，面板必须同一口径
        assertThat(view.items().get(0).exists()).isTrue();
        assertThat(view.problemCount()).isZero();
    }

    @Test
    void reuseModeShouldAlsoAcceptChineseDisplayNameAsMatch() {
        var catalog = DigestCategoryCatalog.from(List.of(row("wechat", "微信生态", "")));

        var view = DigestCategoryViewDTO.of(catalog, "reuse", "other", Set.of("微信生态"));

        // 站点上的分类是中文名时，reuse 一样要能命中（否则面板会误报「会被跳过」）
        assertThat(view.items().get(0).exists()).isTrue();
        assertThat(view.problemCount()).isZero();
    }

    @Test
    void emptyListShouldReportBuiltInSource() {
        var view = DigestCategoryViewDTO.of(DigestCategoryCatalog.defaultCatalog(), "create",
            "other", Set.of());

        assertThat(view.fromConfig()).isFalse();
        assertThat(view.source()).contains("内置六类");
        assertThat(view.count()).isEqualTo(6);
        assertThat(view.message()).contains("配置正常");
    }
}
