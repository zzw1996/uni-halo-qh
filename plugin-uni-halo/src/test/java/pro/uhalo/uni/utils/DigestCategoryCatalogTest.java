package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.DigestCategoryConfigDTO;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报分类目录：站长可配的分类清单。
 *
 * <p>这里守三件事：</p>
 * <ol>
 *   <li><b>留空 = 升级前的行为</b>：清单为空（或全是脏数据）时必须等价于内置六类，
 *       否则老站点升级完日报就变了样；</li>
 *   <li><b>配置是不可信输入</b>：非法 key、重复 key、空名字、超量都要被消毒掉，
 *       不能让一行脏数据把整轮日报打崩（key 会拼进 prompt、slug 与扩展名）；</li>
 *   <li><b>旧分类仍能显示</b>：清单外的 key（例如改造前生成的旧分类、或站点既有的中文分类）
 *       要有名字与图标兜底，不能渲染成空白标题。</li>
 * </ol>
 */
class DigestCategoryCatalogTest {

    private static DigestCategoryConfigDTO row(String key, String label, String emoji,
        String description, String target) {
        return new DigestCategoryConfigDTO(key, label, emoji, description, target);
    }

    @Test
    void emptyConfigShouldFallBackToBuiltInSix() {
        var catalog = DigestCategoryCatalog.from(List.of());

        assertThat(catalog.keys())
            .containsExactly("ai-ml", "security", "engineering", "tools", "opinion", "other");
        assertThat(catalog.fromConfig()).isFalse();
        // 内置目录与「清单留空」必须是同一份东西，而不是两份长得像的
        assertThat(catalog.keys()).isEqualTo(DigestCategoryCatalog.defaultCatalog().keys());
        assertThat(catalog.labelOf("ai-ml")).isEqualTo("AI / ML");
        assertThat(catalog.emojiOf("ai-ml")).isEqualTo("🤖");
    }

    @Test
    void nullConfigShouldFallBackToBuiltInSix() {
        assertThat(DigestCategoryCatalog.from(null).fromConfig()).isFalse();
        assertThat(DigestCategoryCatalog.from(null).keys()).hasSize(6);
    }

    @Test
    void dirtyRowsShouldBeSanitized() {
        var rows = new ArrayList<DigestCategoryConfigDTO>();
        rows.add(row("WeChat", "", "", "", null));          // 大写 → 归一化成小写，名字/说明补默认
        rows.add(row("wechat", "重复的", "🔁", "", null));    // 重复 key → 只取第一条
        rows.add(row("bad key!", "非法", "❌", "", null));     // 非法字符 → 丢弃
        rows.add(row("中文key", "非法", "❌", "", null));      // 中文不能作扩展名 → 丢弃
        rows.add(row("", "空 key", "❌", "", null));          // 空 key → 丢弃
        rows.add(row("docs", "文档", "", "", "技术文档"));      // emoji 留空 → 兜底图标

        var catalog = DigestCategoryCatalog.from(rows);

        assertThat(catalog.keys()).containsExactly("wechat", "docs");
        assertThat(catalog.fromConfig()).isTrue();
        // label 留空 → 用 key 兜底（大写已被归一化成小写，扩展名不允许大写）
        assertThat(catalog.labelOf("wechat")).isEqualTo("wechat");
        // emoji 留空 → 统一兜底图标
        assertThat(catalog.emojiOf("wechat")).isEqualTo("📝");
        assertThat(catalog.emojiOf("docs")).isEqualTo("📝");
        assertThat(catalog.find("docs").targetCategory()).isEqualTo("技术文档");
    }

    @Test
    void tooManyRowsShouldBeTruncated() {
        var rows = new ArrayList<DigestCategoryConfigDTO>();
        for (var i = 0; i < DigestCategoryCatalog.MAX_CATEGORIES + 5; i++) {
            rows.add(row("cat-" + i, "分类" + i, "🏷", "说明" + i, null));
        }

        var catalog = DigestCategoryCatalog.from(rows);

        assertThat(catalog.keys()).hasSize(DigestCategoryCatalog.MAX_CATEGORIES);
        assertThat(catalog.keys().get(0)).isEqualTo("cat-0");
    }

    @Test
    void normalizeShouldOnlyAcceptKeysInTheList() {
        var catalog = DigestCategoryCatalog.from(List.of(row("wechat", "微信生态", "💬", "微信相关", "")));

        assertThat(catalog.normalize("WeChat")).isEqualTo("wechat");
        assertThat(catalog.normalize("ai-ml")).isEmpty();
        assertThat(catalog.contains("wechat")).isTrue();
        assertThat(catalog.contains("ai-ml")).isFalse();
        // 清单里没有 other 时，「兜底分类」这件事就不成立（等效于不打分类）
        assertThat(catalog.fallbackKey()).isEmpty();

        var withOther = DigestCategoryCatalog.from(List.of(
            row("wechat", "微信", "💬", "", ""),
            row("other", "其他", "📝", "", "")));
        assertThat(withOther.fallbackKey()).isEqualTo("other");
    }

    @Test
    void unknownKeysShouldStillRenderWithBuiltInOrRawName() {
        var catalog = DigestCategoryCatalog.from(List.of(row("wechat", "微信生态", "💬", "", "")));

        // 改造前生成的旧分类：清单里没有，但名字/图标要能兜底出来
        assertThat(catalog.labelOf("ai-ml")).isEqualTo("AI / ML");
        assertThat(catalog.emojiOf("security")).isEqualTo("🔒");
        // 站点上既有的中文分类名：直接用原名，不能显示成空白
        assertThat(catalog.labelOf("微信小程序")).isEqualTo("微信小程序");
        assertThat(catalog.emojiOf("微信小程序")).isEqualTo("📝");
        // 空 key（AI 没给分类 / 策略是 drop）也要有个说法，避免渲染出空标题
        assertThat(catalog.labelOf("")).isEqualTo(DigestCategoryCatalog.UNCATEGORIZED_LABEL);
        assertThat(catalog.descriptionOf("")).isEqualTo(DigestCategoryCatalog.UNCATEGORIZED_LABEL);
    }

    @Test
    void promptSectionShouldListConfiguredRows() {
        var catalog = DigestCategoryCatalog.from(List.of(
            row("wechat", "微信生态", "💬", "小程序、公众号、微信支付相关", "微信"),
            row("other", "其他", "📝", "都不太适合的", "")));

        assertThat(catalog.promptSection())
            .isEqualTo("- wechat: 小程序、公众号、微信支付相关\n- other: 都不太适合的\n");
        assertThat(catalog.sampleKey()).isEqualTo("wechat");
    }

    @Test
    void promptSectionShouldFallBackToLabelWhenDescriptionMissing() {
        var catalog = DigestCategoryCatalog.from(List.of(row("docs", "文档", "📚", "", "")));

        // 说明留空时用中文名兜底，prompt 里不会出现「- docs: 」这种半截行
        assertThat(catalog.promptSection()).isEqualTo("- docs: 文档\n");
    }
}
