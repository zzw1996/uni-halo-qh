package pro.uhalo.uni.utils;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用设置白名单：这是本项目第一个「形态自由」的匿名可写接口的守门人。
 *
 * <p>钉住三件事：① 键集就是设置页能改的那些（多了会同步无意义的东西，
 * 少了用户改了却同步不上）；② 类型不对/取值非法的键<b>丢弃</b>而不是回落默认值
 * （回落会改掉用户没动过的设置）；③ 存储体积有上限。</p>
 */
class AppSettingsCatalogTest {

    /** 期望的键集：与前端 utils/app.js 的 _DefaultAppSettings + utils/skin.js 的外观模式一一对应 */
    private static final java.util.Set<String> EXPECTED = java.util.Set.of(
        "articleCard.showCover", "articleDetail.showStats", "flotbutton.show",
        "banner.useDot", "banner.dotPosition",
        "layout.home", "layout.cardType",
        "tabbar.useCustom", "tabbar.show.category", "tabbar.show.moments",
        "tabbar.show.rank", "tabbar.show.gallery", "tabbar.show.store",
        "appearance.mode");

    @Test
    void keySetShouldMatchTheSettingsPage() {
        assertThat(AppSettingsCatalog.keys()).isEqualTo(EXPECTED);
        // 存储体积的上限就是白名单大小：因此不需要再对「存进去的内容」做体积校验
        assertThat(AppSettingsCatalog.maxStoredKeys()).isEqualTo(EXPECTED.size());
        // 请求上限必须比白名单宽松，否则正常的全量提交会被自己挡住
        assertThat(AppSettingsCatalog.MAX_REQUEST_KEYS).isGreaterThan(EXPECTED.size());
    }

    @Test
    void booleanKeysAcceptOnlyRealBooleans() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("tabbar.show.rank", false);
        raw.put("tabbar.useCustom", true);
        // 传成字符串一律丢弃：不猜 "true"/"1" 的意思
        raw.put("flotbutton.show", "true");

        var result = AppSettingsCatalog.sanitize(raw);

        assertThat(result.accepted())
            .containsEntry("tabbar.show.rank", false)
            .containsEntry("tabbar.useCustom", true)
            .doesNotContainKey("flotbutton.show");
        assertThat(result.rejected()).containsExactly("flotbutton.show");
    }

    @Test
    void enumKeysAcceptOnlyListedValuesAndDropInsteadOfClamping() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("layout.home", "h_row_col2");
        raw.put("appearance.mode", "dark");
        // 非法枚举：丢弃而不是回落成默认值——回落会改掉用户没动过的设置
        raw.put("layout.cardType", "tb_image_text_v2");

        var result = AppSettingsCatalog.sanitize(raw);

        assertThat(result.accepted())
            .containsEntry("layout.home", "h_row_col2")
            .containsEntry("appearance.mode", "dark")
            .doesNotContainKey("layout.cardType");
        assertThat(result.rejected()).containsExactly("layout.cardType");
    }

    @Test
    void unknownKeysAndBadValuesAreReported() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("tabbar.show.rank", true);
        raw.put("ad.disabled", true);          // 设置页没有入口的键：不入白名单
        raw.put("tabbar.show.home", true);     // 固定项没有开关
        raw.put("layout", Map.of("home", "h_row_col1")); // 只认扁平点号键，不收嵌套对象
        raw.put("  ", true);                   // 空键

        var result = AppSettingsCatalog.sanitize(raw);

        assertThat(result.accepted()).containsExactly(Map.entry("tabbar.show.rank", true));
        assertThat(result.rejected())
            .containsExactlyInAnyOrder("ad.disabled", "tabbar.show.home", "layout", "(空键)");
    }

    @Test
    void nullAndEmptyInputProduceNothing() {
        assertThat(AppSettingsCatalog.sanitize(null).accepted()).isEmpty();
        assertThat(AppSettingsCatalog.sanitize(null).rejected()).isEmpty();
        assertThat(AppSettingsCatalog.sanitize(Map.of()).accepted()).isEmpty();

        var result = AppSettingsCatalog.sanitize(Map.of("tabbar.useCustom", true));
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void acceptedValuesAreTrimmedForEnums() {
        // 客户端多打个空格不该导致整项丢失
        var result = AppSettingsCatalog.sanitize(Map.of("appearance.mode", " dark "));
        assertThat(result.accepted()).containsEntry("appearance.mode", "dark");
        assertThat(result.rejected()).isEmpty();
    }
}
