package pro.uhalo.uni.dto;

import pro.uhalo.uni.utils.DigestCategoryCatalog;
import pro.uhalo.uni.utils.DigestConfigHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 后台「日报分类」面板的视图数据：当前生效的分类清单 + 每一行的落库结论。
 *
 * <p>这张表回答站长两个问题：「AI 现在能在哪些分类里选」，以及「这些分类发布时会落到哪里」。
 * 尤其是 {@code reuse} / {@code map} 两种模式——匹配不上是静默跳过的，
 * 只有在后台把问题行列出来，站长才不会以为配置已经生效。</p>
 *
 * @param fromConfig false 表示清单留空、正在用内置六类（默认状态，与升级前行为一致）
 * @param source 清单来源的中文说明
 * @param mode 落库方式（create / reuse / map）
 * @param modeLabel 落库方式的中文名
 * @param unknownCategoryPolicy AI 给出清单外分类时的处理方式
 * @param count 生效的分类数量
 * @param problemCount 需要站长处理的行数（reuse / map 下匹配不上、map 下漏配映射）
 * @param items 逐行结论
 * @param message 一句话总结
 */
public record DigestCategoryViewDTO(
    boolean fromConfig,
    String source,
    String mode,
    String modeLabel,
    String unknownCategoryPolicy,
    int count,
    int problemCount,
    List<Item> items,
    String message) {

    /**
     * 清单里的一行。
     *
     * @param key AI 只能从这个 key 里选
     * @param label 渲染与创建分类时用的中文名
     * @param emoji 渲染分组标题时的图标
     * @param description 给 AI 的判定说明
     * @param targetCategory 清单里填的「映射到已有分类」（map 模式使用，可为空）
     * @param exists 站点上是否已有能匹配上的分类（create 模式下没有就会自动创建）
     * @param problem 这一行会不会导致「静默不打分类」，需要在后台高亮
     * @param message 这一行的结论
     */
    public record Item(
        String key,
        String label,
        String emoji,
        String description,
        String targetCategory,
        boolean exists,
        boolean problem,
        String message) {
    }

    /**
     * 按「当前配置的清单 + 站点上已有的分类名」算出一张结论表。
     *
     * <p>判断「能不能匹配上」用的是发布时的同一套口径（分类的 name / slug / 显示名，
     * 大小写不敏感）——自检说没问题、发布却静默跳过，是最容易让站长白折腾的一种 bug。</p>
     *
     * @param catalog       生效的分类目录
     * @param mode          落库方式
     * @param policy        清单外分类的处理方式
     * @param existingNames 站点已有分类的可匹配名字（小写）
     */
    public static DigestCategoryViewDTO of(DigestCategoryCatalog catalog, String mode, String policy,
        Set<String> existingNames) {
        var directory = catalog == null ? DigestCategoryCatalog.defaultCatalog() : catalog;
        var normalizedMode = normalizeMode(mode);
        var names = existingNames == null ? Set.<String>of() : existingNames;
        var items = new ArrayList<Item>();
        var problems = 0;
        for (var entry : directory.categories()) {
            var item = toItem(entry, normalizedMode, names);
            if (item.problem()) {
                problems++;
            }
            items.add(item);
        }
        var source = directory.fromConfig() ? "后台配置的分类清单" : "内置六类（清单留空）";
        return new DigestCategoryViewDTO(directory.fromConfig(), source, normalizedMode,
            DigestConfigHelper.categoryModeLabel(normalizedMode), policy, items.size(), problems,
            List.copyOf(items), describe(normalizedMode, items.size(), problems));
    }

    private static Item toItem(DigestCategoryConfigDTO entry, String mode, Set<String> names) {
        var key = entry.key();
        // reuse 模式拿 key 与中文显示名一起匹配（站长的分类名往往是中文）
        var keyExists = contains(names, key)
            || (mode.equals(DigestConfigHelper.CATEGORY_MODE_REUSE) && contains(names, entry.label()));
        var target = entry.hasTarget() ? entry.targetCategory().trim() : "";
        var targetExists = !target.isEmpty() && contains(names, target);
        return switch (mode) {
            case DigestConfigHelper.CATEGORY_MODE_REUSE -> new Item(key, entry.label(),
                entry.emoji(), entry.description(), target, keyExists, !keyExists,
                keyExists ? "站点上已有对应分类（按英文标识或中文显示名匹配），会直接复用"
                    : "站点上没有这个分类，该模式不会自动创建 → 这类文章最终不会打分类");
            case DigestConfigHelper.CATEGORY_MODE_MAP -> {
                if (target.isEmpty()) {
                    yield new Item(key, entry.label(), entry.emoji(), entry.description(), target,
                        false, true, "没填「映射到已有分类」，这行会被跳过（该模式下不会自动创建）");
                }
                yield new Item(key, entry.label(), entry.emoji(), entry.description(), target,
                    targetExists, !targetExists, targetExists
                        ? "映射到站点分类「" + target + "」"
                        : "映射目标「" + target + "」在站点上不存在，会被跳过（不会自动创建）");
            }
            default -> new Item(key, entry.label(), entry.emoji(), entry.description(), target,
                keyExists, false, keyExists
                    ? "站点上已有同名分类，会直接复用（不会改动它现有的显示名与描述）"
                    : "站点上还没有，发布时会自动创建为站点分类");
        };
    }

    private static String describe(String mode, int count, int problems) {
        var head = "当前生效 " + count + " 个分类，落库方式：" + DigestConfigHelper.categoryModeLabel(mode);
        if (problems == 0) {
            return head + "。配置正常。";
        }
        return head + "。其中 " + problems + " 行有问题（下表已标红）："
            + "这些行发布时会被静默跳过，不会自动创建分类——"
            + "要么去站点后台把分类建好，要么把落库方式改成「不存在则自动创建」。";
    }

    /** 与发布时的 {@code DigestTaxonomyService#matchOne} 保持同一口径：大小写不敏感 */
    private static boolean contains(Set<String> names, String value) {
        return value != null && !value.isEmpty() && names.contains(value.toLowerCase(Locale.ROOT));
    }

    private static String normalizeMode(String mode) {
        if (DigestConfigHelper.CATEGORY_MODE_REUSE.equals(mode)) {
            return DigestConfigHelper.CATEGORY_MODE_REUSE;
        }
        if (DigestConfigHelper.CATEGORY_MODE_MAP.equals(mode)) {
            return DigestConfigHelper.CATEGORY_MODE_MAP;
        }
        return DigestConfigHelper.CATEGORY_MODE_CREATE;
    }
}
