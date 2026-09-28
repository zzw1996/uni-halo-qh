package pro.uhalo.uni.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 分类封面兜底：服务端绘制一张标题卡片（分类色 + 分类名 + 标题 + 来源与日期）。
 *
 * <p>为什么需要它：头图来自 RSS 内嵌图或文章页的 <code>og:image</code>，实测只有六成文章配得到。
 * 站长开了「每个分类至少一篇」之后，某个分类很可能只剩没图的文章——不发就断了保底，
 * 发无图又违背「只发布带封面图」。这张卡片是第三条路：不伪造正文内容，只补一张招牌式的封面，
 * 让每个分类的保底名额都能带封面发出去。</p>
 *
 * <p><b>字体不依赖服务器</b>：插件内置了一份 Noto Sans SC 子集（6763 个常用汉字 + 标点 + 拉丁字母，
 * 约 1.4 MB，OFL 许可，见 {@code digest/cover-font-LICENSE.txt}），所以即使 Halo 容器里
 * 一个中文字体都没有，卡片上的中文标题也能正常画出来——线上实测过：很多精简镜像里
 * 只有 DejaVu 这类无中文字形的字体，「装字体」这一步对很多站长来说并不容易完成。</p>
 *
 * <p>字体选择顺序：<b>内置字体</b>（各站点观感一致、保证有中文）→ 系统上覆盖更全的 CJK 字体
 * （内置子集缺字时用它兜）→ 逻辑字体（极端情况）。正文逐字过滤：某个字谁都画不出来时
 * 只丢掉那个字，而不是整段不画、也不画成方框。</p>
 *
 * <p>纯 Java2D 实现，不依赖显示设备（只用 {@link BufferedImage}，不碰 Swing/Toolkit），
 * 因此可以在无头容器里安全运行。</p>
 */
@Slf4j
public final class DigestCoverGenerator {

    /** 1200×675 = 16:9：文章头图与小程序卡片都适用 */
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 675;

    private static final int MARGIN = 88;

    private static final String FORMAT = "png";

    private static final String LATIN_FALLBACK = "AI DIGEST";

    /** 内置字体（随插件分发，任何服务器上都有中文字形） */
    static final String BUNDLED_FONT_RESOURCE = "/digest/cover-font.otf";

    private static final String BUNDLED_FONT_NAME = "插件内置字体（Noto Sans SC 子集）";

    /**
     * 系统 CJK 字体候选（内置字体缺字时才用到）：覆盖 Halo 官方镜像常见的 Noto/文泉驿、
     * Windows 的雅黑、macOS 的苹方。
     */
    private static final List<String> CJK_CANDIDATES = List.of(
        "Noto Sans CJK SC", "Noto Sans SC", "Noto Sans CJK", "Source Han Sans SC",
        "Source Han Sans CN", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
        "Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", "SimHei", "SimSun");

    private static final String CJK_SAMPLE = "人工智能日报分类封面";

    /** 配色色阶数：24 段，任意两个不同色段至少差 15°，卡片之间不会撞色 */
    private static final int HUE_STEPS = 24;

    private DigestCoverGenerator() {
    }

    /**
     * 绘制封面卡片。
     *
     * @param categoryKey   分类 key：只用来决定配色（同一分类每天颜色一致）
     * @param categoryLabel 分类显示名（可为空）
     * @param title         文章标题（可为空）
     * @param sourceName    来源站点名（可为空）
     * @param dateText      日期文案（如 2026-09-26）
     * @param siteName      站点名（可为空）
     * @return PNG 字节
     */
    public static byte[] renderCard(String categoryKey, String categoryLabel, String title,
        String sourceName, String dateText, String siteName) throws IOException {
        var canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        try {
            applyHints(graphics);
            paintBackground(graphics, categoryKey);

            paintCategory(graphics, categoryLabel);
            paintTitle(graphics, title);
            paintFooter(graphics, sourceName, dateText, siteName);
        } finally {
            graphics.dispose();
        }

        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, FORMAT, output)) {
            throw new IOException("PNG 编码器不可用，无法生成封面卡片");
        }
        return output.toByteArray();
    }

    /** 服务端有没有能显示中文的字体（后台自检与日志用） */
    /** 卡片上的中文能不能画出来（内置字体在就一定能） */
    public static boolean cjkAvailable() {
        return Fonts.BUNDLED != null || Fonts.SYSTEM_CJK != null;
    }

    /** 实际用的是哪里的字体（自检与日志里显示，出问题时一眼知道是不是字体没加载上） */
    public static String fontSourceLabel() {
        if (Fonts.BUNDLED != null) {
            return BUNDLED_FONT_NAME;
        }
        if (Fonts.SYSTEM_CJK != null) {
            return "系统字体：" + Fonts.SYSTEM_CJK.getFamily();
        }
        return "无可用中文字体（卡片不含中文文案）";
    }

    // ── 绘制 ──

    private static void applyHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
            RenderingHints.VALUE_STROKE_PURE);
    }

    /** 深色渐变底 + 两个半透明装饰圆：不同分类配色不同，但同一分类稳定 */
    private static void paintBackground(Graphics2D graphics, String categoryKey) {
        var hue = hueOf(categoryKey);
        var from = Color.getHSBColor(hue, 0.62f, 0.26f);
        var to = Color.getHSBColor((hue + 0.07f) % 1f, 0.72f, 0.56f);
        graphics.setPaint(new GradientPaint(0, 0, from, WIDTH, HEIGHT, to));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        graphics.setColor(new Color(255, 255, 255, 20));
        graphics.fillOval(WIDTH - 420, -220, 620, 620);
        graphics.setColor(new Color(255, 255, 255, 14));
        graphics.fillOval(WIDTH - 200, 140, 340, 340);
    }

    private static void paintCategory(Graphics2D graphics, String label) {
        var run = textRun(label, Font.BOLD, 40);
        if (run == null) {
            return;
        }
        graphics.setFont(run.font());
        graphics.setColor(new Color(255, 255, 255, 224));
        graphics.drawString(run.text(), MARGIN, 148);
        graphics.setColor(new Color(255, 255, 255, 150));
        graphics.fillRoundRect(MARGIN, 176, 104, 6, 6, 6);
    }

    private static void paintTitle(Graphics2D graphics, String title) {
        var run = textRun(title, Font.BOLD, 58);
        if (run == null) {
            return;
        }
        var font = run.font();
        graphics.setFont(font);
        var lines = wrap(graphics, run.text(), font, WIDTH - 2 * MARGIN, 2);
        var metrics = graphics.getFontMetrics(font);
        var lineHeight = metrics.getHeight() + 12;
        // 两行标题时整体上移，视觉重心保持在卡片中部
        var baseline = lines.size() > 1 ? 320 : 350;
        for (var line : lines) {
            // 先描一层淡影，浅色渐变底上也能看清
            graphics.setColor(new Color(0, 0, 0, 60));
            graphics.drawString(line, MARGIN + 2, baseline + 2);
            graphics.setColor(Color.WHITE);
            graphics.drawString(line, MARGIN, baseline);
            baseline += lineHeight;
        }
    }

    private static void paintFooter(Graphics2D graphics, String source, String dateText,
        String siteName) {
        var left = new StringBuilder();
        if (StringUtils.hasText(source)) {
            left.append(source.trim());
        }
        if (StringUtils.hasText(dateText)) {
            if (!left.isEmpty()) {
                left.append(" · ");
            }
            left.append(dateText);
        }
        if (left.isEmpty()) {
            left.append(LATIN_FALLBACK);
        }
        var run = textRun(left.toString(), Font.PLAIN, 30);
        if (run == null) {
            return;
        }
        var font = run.font();
        graphics.setFont(font);
        graphics.setColor(new Color(255, 255, 255, 200));
        var baseline = HEIGHT - MARGIN + 12;
        graphics.drawString(run.text(), MARGIN, baseline);

        var siteRun = textRun(siteName, Font.PLAIN, 30);
        if (siteRun != null) {
            var metrics = graphics.getFontMetrics(font);
            var width = metrics.stringWidth(siteRun.text());
            if (width < WIDTH / 2) {
                graphics.setColor(new Color(255, 255, 255, 176));
                graphics.drawString(siteRun.text(), WIDTH - MARGIN - width, baseline);
            }
        }
    }

    // ── 文本处理 ──

    /** 一段画得出的文案 + 画它用的字体（两者必须成对解析，否则测量与绘制会用不同字体） */
    private record TextRun(String text, Font font) {
    }

    /**
     * 准备要画的一段文字：挑字体 → 逐字丢掉画不出来的字。
     *
     * <p>丢掉个别字而不是整段不画：内置字库有 6763 个常用字，偶尔碰到生僻字时
     * 少一个字远好过整张卡片没有标题；也绝不画成方框。</p>
     */
    private static TextRun textRun(String raw, int style, int size) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        var trimmed = raw.trim();
        var base = Fonts.pick(trimmed);
        var font = base.deriveFont(style, (float) size);
        var kept = filterDisplayable(font, trimmed);
        if (!StringUtils.hasText(kept)) {
            // 一个都画不出来（例如内置字体资源缺失且系统也没有中文字体）
            kept = asciiOnly(trimmed);
        }
        return StringUtils.hasText(kept) ? new TextRun(kept, font) : null;
    }

    /** 逐字过滤：只留下当前字体画得出的字符（空白没有字形，照留） */
    static String filterDisplayable(Font font, String text) {
        var builder = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            if (Character.isWhitespace(codePoint) || font.canDisplay(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString().trim();
    }

    /** 只保留 ASCII 可见字符（连内置字体都不可用时的最后一道降级，保证不会出现方框） */
    static String asciiOnly(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var builder = new StringBuilder();
        for (var i = 0; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch >= 0x20 && ch < 0x7F) {
                builder.append(ch);
            } else if (ch == '、' || ch == '，') {
                builder.append(", ");
            }
        }
        var kept = builder.toString().replaceAll("\\s{2,}", " ").trim();
        // 只剩标点/空白的降级文案没有信息量
        return kept.replaceAll("[,\\s]+", "").isEmpty() ? "" : kept;
    }

    /**
     * 折行结果（包内可见：断行位置是最容易悄悄退化的地方——英文被劈成两半时
     * 只能从图上看出来，让测试直接验证断点，不必靠像素反推）。
     */
    static List<String> wrapLines(String text, Font font, int maxWidth, int maxLines) {
        var scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            return wrap(scratch, text, font, maxWidth, maxLines);
        } finally {
            scratch.dispose();
        }
    }

    /**
     * 折行：中文可以逐字断，英文按单词整块搬。
     *
     * <p>逐字断对中文没问题，但英文标题会被劈成 {@code "…on long c" / "ontext"} 这样，
     * 所以先按「中文单字 / 英文单词 / 空格」切块，再整块排版。</p>
     */
    private static List<String> wrap(Graphics2D graphics, String text, Font font, int maxWidth,
        int maxLines) {
        var metrics = graphics.getFontMetrics(font);
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var token : tokens(text, metrics, maxWidth)) {
            if (!current.isEmpty() && metrics.stringWidth(current.toString() + token) > maxWidth) {
                lines.add(current.toString().stripTrailing());
                current.setLength(0);
                if (lines.size() >= maxLines) {
                    // 行数用尽还有内容没排 → 最后一行截断加省略号
                    return withEllipsis(lines, metrics, maxWidth);
                }
            }
            // 换行后的行首不要留空格
            if (current.isEmpty() && token.isBlank()) {
                continue;
            }
            current.append(token);
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(current.toString().stripTrailing());
        }
        return lines;
    }

    /** 排版块：中文单字、英文单词、空格；超宽的长词先按宽度硬切，保证每块都放得下 */
    private static List<String> tokens(String text, FontMetrics metrics, int maxWidth) {
        var tokens = new ArrayList<String>();
        var word = new StringBuilder();
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            var single = new String(Character.toChars(codePoint));
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                flushWord(word, tokens, metrics, maxWidth);
                tokens.add(" ");
            } else if (isBreakable(codePoint)) {
                flushWord(word, tokens, metrics, maxWidth);
                tokens.add(single);
            } else {
                word.append(single);
            }
        }
        flushWord(word, tokens, metrics, maxWidth);
        return tokens;
    }

    /** 中文（含全角标点）可以逐字断行；其它按单词整体处理 */
    private static boolean isBreakable(int codePoint) {
        return Character.isIdeographic(codePoint)
            || (codePoint >= 0x3000 && codePoint <= 0x303F)
            || (codePoint >= 0xFF00 && codePoint <= 0xFF65);
    }

    private static void flushWord(StringBuilder word, List<String> tokens, FontMetrics metrics,
        int maxWidth) {
        if (word.isEmpty()) {
            return;
        }
        var value = word.toString();
        word.setLength(0);
        // 单词本身就比一整行还宽（超长单词、URL）：按宽度切开，别让它引起死循环
        while (metrics.stringWidth(value) > maxWidth && value.length() > 1) {
            var cut = value.length() - 1;
            while (cut > 1 && metrics.stringWidth(value.substring(0, cut)) > maxWidth) {
                cut--;
            }
            tokens.add(value.substring(0, cut));
            value = value.substring(cut);
        }
        tokens.add(value);
    }

    private static List<String> withEllipsis(List<String> lines, FontMetrics metrics, int maxWidth) {
        var last = lines.get(lines.size() - 1);
        lines.set(lines.size() - 1, ellipsize(metrics, last, maxWidth));
        return lines;
    }

    private static String ellipsize(FontMetrics metrics, String line, int maxWidth) {
        var text = line;
        while (!text.isEmpty() && metrics.stringWidth(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text.stripTrailing() + "…";
    }

    /**
     * 分类 key → 色相（0..1）：同一分类每天同色，不同分类至少差 15°。
     *
     * <p>用 {@code String.hashCode} 连续映射时，短 key 的哈希值很接近，会出现两张卡片看着一个颜色
     * （线上第一版的安全与工程就是这样）。这里改成「FNV-1a 撒散 + 24 段色阶取模」：
     * 结构相近的 key 也会落到不同色段，且任意两个不同色段至少差 15°。</p>
     */
    static float hueOf(String categoryKey) {
        var key = categoryKey == null ? "" : categoryKey.trim().toLowerCase(Locale.ROOT);
        var hash = 0x811C9DC5;
        for (var i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x01000193;
        }
        var index = (hash & 0x7FFFFFFF) % HUE_STEPS;
        // 取每段中点，避免正好落在色环接缝上
        return (index + 0.5f) / HUE_STEPS;
    }

    // ── 字体 ──

    /**
     * 字体解析结果（只解析一次）。
     *
     * <p>加载内置字体、扫描系统字体都有开销，而结果在一个进程里不会变，所以放在静态内部类里缓存：
     * 类初始化由 JVM 保证线程安全，也不需要自己加锁。</p>
     *
     * <p><b>字段顺序即初始化顺序</b>，{@code SYSTEM_CJK} 依赖 {@code AVAILABLE}，反过来写会读到 null。</p>
     */
    private static final class Fonts {

        /** 内置字体：随插件分发，服务器上没有任何字体时也能画中文 */
        private static final Font BUNDLED = loadBundled();

        /** 系统字体族（内置字体缺字时才查它） */
        private static final List<String> AVAILABLE = availableFamilies();

        /** 系统上能显示中文的字体；null = 没有 */
        private static final Font SYSTEM_CJK = resolveSystemCjk();

        private Fonts() {
        }

        /**
         * 按文本挑字体：内置优先（各站点观感一致且一定有中文），
         * 内置子集缺字时看系统 CJK 字体，都没有就用逻辑字体（至少有 ASCII 字形）。
         */
        static Font pick(String text) {
            if (BUNDLED != null && BUNDLED.canDisplayUpTo(text) < 0) {
                return BUNDLED;
            }
            if (SYSTEM_CJK != null && SYSTEM_CJK.canDisplayUpTo(text) < 0) {
                return SYSTEM_CJK;
            }
            if (BUNDLED != null) {
                return BUNDLED;
            }
            return SYSTEM_CJK != null ? SYSTEM_CJK : new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        }

        /** 读取插件内置字体；资源缺失或格式不被支持时返回 null（随后会退到系统字体） */
        private static Font loadBundled() {
            try (var stream = DigestCoverGenerator.class.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
                if (stream == null) {
                    log.warn("[UniHalo][Digest] 内置封面字体缺失（{}），将改用系统字体",
                        BUNDLED_FONT_RESOURCE);
                    return null;
                }
                var font = Font.createFont(Font.TRUETYPE_FONT, stream);
                log.info("[UniHalo][Digest] 封面标题卡片使用内置字体：{}", font.getFontName());
                return font;
            } catch (Exception | Error ex) {
                // 连 OutOfMemoryError 也接住：字体加载失败只该让卡片少点文字，不该让整轮日报挂掉
                log.warn("[UniHalo][Digest] 内置封面字体加载失败，将改用系统字体：{}", ex);
                return null;
            }
        }

        private static List<String> availableFamilies() {
            try {
                return List.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());
            } catch (Throwable ex) {
                log.warn("[UniHalo][Digest] 读取系统字体列表失败：{}", ex.getMessage());
                return List.of();
            }
        }

        private static Font resolveSystemCjk() {
            for (var candidate : CJK_CANDIDATES) {
                if (AVAILABLE.stream().noneMatch(name -> name.equalsIgnoreCase(candidate))) {
                    continue;
                }
                try {
                    var font = new Font(candidate, Font.PLAIN, 32);
                    if (font.canDisplayUpTo(CJK_SAMPLE) < 0) {
                        return font;
                    }
                } catch (Throwable ignored) {
                    // 换下一个候选
                }
            }
            return null;
        }
    }
}
