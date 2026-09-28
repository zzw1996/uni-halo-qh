package pro.uhalo.uni.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「应用设置跟账号走」的键白名单与取值校验：<b>唯一的键清单来源</b>。
 *
 * <p>为什么必须有白名单：这是本项目第一个「形态自由」的匿名可写接口（客户端提交一包键值）。
 * 客户端用<b>扁平点号键</b>提交（如 {@code tabbar.show.rank}），服务端只认这里登记过的键，
 * 其余一律丢弃并在响应里回报（{@code rejected}），绝不把客户端原文落到存储里——
 * 否则任何人都能往扩展存储里塞任意 JSON。</p>
 *
 * <p>为什么只登记这些键：以「用户在设置页真的能改」为准
 * （{@code uni-halo/pagesA/setting/setting.vue}）。{@code ad} / {@code barrage} /
 * {@code about} / {@code showStartPage} 这些键在前端默认值里写死、界面上没有入口，
 * 登记它们只是在同步「永远不变更的默认值」。</p>
 *
 * <p><b>加一个键要三处同步</b>：这里、前端 {@code utils/app.js} 的 {@code _DefaultAppSettings}、
 * 以及设置页的控件。两边类型或取值不一致时，服务端只会丢弃并回报，不会写坏用户的设置。</p>
 *
 * <p>非法取值（例如客户端升级后出现了新的枚举值）一律<b>丢弃而不是回落到默认值</b>：
 * 回落会改掉用户根本没动过的设置，比「这一项这次没同步上」糟得多。</p>
 */
public final class AppSettingsCatalog {

    /**
     * 布尔键（只有 true/false）。
     *
     * <p>{@code tabbar.show.*} 的键取自底栏可选项：服务端
     * {@code UniHaloServiceImpl.TABBAR_OPTIONAL} 与前端 {@code utils/tabbar.js} 的候选键
     * 都必须是这 5 个（固定项「首页/我的」用户不能取消，因此没有开关）。</p>
     */
    private static final List<String> BOOLEAN_KEYS = List.of(
        "articleCard.showCover",
        "articleDetail.showStats",
        "flotbutton.show",
        "banner.useDot",
        "tabbar.useCustom",
        "tabbar.show.category",
        "tabbar.show.moments",
        "tabbar.show.rank",
        "tabbar.show.gallery",
        "tabbar.show.store");

    /** 枚举键：取值必须是清单里的一个（每一项的第一个值即前端默认值，仅作文档意义） */
    private static final Map<String, List<String>> ENUM_KEYS = Map.of(
        "banner.dotPosition", List.of("right", "bottom"),
        "layout.home", List.of("h_row_col1", "h_row_col2"),
        "layout.cardType", List.of("lr_image_text", "lr_text_image", "tb_image_text",
            "tb_text_image", "only_text"),
        // 外观模式不在 APP_GLOBAL_SETTINGS 里（前端是独立键 unihalo_appearance_mode），
        // 但它恰恰是「换设备后最容易被察觉丢了」的一项，所以照同一套扁平键同步。
        // 取值与前端 utils/skin.js 的 APPEARANCE_MODES 一一对应。
        "appearance.mode", List.of("auto", "light", "dark"));

    /**
     * 一次请求最多带多少个键。
     *
     * <p>白名单本身已经限制了「存进去」的体积（最多 14 个键），这道限制只为挡住畸形大包，
     * 让它在解析后立刻被拒，而不必走完查询与写入。</p>
     */
    public static final int MAX_REQUEST_KEYS = 64;

    private AppSettingsCatalog() {
    }

    /** 白名单内的全部键（顺序稳定，供测试与前端契约对照） */
    public static Set<String> keys() {
        var all = new LinkedHashSet<String>(BOOLEAN_KEYS);
        all.addAll(ENUM_KEYS.keySet());
        return Set.copyOf(all);
    }

    /** 最大可存储键数 = 白名单大小（存储内容不可能超出它，因此不需要额外的体积上限） */
    public static int maxStoredKeys() {
        return BOOLEAN_KEYS.size() + ENUM_KEYS.size();
    }

    /**
     * 清洗一份「扁平点号键 → 标量」的映射。
     *
     * <p>不抛异常：丢弃的键通过返回值回报。调用方对「用户自己提交的键被丢弃」可以选择提示，
     * 对「存量数据里的旧键被丢弃」则静默处理——后者是白名单收敛时必然出现的正常情况。</p>
     *
     * @param raw 客户端提交或从存储里解析出来的映射（可为 null）
     * @return 只含白名单内合法取值的键（插入顺序稳定）+ 被丢弃的键名
     */
    public static Sanitized sanitize(Map<String, ?> raw) {
        var accepted = new LinkedHashMap<String, Object>();
        var rejected = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) {
            return new Sanitized(accepted, rejected);
        }
        for (var entry : raw.entrySet()) {
            var key = entry.getKey() == null ? "" : entry.getKey().trim();
            var value = entry.getValue();
            if (BOOLEAN_KEYS.contains(key)) {
                // 只认真正的布尔值：客户端如果把 true 传成字符串 "true"，宁可丢空也不猜
                if (value instanceof Boolean bool) {
                    accepted.put(key, bool);
                } else {
                    rejected.add(key);
                }
                continue;
            }
            var allowed = ENUM_KEYS.get(key);
            if (allowed == null) {
                rejected.add(key.isEmpty() ? "(空键)" : key);
                continue;
            }
            if (value instanceof String text && allowed.contains(text.trim())) {
                accepted.put(key, text.trim());
            } else {
                rejected.add(key);
            }
        }
        return new Sanitized(accepted, rejected);
    }

    /**
     * 一次清洗的结果。
     *
     * @param accepted 白名单内的合法键值（可变，供调用方做字段级合并）
     * @param rejected 被丢弃的键名（客户端版本与白名单不一致时，这是唯一的线索）
     */
    public record Sanitized(Map<String, Object> accepted, List<String> rejected) {

        public boolean isEmpty() {
            return accepted.isEmpty();
        }
    }
}
