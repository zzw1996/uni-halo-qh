package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;
import pro.uhalo.uni.dto.OutfitItemDTO;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 等级/称号/装扮配置测试。
 *
 * <p>重点锁定主题皮肤（type=theme）的 style 语义：必须是 tm-vuetify 登记的<b>纯色主题名</b>，
 * 不能是 CSS 渐变值。前端 utils/themeSkin.js 的 THEME_SKINS 白名单以此为准，
 * 填错会导致用户付费购买主题后界面无任何变化（静默失败）。</p>
 */
class LevelConfigHelperTest {

    /**
     * 前端 THEME_SKINS 白名单登记的主题名，必须与此保持同步。
     * 若将来在 themeSkin.js 中增删主题，此处需同步修改。
     */
    private static final Set<String> FRONTEND_THEME_SKINS = Set.of(
        "light-blue", "primary", "blue", "cyan",
        "green", "orange", "deep-orange", "blue-grey"
    );

    private static List<OutfitItemDTO> themeOutfits() {
        return LevelConfigHelper.defaults().getOutfits().stream()
            .filter(item -> "theme".equals(item.getType()))
            .toList();
    }

    /** 默认主题装扮必须存在，否则皮肤功能没有开箱可用的商品。 */
    @Test
    void defaultsShouldContainThemeOutfits() {
        var themes = themeOutfits();

        assertThat(themes).hasSize(2);
        assertThat(themes).extracting(OutfitItemDTO::getKey)
            .containsExactly("theme_dusk", "theme_sunset");
    }

    /**
     * 核心约束：theme 装扮的 style 必须是纯色主题名，不能是 CSS 渐变值。
     *
     * <p>两个原因：</p>
     * <ol>
     *   <li>前端 THEME_SKINS 白名单只登记纯色主题名，渐变值会被判为未登记而静默回退默认配色，
     *       用户花了积分却看不到任何变化；</li>
     *   <li>tm-vuetify 的按钮阴影按 {@code shadow-{主题名}-{n}} 拼接类名，
     *       框架只为纯色主题生成了这些类（每个 26 个），渐变名对应的
     *       {@code shadow-bg-gradient-*} 类<b>不存在</b>，设为全局色会让阴影静默失效。</li>
     * </ol>
     */
    @Test
    void themeOutfitStyleMustBeRegisteredSolidThemeName() {
        var themes = themeOutfits();

        assertThat(themes).allSatisfy(item -> {
            var style = item.getStyle();
            assertThat(style)
                .as("主题装扮「%s」的 style 必须是已登记的纯色主题名", item.getName())
                .isIn(FRONTEND_THEME_SKINS);
            // 渐变值特征：含逗号或 # —— 一律不允许
            assertThat(style)
                .as("主题装扮「%s」的 style 不能是 CSS 渐变值", item.getName())
                .doesNotContain(",")
                .doesNotContain("#");
        });
    }

    /** 非 theme 类型的装扮不受纯色主题名约束（头像框用样式名、昵称前缀用文本）。 */
    @Test
    void nonThemeOutfitsKeepTheirOwnStyleSemantics() {
        var outfits = LevelConfigHelper.defaults().getOutfits();

        var avatarFrames = outfits.stream()
            .filter(item -> "avatar_frame".equals(item.getType())).toList();
        var nickFrames = outfits.stream()
            .filter(item -> "nick_frame".equals(item.getType())).toList();

        assertThat(avatarFrames).extracting(OutfitItemDTO::getStyle)
            .containsExactly("gold", "rainbow");
        // 昵称前缀是展示文本，允许 emoji，不适用主题名约束
        assertThat(nickFrames).extracting(OutfitItemDTO::getStyle)
            .containsExactly("⭐", "👑");
    }

    /** 装扮类型白名单应包含 theme，且不应引入未支持的类型。 */
    @Test
    void outfitTypesShouldIncludeTheme() {
        assertThat(LevelConfigHelper.OUTFIT_TYPES)
            .containsExactlyInAnyOrder("avatar_frame", "nick_frame", "theme");
    }

    /**
     * 昵称前缀长度约束：style 即前缀文本（如 "⭐"），前端 nickFramePrefix() 对超过
     * {@code NICK_FRAME_MAX_LENGTH}（6，JS 与 Java 同样按 UTF-16 计）的文本判为无效并丢弃，
     * 以免顶掉昵称本身的展示空间。默认配置若超限，默认装扮自己就不会显示。
     */
    @Test
    void nickFrameStyleShouldStayWithinFrontendLengthLimit() {
        var nickFrames = LevelConfigHelper.defaults().getOutfits().stream()
            .filter(item -> "nick_frame".equals(item.getType()))
            .toList();

        assertThat(nickFrames).isNotEmpty();
        assertThat(nickFrames).allSatisfy(item -> assertThat(item.getStyle().length())
            .as("昵称前缀「%s」的长度需不超过 6（前端 NICK_FRAME_MAX_LENGTH）", item.getName())
            .isLessThanOrEqualTo(6));
    }

    /** 配置为空时回落默认装扮，回落结果同样满足纯色主题名约束。 */
    @Test
    void parseShouldFallBackToDefaultsWhenOutfitsEmpty() {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var config = LevelConfigHelper.parse(node);

        assertThat(config.getOutfits()).isNotEmpty();
        assertThat(config.getOutfits().stream()
            .filter(item -> "theme".equals(item.getType()))
            .map(OutfitItemDTO::getStyle)
            .toList())
            .allSatisfy(style -> assertThat(style).isIn(FRONTEND_THEME_SKINS));
    }
}
