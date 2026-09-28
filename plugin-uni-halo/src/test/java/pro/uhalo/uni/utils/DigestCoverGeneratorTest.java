package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标题卡片封面：能出图、出的是真 PNG，且中文标题真的被画在了图上。
 */
class DigestCoverGeneratorTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void renderCardShouldProduceAPngImage() throws Exception {
        var bytes = DigestCoverGenerator.renderCard("ai-ml", "AI / ML", "大模型推理优化实践",
            "Simon Willison's Weblog", "2026-09-26", "测试站");

        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(bytes).startsWith(PNG_MAGIC);
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        // 1200×675：文章头图与小程序卡片墙都能用同一张
        assertThat(image.getWidth()).isEqualTo(1200);
        assertThat(image.getHeight()).isEqualTo(675);
    }

    /**
     * 中文标题必须真的画出来。
     *
     * <p>这条用例是「服务器没装中文字体」那个线上问题的守门人：只断言「生成了 PNG」
     * 是没用的——一张只有渐变底的空卡片同样会通过。这里比较「有中文标题」与「没有标题」
     * 两张图的像素差异，差异够大才说明字确实落到了图上。</p>
     */
    @Test
    void chineseTitleShouldActuallyBePainted() throws Exception {
        var withTitle = render(renderCardBytes("人工智能日报：大模型推理与安全"));
        var withoutTitle = render(renderCardBytes(""));

        var diff = differingPixels(withTitle, withoutTitle);
        assertThat(diff).as("中文标题应该画出明显数量的像素").isGreaterThan(2000);
    }

    /** 分类名、日期、来源都是中文时同样要能画出来 */
    @Test
    void chineseCategoryAndDateShouldBePainted() throws Exception {
        var chinese = render(renderCardBytes("安全", "标题", "来源", "2026-09-26", "我的站点"));
        var plain = render(renderCardBytes("", "标题", "", "2026-09-26", ""));

        assertThat(differingPixels(chinese, plain)).isGreaterThan(500);
    }

    @Test
    void renderCardShouldSurviveMissingText() throws Exception {
        // 标题/来源都为空（RSS 缺字段时会这样）不能抛异常，卡片仍然要出得来
        var bytes = DigestCoverGenerator.renderCard(null, null, null, null, "2026-09-26", null);

        assertThat(bytes).startsWith(PNG_MAGIC);
    }

    @Test
    void emptyTitleShouldStillYieldAValidImage() throws Exception {
        var bytes = DigestCoverGenerator.renderCard("security", "安全", "   ", "", "", "");

        assertThat(ImageIO.read(new ByteArrayInputStream(bytes))).isNotNull();
    }

    @Test
    void bundledFontShouldCoverCommonChinese() throws Exception {
        // 内置字体随插件分发：服务器上没有任何中文字体时，卡片也能画中文
        assertThat(DigestCoverGenerator.cjkAvailable()).isTrue();
        assertThat(DigestCoverGenerator.fontSourceLabel()).contains("内置字体");

        var bundled = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
            getClass().getResourceAsStream(DigestCoverGenerator.BUNDLED_FONT_RESOURCE));
        // 生僻字（CJK 扩展 A 区，不在 6763 常用字子集里）被逐字丢掉，其余照常保留
        assertThat(DigestCoverGenerator.filterDisplayable(bundled, "中文㐀标题")).isEqualTo("中文标题");
        assertThat(DigestCoverGenerator.filterDisplayable(bundled, "安全")).isEqualTo("安全");
    }

    @Test
    void asciiOnlyShouldStripCharactersTheFontCannotShow() {
        // 最后一道降级（内置字体也加载失败时）的文案：只留 ASCII 可见字符
        assertThat(DigestCoverGenerator.asciiOnly("AI 日报：Rust 与编译器")).isEqualTo("AI Rust");
        assertThat(DigestCoverGenerator.asciiOnly("中文标题")).isEmpty();
        assertThat(DigestCoverGenerator.asciiOnly(null)).isEmpty();
    }

    @Test
    void hueShouldBeStablePerCategory() {
        // 同一分类每天一个颜色：配色由 key 决定，不能带随机
        assertThat(DigestCoverGenerator.hueOf("ai-ml"))
            .isEqualTo(DigestCoverGenerator.hueOf("ai-ml"));
        assertThat(DigestCoverGenerator.hueOf("ai-ml"))
            .isBetween(0f, 1f);
        assertThat(DigestCoverGenerator.hueOf(null)).isBetween(0f, 1f);
        // 线上第一版里「安全」和「工程」被映射成几乎一样的颜色，现在按 24 段色阶取模
        assertThat(DigestCoverGenerator.hueOf("security"))
            .isNotEqualTo(DigestCoverGenerator.hueOf("engineering"));
    }

    @Test
    void latinTitlesShouldBreakAtWordBoundaries() {
        var text = "Why transformers still win on long context";
        var lines = DigestCoverGenerator.wrapLines(text,
            new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 58), 1000, 2);

        assertThat(lines).hasSize(2);
        // 除最后一行外都必须断在空格处：断点后面紧跟一个空格，单词不会被劈成两半
        for (var i = 0; i < lines.size() - 1; i++) {
            assertThat(text).contains(lines.get(i) + " ");
        }
    }

    @Test
    void chineseTitlesShouldBreakPerCharacter() {
        // 中文没有空格可断：必须退化成逐字断行，且不超过设定的行数
        var lines = DigestCoverGenerator.wrapLines(
            "一个长达四十个字的超长中文标题会被折成两行并在这里被省略号截断掉后面的内容",
            new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 58), 1000, 2);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).endsWith("…");
    }

    // ── 脚手架 ──

    private static byte[] renderCardBytes(String title) throws Exception {
        return DigestCoverGenerator.renderCard("ai-ml", "", title, "", "2026-09-26", "");
    }

    private static byte[] renderCardBytes(String categoryLabel, String title, String source,
        String date, String site) throws Exception {
        return DigestCoverGenerator.renderCard("security", categoryLabel, title, source, date, site);
    }

    private static BufferedImage render(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int differingPixels(BufferedImage left, BufferedImage right) {
        var diff = 0;
        for (var x = 0; x < left.getWidth(); x++) {
            for (var y = 0; y < left.getHeight(); y++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return diff;
    }
}
